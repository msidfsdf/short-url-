#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
批量聚合 vs 逐条消费 A/B 压测主控。

做法:
 1. 杀 8082 旧进程
 2. 用 RabbitMQ 管理台 HTTP API (guest/guest) 清空 click.queue + dlx.queue
 3. Redis 预热 4c92 缓存
 4. 用 java -jar 启动后端（带指定 batch-enabled + simulate-latency + 8082）
 5. 等后端就绪 + 预热一次跳转
 6. 读 click.queue 深度
 7. 跑 tools/load_test_async.py -n N -c C -u http://localhost:8082/4c92
 8. 记录压测结束时刻后，轮询 click.queue 深度到 0，得到"队列清空耗时"
 9. 【正确性闸门】校验死信队列深度 == 0 且 DB click_count 增量 ≈ 成功请求数，
    任一不满足即判 FAIL 并以退出码 2 结束（队列空了不等于消费成功）
10. 结果写入 docs/load_results/ 下的 txt 文件
11. 停后端

用法:
  python tools/run_load_test_batch.py \
      --batch-enabled false \
      --simulate-latency-ms 0 \
      --label A_per \
      --total 100000 \
      --concurrency 500 \
      --short-code 4c92

强烈建议先跑小样本冒烟（约 20 秒）确认闸门 PASS 再上全量:
  python tools/run_load_test_batch.py --batch-enabled true --simulate-latency-ms 0 \
      --label SMOKE --total 500 --concurrency 20 --short-code 4c92

