# -*- coding: utf-8 -*-
"""
阶段4 缓存穿透 / 击穿治理 A/B 对照压测

对照三种模式 (同一份 jar, 靠启动参数切换, 代码路径完全不同所以对照有效):
  none           阶段1~3 原逻辑, 无任何防护 (基线)
  mutex          互斥锁重建
  logical-expiry 逻辑过期 + 异步重建

两组场景:
  穿透组  大量随机不存在的短码 -> 看有多少次打到了 DB
  击穿组  清掉热 key 缓存后高并发同时打 -> 看 DB 被打了多少次、延迟如何

取数方式(双保险):
  1. 应用内计数器 GET /api/short-url/cache-guard/metrics (dbQuery / bloomRejected ...)
  2. MySQL 全局 Com_select 增量 —— 独立于应用, 防止计数器本身写错

用法:
  python tools/run_cache_guard_test.py                       # 跑全部三种模式
  python tools/run_cache_guard_test.py --modes none,mutex    # 只跑指定模式
  python tools/run_cache_guard_test.py --total 1000 --concurrency 50 --smoke

注意: 脚本会启停后端, 并 FLUSHDB 清 Redis, 不要指向生产库。
"""

import argparse
import os
import random
import re
import statistics
import string
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAR = ROOT / "short-url-server" / "target" / "short-url-server-1.0.0.jar"
JAVA = os.environ.get("JAVA_BIN", r"C:\Users\LENOVO\Desktop\jdk-17.0.18+8\bin\java.exe")
REDIS_CLI = os.environ.get("REDIS_CLI", r"D:\Redis-x64-3.2.100\Redis-x64-3.2.100\redis-cli.exe")
MYSQL = os.environ.get("MYSQL_BIN", r"C:\Program Files\MySQL\MySQL Server 5.5\bin\mysql.exe")
DB_USER = os.environ.get("DB_USER", "root")
DB_PASS = os.environ.get("DB_PASS", "root")
CACHE_PREFIX = "short:url:"
PORT = int(os.environ.get("APP_PORT", "8082"))
BASE = f"http://127.0.0.1:{PORT}"
LOG_DIR = ROOT / "short-url-server" / ".workbuddy"

ALL_MODES = ["none", "mutex", "logical-expiry"]


# ==================== 基础工具 ====================

def log(msg=""):
    print(msg, flush=True)


def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True,
                          encoding="utf-8", errors="replace", **kw)


def mysql_query(sql):
    r = run([MYSQL, f"-u{DB_USER}", f"-p{DB_PASS}", "-N", "-e", sql])
    if r.returncode != 0:
        return None
    return (r.stdout or "").strip()


def read_com_select():
    out = mysql_query("SHOW GLOBAL STATUS LIKE 'Com_select';")
    if not out:
        return -1
    parts = out.split()
    return int(parts[-1]) if parts and parts[-1].isdigit() else -1


def set_query_cache(on):
    """
    开/关 MySQL 查询缓存。

    必须关掉的原因: 击穿组是"同一个短码被反复查",
    查询缓存会直接返回重复查询的结果, Com_select 不计数(只记 Qcache_hits)。
    开着它等于给基线组开了一个外挂 —— DB 明明被打了 N 次,
    Com_select 却只涨几次, 会严重低估治理前的真实 DB 压力, 对照就失真了。
    """
    mysql_query(f"SET GLOBAL query_cache_type={'ON' if on else 'OFF'};")
    if not on:
        mysql_query("RESET QUERY CACHE;")


def redis_cmd(*args):
    r = run([REDIS_CLI, *args])
    return (r.stdout or "").strip()


def flush_redis():
    """清空 Redis, 保证每组测试都从冷缓存开始"""
    redis_cmd("FLUSHDB")


