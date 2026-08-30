#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""高吞吐 QPS 压测工具（asyncio 版，纯标准库，零依赖）。

相比 load_test.py(线程版)，本工具用 asyncio + 原生 socket 异步 I/O，
突破 Python GIL 对线程并发的限制，能把单机后端真正压到饱和，
从而暴露「同步 UPDATE」与「MQ 异步」在请求路径上的真实差异。

默认【不跟随重定向】，只读 302 响应头即返回，正好测跳转接口本身。
使用 HTTP/1.1 keep-alive 复用连接，降低 TCP 握手开销。

用法:
  python load_test_async.py -u http://localhost:8082/abc123 -n 100000 -c 500
参数:
  -u URL          目标地址（跳转接口）
  -n 总数          总请求数
  -c 并发          并发连接数（keep-alive，每条连接串行发请求）
  -t 超时          单请求超时秒（默认 5）
"""
import argparse
import asyncio
import statistics
import time
from urllib.parse import urlparse


async def one_request(reader, writer, path, host, timeout):
    """发送一次 GET，读到响应头结束(\r\n\r\n)即返回，不读 body、不跟随重定向。"""
    t0 = time.perf_counter()
    req = (
        f"GET {path} HTTP/1.1\r\n"
        f"Host: {host}\r\n"
        f"Connection: keep-alive\r\n"
        f"\r\n"
    ).encode()
    writer.write(req)
    await writer.drain()
    data = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), timeout)
    dt = (time.perf_counter() - t0) * 1000.0
    # 状态行: HTTP/1.1 302 Found
    first_line = data.split(b"\r\n", 1)[0]
    parts = first_line.split(b" ")
    code = int(parts[1]) if len(parts) >= 2 else 0
    return dt, code


async def worker(n, host, port, path, timeout):
    latencies = []
    counts = {}
    reader = None
    writer = None
    try:
        for _ in range(n):
            try:
                if writer is None or writer.is_closing():
                    reader, writer = await asyncio.open_connection(host, port)
                dt, code = await one_request(reader, writer, path, host, timeout)
                latencies.append(dt)
                counts[code] = counts.get(code, 0) + 1
            except Exception:
                counts["ERR"] = counts.get("ERR", 0) + 1
                try:
                    if writer is not None:
                        writer.close()
                except Exception:
                    pass
                writer = None
    finally:
        if writer is not None:
            try:
                writer.close()
            except Exception:
                pass
    return latencies, counts


async def amain(url, total, concurrency, timeout):
    p = urlparse(url)
    host = p.hostname
    port = p.port or (443 if p.scheme == "https" else 80)
    path = p.path or "/"
    if p.query:
        path = path + "?" + p.query

    per = total // concurrency
    rem = total % concurrency
    tasks = []
    for i in range(concurrency):
        n = per + (1 if i < rem else 0)
        if n > 0:
            tasks.append(asyncio.create_task(worker(n, host, port, path, timeout)))

    all_lat = []
    all_cnt = {}
    start = time.perf_counter()
    for fut in asyncio.as_completed(tasks):
        lat, cnt = await fut
        all_lat.extend(lat)
        for k, v in cnt.items():
            all_cnt[k] = all_cnt.get(k, 0) + v
    elapsed = time.perf_counter() - start
    return all_lat, all_cnt, elapsed


def pct(latencies, p):
    if not latencies:
        return 0.0
    k = (len(latencies) - 1) * p / 100.0
    f = int(k)
    c = min(f + 1, len(latencies) - 1)
    return latencies[f] + (latencies[c] - latencies[f]) * (k - f)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-u", "--url", required=True)
    ap.add_argument("-n", "--total", type=int, default=100000)
    ap.add_argument("-c", "--concurrency", type=int, default=500)
    ap.add_argument("-t", "--timeout", type=float, default=5.0)
    args = ap.parse_args()

    latencies, counts, elapsed = asyncio.run(
        amain(args.url, args.total, args.concurrency, args.timeout)
    )

    latencies.sort()
    total = len(latencies)
    print("=" * 56)
    print(f"URL            : {args.url}")
    print(f"并发连接       : {args.concurrency}")
    print(f"总请求数       : {total}")
    print(f"耗时           : {elapsed:.2f}s")
    print(f"吞吐(QPS)      : {total / elapsed:.1f}")
    print(f"状态码         : {counts}")
    if total:
        print(f"延迟(ms) avg  : {statistics.mean(latencies):.2f}")
        print(f"延迟(ms) min  : {latencies[0]:.2f}")
        print(f"延迟(ms) max  : {latencies[-1]:.2f}")
        print(f"p50/p95/p99   : {pct(latencies,50):.2f} / {pct(latencies,95):.2f} / {pct(latencies,99):.2f}")
    print("=" * 56)


if __name__ == "__main__":
    main()