数据库连接可用环境变量覆盖: MYSQL_BIN / DB_USER / DB_PASS / DB_NAME
"""
import argparse
import base64
import os
import socket
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAVA = r"C:\Users\LENOVO\Desktop\jdk-17.0.18+8\bin\java.exe"
JAR = ROOT / "short-url-server" / "target" / "short-url-server-1.0.0.jar"
PY = sys.executable if sys.executable else "python"
CLIENT = ROOT / "tools" / "load_test_async.py"
REDIS_CLI = r"D:\Redis-x64-3.2.100\Redis-x64-3.2.100\redis-cli.exe"
MYSQL = os.environ.get("MYSQL_BIN", r"C:\Program Files\MySQL\MySQL Server 5.5\bin\mysql.exe")
DB_USER = os.environ.get("DB_USER", "root")
DB_PASS = os.environ.get("DB_PASS", "root")
DB_NAME = os.environ.get("DB_NAME", "short_url")
DB_TABLE = os.environ.get("DB_TABLE", "t_short_url")
MGMT_USER = "guest"
MGMT_PASS = "guest"
MGMT_BASE = "http://localhost:15672/api"
OUT_DIR = ROOT / "docs" / "load_results"
OUT_DIR.mkdir(parents=True, exist_ok=True)

# 正确性闸门阈值: 落库增量 / 成功请求数 低于此比例即判 FAIL
CLICK_DELTA_MIN_RATIO = 0.99
# 后端 stdout 超过此大小(MB)即告警: 正常压测只有几 MB, 膨胀说明消费端在刷异常堆栈
LOG_SIZE_WARN_MB = 20


def mgmt_headers():
    tok = base64.b64encode(f"{MGMT_USER}:{MGMT_PASS}".encode("ascii")).decode("ascii")
    return {"Authorization": f"Basic {tok}"}


def http(method, url, timeout=5):
    """极简 urllib HTTP（避免依赖 requests）。返回 (status, text)"""
    import urllib.request
    import urllib.error
    req = urllib.request.Request(url, method=method, headers=mgmt_headers())
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        try:
            body = e.read().decode("utf-8", errors="replace")
        except Exception:
            body = ""
        return e.code, body
    except Exception as e:
        return -1, str(e)


def purge_queue(name):
    status, body = http("DELETE", f"{MGMT_BASE}/queues/%2F/{name}/contents")
    return status, body


def queue_depth(name):
    import json
    status, body = http("GET", f"{MGMT_BASE}/queues/%2F/{name}")
    if status != 200:
        return -1
    try:
        data = json.loads(body)
        return int(data.get("messages", -1))
    except Exception:
        return -1


def read_click_count(short_code):
    """从 MySQL 读该短码当前的 click_count。读不到返回 None (不静默当成 0)。"""
    sql = f"SELECT click_count FROM {DB_TABLE} WHERE short_code='{short_code}';"
    try:
        r = subprocess.run(
            [MYSQL, "-h", "127.0.0.1", "-P", "3306", "-u", DB_USER, f"-p{DB_PASS}",
             "-D", DB_NAME, "-N", "-B", "-e", sql],
            capture_output=True, text=True, timeout=15,
        )
        if r.returncode != 0:
            print(f"  [WARN] mysql exit={r.returncode}: {(r.stderr or '').strip()[:200]}")
            return None
        out = (r.stdout or "").strip()
        if not out:
            print(f"  [WARN] mysql 返回空, 短码 {short_code} 可能不存在")
            return None
        return int(out.splitlines()[-1])
    except Exception as e:
        print(f"  [WARN] read_click_count failed: {e}")
        return None


def read_com_update():
    """读 MySQL 全局 Com_update 计数器。

    为什么不用"数日志里的 Preparing 行"来统计 DB 写次数:
      ① 进程被 kill 时 stdout 缓冲未刷盘, 计数会偏低 (实测漏了一半以上)
      ② 开着 MyBatis SQL 日志时, 对照组的日志量天然远大于实验组
         (10万次 UPDATE = 10万行日志), 这个 I/O 开销不公平地拖慢对照组,
         会让"批量聚合更快"的结论掺假
    所以: 关闭 SQL 日志, 改由 MySQL 服务端计数器精确统计 UPDATE 次数。
    """
    try:
        r = subprocess.run(
            [MYSQL, "-h", "127.0.0.1", "-P", "3306", "-u", DB_USER, f"-p{DB_PASS}",
             "-N", "-B", "-e", "SHOW GLOBAL STATUS LIKE 'Com_update';"],
            capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=15,
        )
        if r.returncode != 0:
            return None
        for line in (r.stdout or "").splitlines():
            parts = line.split()
            if len(parts) >= 2 and parts[0] == "Com_update":
                return int(parts[1])
        return None
    except Exception:
        return None


def parse_success_count(load_out):
    """从 load_test_async.py 输出解析 3xx 成功数。

    输出形如:  状态码         : {302: 99500, 'ERR': 500}
    只有 302/301/200 才算"真的点了一次", ERR 不算。
    """
    import re
    m = re.search(r"状态码\s*:\s*\{(.+?)\}", load_out)
    if not m:
        return None
    succ = 0
    for k, v in re.findall(r"'?([A-Za-z0-9_]+)'?\s*:\s*(\d+)", m.group(1)):
        if k in ("302", "301", "200"):
            succ += int(v)
    return succ if succ > 0 else None


def wait_for_click_quiescence(short_code, stable_polls=5, interval=1.0, max_wait=300,
                              label=""):
    """轮询 click_count 直到它连续 stable_polls 次不再变化, 认为消费已结束。

    为什么不用队列深度判断排空:
      RabbitMQ 管理台插件未启用时 HTTP API 不可用, 而旧代码把"读取失败(-1)"
      当成"队列空(0)"处理, 导致四组压测全部显示 QueueDrained=True 的假象。
    click_count 才是我们真正关心的落库事实, 直接盯它更可靠:
      - 消费者全挂   -> 数字永远不涨, 增量 = 0, 闸门直接判 FAIL
      - 消息进死信   -> 数字提前停止增长, 增量 < 成功数, 闸门同样判 FAIL
    返回 (最终值, 是否达到稳态, 耗时秒)。
    """
    t0 = time.time()
    last = None
    same = 0
    while time.time() - t0 < max_wait:
        cur = read_click_count(short_code)
        if cur is not None and cur == last:
            same += 1
            if same >= stable_polls:
                return cur, True, time.time() - t0
        else:
            same = 0
        last = cur
        time.sleep(interval)
    return last, False, time.time() - t0


def count_sql_updates(log_path):
    """统计后端日志里实际执行的 UPDATE 次数。

    MyBatis 开了 StdOutImpl, 每条 SQL 都会打 "Preparing:" 到 stdout。
    这是"批量聚合把 N 次写压成 M 次"这个核心主张的直接证据。
    """
    try:
        with open(log_path, "r", encoding="utf-8", errors="replace") as fp:
            n = 0
            for line in fp:
                if "Preparing:" in line and "update" in line.lower():
                    n += 1
            return n
    except Exception:
        return None


def kill_backend():
    """杀掉监听 8082 或 CommandLine 带 server.port=8082 的 java 进程"""
    # 8082 TCP
    try:
        result = subprocess.run(
            ["netstat", "-ano", "-p", "TCP"], capture_output=True, text=True, timeout=10
        )
        for line in result.stdout.splitlines():
            parts = line.split()
            if len(parts) >= 5 and ":8082" in parts[1] and parts[3] == "LISTENING":
                pid = parts[-1]
                try:
                    subprocess.run(["taskkill", "/F", "/PID", pid], capture_output=True, timeout=5)
                    print(f"  kill 8082-listener PID={pid}")
                except Exception as e:
                    print(f"  kill failed {pid}: {e}")
    except Exception as e:
        print(f"  netstat kill error: {e}")
    # WMI java server.port=8082
    try:
        ps = subprocess.run(
            ["powershell", "-NoProfile", "-Command",
             "Get-WmiObject Win32_Process -Filter \"name='java.exe'\" | "
             "Where-Object { $_.CommandLine -match 'server\\\\.port=8082' } | "
             "ForEach-Object { $_.ProcessId }"],
            capture_output=True, text=True, timeout=10
        )
        for pid in ps.stdout.split():
            pid = pid.strip()
            if pid.isdigit():
                try:
                    subprocess.run(["taskkill", "/F", "/PID", pid], capture_output=True, timeout=5)
                    print(f"  kill java(PID={pid}) with server.port=8082")
                except Exception as e:
                    print(f"  kill failed {pid}: {e}")
    except Exception as e:
        print(f"  wmi kill error: {e}")
    time.sleep(2)


def port_ready(port, timeout_s=80):
    t0 = time.time()
    while time.time() - t0 < timeout_s:
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=1.5):
                return True, time.time() - t0
        except OSError:
            time.sleep(1.5)
    return False, timeout_s


def main():
    ap = argparse.ArgumentParser()
    # 仅用于结果文件命名与标注: 代码已移除 batch-enabled 开关(批量与单条签名不兼容),
    # 该参数不再影响运行行为。逐条基线请用阶段2 原始代码打包的 jar 来跑。
    ap.add_argument("--batch-enabled", default="true", choices=["true", "false"])
    # false = 阶段1 同步 UPDATE(不走 MQ), 用于把"批量聚合"与"同步写库"放在同一场对比
    ap.add_argument("--async-enabled", default="true", choices=["true", "false"])
    ap.add_argument("--simulate-latency-ms", required=True)
    ap.add_argument("--label", required=True)
    ap.add_argument("--total", type=int, default=100000)
    ap.add_argument("--concurrency", type=int, default=500)
    ap.add_argument("--short-code", default="4c92")
    # 慢下游时逐条消费会非常慢(单消费者串行, N 条 × latency), 等待上限要放宽
    ap.add_argument("--settle-max-wait", type=int, default=300,
                    help="等待 click_count 稳定的最长时间(秒)")
    args = ap.parse_args()

    tag = (f"{args.label}_batch{args.batch_enabled}_lat{args.simulate_latency_ms}"
           f"_n{args.total}_c{args.concurrency}")
    run_id = time.strftime("%Y%m%d_%H%M%S")
    print(f"\n===== [RUN] {tag}  ({run_id}) =====", flush=True)

    # 1. kill old + purge queues + warm Redis
    print("[1/9] Kill old backend...")
    kill_backend()

    print("[2/9] Purge RabbitMQ queues...")
    for qn in ["short-url.click.queue", "short-url.click.dlx.queue"]:
        s, _ = purge_queue(qn)
        print(f"  purge {qn} -> status={s}")

    print("[3/9] Warm Redis short:url:{args.short_code} ...")
    try:
        subprocess.run(
            [REDIS_CLI, "-h", "localhost", "-p", "6379", "SET",
             f"short:url:{args.short_code}", "https://www.baidu.com/s?wd=hello", "EX", "600"],
            capture_output=True, timeout=5, check=False,
        )
    except Exception as e:
        print(f"  redis-cli warm error: {e}")

    # 4. start backend
    print("[4/9] Start backend ...")
    out_dir_log = OUT_DIR
    stdout_p = out_dir_log / f"{run_id}_{tag}.backend.stdout.log"
    stderr_p = out_dir_log / f"{run_id}_{tag}.backend.stderr.log"
    fo = open(stdout_p, "w", encoding="utf-8")
    fe = open(stderr_p, "w", encoding="utf-8")
    cmd = [
        JAVA,
        "-Dserver.port=8082",
        "-Dserver.tomcat.max-threads=500",
        "-Dserver.tomcat.accept-count=2000",
        "-Dserver.tomcat.max-connections=5000",
        f"-Dshort-url.click.simulate-db-latency-ms={args.simulate_latency_ms}",
        f"-Dshort-url.click.async-enabled={args.async_enabled}",
        # 关掉 MyBatis 的逐条 SQL 日志: 它让对照组(逐条)的日志量远大于实验组(批量),
        # 这部分同步 stdout I/O 会成为不公平的性能干扰项。
        # UPDATE 次数改由 MySQL 的 Com_update 全局计数器精确统计。
        "-Dmybatis-plus.configuration.log-impl="
        "org.apache.ibatis.logging.nologging.NoLoggingImpl",
        "-jar", str(JAR),
    ]
    proc = subprocess.Popen(cmd, stdout=fo, stderr=fe)
    print(f"  PID={proc.pid}")

    # 5. wait up
    print("[5/9] Wait backend 8082 ...")
    ok, waited = port_ready(8082, timeout_s=90)
    if not ok:
        print(f"  FATAL backend not up after {waited:.0f}s")
        try:
            with open(stderr_p, "r", encoding="utf-8", errors="replace") as fp:
                lines = fp.readlines()
                print("".join(lines[-40:]))
        except Exception:
            pass
        try:
            proc.kill()
        except Exception:
            pass
        sys.exit(1)
    print(f"  up after {waited:.1f}s, waiting consumer bind 3s")
    time.sleep(3)

    # warmup jump
    import urllib.request
    try:
        urllib.request.urlopen(f"http://127.0.0.1:8082/{args.short_code}", timeout=5)
    except Exception:
        pass
    pre_depth = queue_depth("short-url.click.queue")
    if pre_depth is not None and pre_depth < 0:
        print("  [INFO] 管理台 API 不可用(插件未启用), 队列深度无法采集; 改用 click_count 稳态判定")

    # 等 click_count 稳定: 把上一轮遗留的消息先消费干净, 避免污染本轮增量
    print("  wait click_count quiescence before test (drain leftovers) ...")
    pre_click, pre_stable, pre_wait = wait_for_click_quiescence(
        args.short_code, stable_polls=5, interval=1.0, max_wait=120, label="pre")
    print(f"  pre click_count={pre_click} stable={pre_stable} waited={pre_wait:.1f}s")
    pre_com_update = read_com_update()
    if pre_click is None:
        print("  FATAL 读不到 click_count, 闸门无法工作")
        try:
            proc.kill()
        except Exception:
            pass
        kill_backend()
        sys.exit(1)

    # 6. load test
    print(f"[6/9] Load test start n={args.total} c={args.concurrency}")
    t0 = time.time()
    result_p = out_dir_log / f"{run_id}_{tag}.result.txt"
    client_args = [PY, str(CLIENT),
                   "-u", f"http://127.0.0.1:8082/{args.short_code}",
                   "-n", str(args.total),
                   "-c", str(args.concurrency)]
    # 压测客户端输出含中文, 显式按 utf-8 解码, 否则 Windows 默认 GBK 会抛 UnicodeDecodeError
    r = subprocess.run(client_args, capture_output=True, text=True,
                       encoding="utf-8", errors="replace", timeout=1800)
    wall = time.time() - t0
    load_out = (r.stdout or "") + (r.stderr or "")
    print(load_out)

    # 7. 等待落库完成: 盯 click_count 是否停止增长, 而不是盯队列深度
    print("[7/9] Wait click_count settle (all clicks landed) ...")
    drain_t0 = time.time()
    post_click, drained, drain_s = wait_for_click_quiescence(
        args.short_code, stable_polls=5, interval=1.0,
        max_wait=args.settle_max_wait, label="post")
    print(f"  settled: click_count={post_click} stable={drained} took={drain_s:.1f}s")
    post_depth = queue_depth("short-url.click.queue")
    dlx_depth = queue_depth("short-url.click.dlx.queue")

    # 7b. 正确性闸门: 队列空了不等于消费成功, 必须看 DB 实际落库
    print("[7b/9] Verify correctness ...")
    succ = parse_success_count(load_out)
    click_delta = None
    if post_click is not None and pre_click is not None:
        click_delta = post_click - pre_click
    ratio = None
    if click_delta is not None and succ:
        ratio = click_delta / succ

    log_mb = stdout_p.stat().st_size / 1024 / 1024 if stdout_p.exists() else 0.0
    sql_updates = count_sql_updates(stdout_p)
    post_com_update = read_com_update()
    db_updates = None
    if pre_com_update is not None and post_com_update is not None:
        db_updates = post_com_update - pre_com_update

    problems = []
    notes = []
    # 死信深度: 管理台插件未启用时读不到(-1), 这只是可观测性缺失, 不是失败证据。
    # 真正的失败由 click_delta 捕获 —— 消息进死信必然导致增量不足。
    if dlx_depth is not None and dlx_depth > 0:
        problems.append(f"死信队列残留 {dlx_depth} 条 -> 有消息消费失败被拒收")
    elif dlx_depth is None or dlx_depth < 0:
        notes.append("死信队列深度不可采集(管理台插件未启用), 改由 click_count 增量兜底判定")
    if not drained:
        notes.append(f"click_count 在 {drain_s:.0f}s 内未稳定, 可能仍在消费或有消息卡在死信")
    if click_delta is None:
        problems.append("无法读取 DB click_count (闸门失效, 结果不可采信)")
    elif succ is None:
        problems.append("无法解析成功请求数 (闸门失效, 结果不可采信)")
    elif ratio is not None and ratio < CLICK_DELTA_MIN_RATIO:
        problems.append(
            f"落库不足: click_count 增量 {click_delta} / 成功请求 {succ} = {ratio:.3f}"
            f" (< {CLICK_DELTA_MIN_RATIO})"
        )
    if log_mb > LOG_SIZE_WARN_MB:
        problems.append(f"后端日志 {log_mb:.0f}MB 异常膨胀, 疑似消费端刷异常堆栈, 数据被 I/O 污染")

    verdict = "PASS" if not problems else "FAIL"
    print(f"  dlx_depth={dlx_depth}  click_delta={click_delta}  succ={succ}  "
          f"ratio={'n/a' if ratio is None else f'{ratio:.4f}'}  log={log_mb:.1f}MB  "
          f"db_updates={db_updates}")
    print(f"  >>> VERDICT: {verdict}")
    for p in problems:
        print(f"      [FAIL] {p}")
    for n in notes:
        print(f"      [NOTE] {n}")

    # 8. write result file
    print("[8/9] Write result ...")
    extra = (
        f"\n---- extra ----\n"
        f"LoadTestWallTime_s: {wall:.2f}\n"
        f"QueueDrain_s:      {drain_s:.1f}\n"
        f"QueueDrained:      {drained}\n"
        f"PreClickDepth:     {pre_depth}\n"
        f"PostClickDepth:    {post_depth}\n"
        f"DlxDepth:          {dlx_depth}\n"
        f"PreClickCount:     {pre_click}\n"
        f"PostClickCount:    {post_click}\n"
        f"ClickDelta:        {click_delta}\n"
        f"SuccessRequests:   {succ}\n"
        f"DeltaRatio:        {'n/a' if ratio is None else f'{ratio:.4f}'}\n"
        f"DbUpdateCount:     {db_updates}\n"
        f"UpdatesPerSuccess: {'n/a' if (not succ or db_updates is None) else f'{db_updates / succ:.4f}'}\n"
        f"LogSqlLines:       {sql_updates}\n"
        f"BackendLogMB:      {log_mb:.1f}\n"
        f"VERDICT:           {verdict}\n"
        f"SimulateLatency_ms:{args.simulate_latency_ms}\n"
        f"BatchEnabled:      {args.batch_enabled}\n"
        f"ShortCode:         {args.short_code}\n"
        f"RunId:             {run_id}\n"
        f"Tag:               {tag}\n"
    )
    if problems:
        extra += "---- problems ----\n" + "".join(f"  [FAIL] {p}\n" for p in problems)
    if notes:
        extra += "---- notes ----\n" + "".join(f"  [NOTE] {n}\n" for n in notes)
    with open(result_p, "w", encoding="utf-8") as fp:
        fp.write(load_out + extra)

    # 9. stop backend
    print("[9/9] Stop backend ...")
    try:
        proc.kill()
    except Exception:
        pass
    kill_backend()
    try:
        fo.close(); fe.close()
    except Exception:
        pass
    print(f"Result file: {result_p}")
    if verdict == "FAIL":
        print(f"===== [DONE-FAIL] {tag} ({run_id}) — 该组数据作废, 禁止写进报告 =====\n")
        sys.exit(2)
    print(f"===== [DONE-PASS] {tag} ({run_id}) =====\n")


if __name__ == "__main__":
    main()