def http_json(url, method="GET", timeout=10):
    req = urllib.request.Request(url, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            import json
            return json.loads(r.read().decode("utf-8"))
    except Exception as e:
        return {"__error__": str(e)}


def reset_metrics():
    return http_json(f"{BASE}/api/short-url/cache-guard/metrics/reset", method="POST")


def read_metrics():
    r = http_json(f"{BASE}/api/short-url/cache-guard/metrics")
    return r.get("data") if isinstance(r, dict) else None


def port_ready(port, timeout_s=90):
    import socket
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        with socket.socket() as s:
            s.settimeout(1)
            if s.connect_ex(("127.0.0.1", port)) == 0:
                return True
        time.sleep(0.5)
    return False


def kill_backend(port=PORT):
    """只杀占用指定端口的进程, 避免误杀 IDE 里跑的实例"""
    r = run(["netstat", "-ano"])
    pids = set()
    for line in (r.stdout or "").splitlines():
        if f":{port}" in line and "LISTENING" in line:
            pids.add(line.split()[-1])
    for pid in pids:
        subprocess.run(["taskkill", "/PID", pid, "/F"],
                       capture_output=True, text=True, errors="replace")
    time.sleep(2)


# ==================== 并发压测客户端 ====================

class NoRedirect(urllib.request.HTTPRedirectHandler):
    """不跟随 302, 否则压测会打到目标网站去"""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


_opener = urllib.request.build_opener(NoRedirect)


def _one_request(url):
    t0 = time.perf_counter()
    try:
        try:
            resp = _opener.open(urllib.request.Request(url, method="GET"), timeout=15)
            code = resp.getcode()
        except urllib.error.HTTPError as e:
            code = e.code
    except Exception:
        code = "ERR"
    return code, (time.perf_counter() - t0) * 1000.0


def fire(urls, concurrency):
    """并发请求, 返回 (状态码计数, 延迟ms列表)"""
    with ThreadPoolExecutor(max_workers=concurrency) as ex:
        results = list(ex.map(_one_request, urls))
    codes, lat = {}, []
    for c, ms in results:
        codes[c] = codes.get(c, 0) + 1
        lat.append(ms)
    return codes, lat


def pct(sorted_lat, p):
    if not sorted_lat:
        return 0.0
    k = max(0, min(len(sorted_lat) - 1, int(round((p / 100.0) * (len(sorted_lat) - 1)))))
    return sorted_lat[k]


def summarize_latency(lat):
    s = sorted(lat)
    return {
        "p50": pct(s, 50),
        "p95": pct(s, 95),
        "p99": pct(s, 99),
        "max": s[-1] if s else 0.0,
        "avg": sum(s) / len(s) if s else 0.0,
    }


# ==================== 场景 ====================

def random_codes(n, length=6):
    """生成 n 个格式合法但几乎一定不存在的短码"""
    chars = string.digits + string.ascii_letters
    rnd = random.Random(20260830)  # 固定种子, 保证三种模式用的是同一批码
    return ["".join(rnd.choice(chars) for _ in range(length)) for _ in range(n)]


def scenario_penetration(total, concurrency):
    """穿透组: 大量随机不存在的短码"""
    urls = [f"{BASE}/{c}" for c in random_codes(total)]
    t0 = time.perf_counter()
    codes, lat = fire(urls, concurrency)
    elapsed = time.perf_counter() - t0
    return {
        "codes": codes,
        "lat": summarize_latency(lat),
        "qps": total / elapsed if elapsed > 0 else 0,
        "elapsed": elapsed,
    }


def scenario_breakdown(hot_code, requests, concurrency):
    """击穿组: 清掉热 key 缓存(缓存彻底消失)后高并发同时打同一个码"""
    redis_cmd("DEL", f"{CACHE_PREFIX}{hot_code}")
    return fire_all(hot_code, requests, concurrency)


def scenario_steady_expiry(hot_code, requests, concurrency):
    """
    逻辑过期组: 缓存**仍然存在但已逻辑过期**, 高并发同时打同一个码。

    与击穿组的区别是起始状态:
      击穿组      缓存彻底消失 (对应 mutex 模式下 TTL 到期的瞬间)
      逻辑过期组  缓存还在但内容过期 (对应 logical-expiry 模式下的常态)
    两组不可直接横向比 DB 次数, 要比的是"缓存失效那一刻请求的等待情况"。
    """
    return fire_all(hot_code, requests, concurrency)


def fire_all(hot_code, requests, concurrency):
    urls = [f"{BASE}/{hot_code}"] * requests
    t0 = time.perf_counter()
    codes, lat = fire(urls, concurrency)
    elapsed = time.perf_counter() - t0
    return {
        "codes": codes,
        "lat": summarize_latency(lat),
        "qps": requests / elapsed if elapsed > 0 else 0,
        "elapsed": elapsed,
    }


# ==================== 单模式执行 ====================

def ensure_hot_code():
    """确保有一个存在的短码用于击穿测试, 返回短码"""
    r = http_json_base = None
    import json
    import urllib.request
    req = urllib.request.Request(
        f"{BASE}/api/short-url/create",
        data=json.dumps({"originalUrl": "https://example.com/cache-guard-hot-key"}).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            d = json.loads(resp.read().decode("utf-8"))
            return d["data"]["shortCode"]
    except Exception as e:
        log(f"  [warn] 创建热 key 失败: {e}")
        return None


def run_mode(mode, args):
    log("=" * 74)
    log(f"模式 = {mode}")
    log("=" * 74)

    kill_backend()
    flush_redis()

    LOG_DIR.mkdir(parents=True, exist_ok=True)
    log_path = LOG_DIR / f"cache-guard-{mode}.log"
    java_opts = [
        JAVA, "-Dfile.encoding=UTF-8",
        "-Dmybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl",
        "-jar", str(JAR),
        f"--server.port={PORT}",
        f"--short-url.cache-guard.mode={mode}",
    ]
    if args.logical_expire > 0:
        java_opts.append(f"--short-url.cache-guard.logical-expire-seconds={args.logical_expire}")

    log(f"  启动后端 -> {log_path.name}")
    with open(log_path, "w", encoding="utf-8", errors="replace") as lf:
        proc = subprocess.Popen(java_opts, stdout=lf, stderr=subprocess.STDOUT)

    try:
        if not port_ready(PORT):
            log("  [FAIL] 后端启动超时")
            return None
        time.sleep(3)  # 等布隆预热完成

        hot_code = ensure_hot_code()
        if hot_code is None:
            return None
        log(f"  热 key = {hot_code}")

        # ---------- 穿透组 ----------
        log(f"  [穿透组] {args.total} 个随机非法短码, 并发 {args.concurrency}")
        reset_metrics()
        sel0 = read_com_select()
        pen = scenario_penetration(args.total, args.concurrency)
        m_pen = read_metrics() or {}
        sel1 = read_com_select()

        log(f"    QPS={pen['qps']:.1f}  p50={pen['lat']['p50']:.2f}ms  p99={pen['lat']['p99']:.2f}ms")
        log(f"    dbQuery={m_pen.get('dbQuery')}  bloomRejected={m_pen.get('bloomRejected')}  "
            f"invalidFormat={m_pen.get('invalidFormat')}  nullCacheHit={m_pen.get('nullCacheHit')}")
        log(f"    MySQL Com_select 增量 = {sel1 - sel0}")

        # ---------- 击穿组 ----------
        log(f"  [击穿组] 清缓存后 {args.hot_requests} 并发打热 key, 并发 {args.hot_concurrency}")
        reset_metrics()
        sel2 = read_com_select()
        brk = scenario_breakdown(hot_code, args.hot_requests, args.hot_concurrency)
        m_brk = read_metrics() or {}
        sel3 = read_com_select()

        log(f"    QPS={brk['qps']:.1f}  p50={brk['lat']['p50']:.2f}ms  p99={brk['lat']['p99']:.2f}ms  "
            f"max={brk['lat']['max']:.2f}ms")
        log(f"    dbQuery={m_brk.get('dbQuery')}  cacheHit={m_brk.get('cacheHit')}  "
            f"lockAcquired={m_brk.get('lockAcquired')}  lockTimeout={m_brk.get('lockTimeout')}  "
            f"asyncRebuild={m_brk.get('asyncRebuild')}")
        log(f"    MySQL Com_select 增量 = {sel3 - sel2}")

        ok = sum(v for k, v in brk["codes"].items() if k == 302)
        log(f"    跳转成功 302 = {ok}/{args.hot_requests}")

        out = {
            "mode": mode,
            "pen": pen, "m_pen": m_pen, "pen_select": sel1 - sel0,
            "brk": brk, "m_brk": m_brk, "brk_select": sel3 - sel2,
            "hot_code": hot_code,
        }

        # ---------- 场景3: 逻辑过期稳态 (仅 logical-expiry) ----------
        if mode == "logical-expiry" and args.logical_expire > 0:
            log(f"  [逻辑过期组] 缓存已逻辑过期但仍存在, {args.hot_requests} 并发打热 key")
            reset_metrics()
            time.sleep(args.logical_expire + 1)  # 等逻辑过期时间过去
            sel4 = read_com_select()
            std = scenario_steady_expiry(hot_code, args.hot_requests, args.hot_concurrency)
            m_std = read_metrics() or {}
            sel5 = read_com_select()
            log(f"    QPS={std['qps']:.1f}  p50={std['lat']['p50']:.2f}ms  "
                f"p99={std['lat']['p99']:.2f}ms  max={std['lat']['max']:.2f}ms")
            log(f"    dbQuery={m_std.get('dbQuery')}  logicalExpired={m_std.get('logicalExpired')}  "
                f"asyncRebuild={m_std.get('asyncRebuild')}  lockTimeout={m_std.get('lockTimeout')}")
            log(f"    MySQL Com_select 增量 = {sel5 - sel4}")
            out["std"] = std
            out["m_std"] = m_std
            out["std_select"] = sel5 - sel4

        return out
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=20)
        except subprocess.TimeoutExpired:
            proc.kill()
        kill_backend()


# ==================== 主流程 ====================

def print_table(results):
    log("")
    log("=" * 74)
    log("对照结果")
    log("=" * 74)

    log("")
    log("[穿透组] %d 个随机不存在的短码" % results[0]["pen_total"] if results else "")
    log(f"  {'模式':<16}{'打到DB次数':>12}{'布隆拦截':>10}{'空值命中':>10}{'QPS':>10}{'p99(ms)':>10}")
    for r in results:
        m = r["m_pen"]
        log(f"  {r['mode']:<16}{m.get('dbQuery', 0):>12}{m.get('bloomRejected', 0):>10}"
            f"{m.get('nullCacheHit', 0):>10}{r['pen']['qps']:>10.1f}{r['pen']['lat']['p99']:>10.2f}")

    log("")
    log("[击穿组] 清缓存后高并发打同一个热 key")
    log(f"  {'模式':<16}{'打到DB次数':>12}{'缓存命中':>10}{'抢锁成功':>10}{'QPS':>10}{'p99(ms)':>10}{'max(ms)':>10}")
    for r in results:
        m = r["m_brk"]
        log(f"  {r['mode']:<16}{m.get('dbQuery', 0):>12}{m.get('cacheHit', 0):>10}"
            f"{m.get('lockAcquired', 0):>10}{r['brk']['qps']:>10.1f}"
            f"{r['brk']['lat']['p99']:>10.2f}{r['brk']['lat']['max']:>10.2f}")

    # 逻辑过期模式的"失效瞬间"单独列, 不与击穿组横向比
    std_results = [r for r in results if "std" in r]
    if std_results:
        log("")
        log("[逻辑过期组] 缓存已逻辑过期但仍存在时, 高并发打热 key")
        log(f"  {'模式':<16}{'打到DB次数':>12}{'逻辑过期命中':>14}{'异步重建':>10}{'QPS':>10}{'p99(ms)':>10}{'max(ms)':>10}")
        for r in std_results:
            m = r["m_std"]
            log(f"  {r['mode']:<16}{m.get('dbQuery', 0):>12}{m.get('logicalExpired', 0):>14}"
                f"{m.get('asyncRebuild', 0):>10}{r['std']['qps']:>10.1f}"
                f"{r['std']['lat']['p99']:>10.2f}{r['std']['lat']['max']:>10.2f}")

    base = next((r for r in results if r["mode"] == "none"), None)
    log("")
    if base and base["m_pen"].get("dbQuery", 0) > 0:
        log("相对基线(none)的 DB 查询下降:")
        for r in results:
            if r["mode"] == "none":
                continue
            for key, label in (("m_pen", "穿透组"), ("m_brk", "击穿组")):
                b = base[key].get("dbQuery", 0)
                c = r[key].get("dbQuery", 0)
                if b > 0:
                    log(f"  {r['mode']:<16} {label}: {b} -> {c}  ({(1 - c / b) * 100:.1f}% ↓)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--modes", default=",".join(ALL_MODES))
    ap.add_argument("--total", type=int, default=5000, help="穿透组请求数")
    ap.add_argument("--concurrency", type=int, default=200, help="穿透组并发")
    ap.add_argument("--hot-requests", type=int, default=200, help="击穿组请求数")
    ap.add_argument("--hot-concurrency", type=int, default=200, help="击穿组并发")
    ap.add_argument("--logical-expire", type=int, default=300, help="逻辑过期秒数")
    ap.add_argument("--smoke", action="store_true", help="小样本冒烟")
    args = ap.parse_args()

    if args.smoke:
        args.total, args.concurrency = 500, 50
        args.hot_requests, args.hot_concurrency = 50, 50

    modes = [m.strip() for m in args.modes.split(",") if m.strip()]
    for m in modes:
        if m not in ALL_MODES:
            log(f"未知模式: {m} (可选 {ALL_MODES})")
            return 2

    if not JAR.exists():
        log(f"找不到 jar: {JAR}")
        return 2

    results = []
    log("关闭 MySQL 查询缓存 (保证 Com_select 反映真实 DB 压力)")
    set_query_cache(False)
    try:
        for m in modes:
            r = run_mode(m, args)
            if r:
                r["pen_total"] = args.total
                results.append(r)
            else:
                log(f"  [FAIL] 模式 {m} 未完成")
    finally:
        set_query_cache(True)
        log("已恢复 MySQL 查询缓存为 ON")

    if results:
        print_table(results)

    return 0 if len(results) == len(modes) else 1


if __name__ == "__main__":
    sys.exit(main())
