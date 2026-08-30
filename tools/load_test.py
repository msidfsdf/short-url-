#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""零依赖 QPS 压测工具（Windows / 任意平台均可）。
并发 GET 指定 URL，统计吞吐(QPS)与延迟分位。
注意：默认【不跟随重定向】，正好用来测 302 跳转接口本身。

用法:
  python load_test.py -u http://localhost:8081/abc123 -n 20000 -c 200
参数:
  -u URL         目标地址（跳转接口，如 http://localhost:8081/你的短码）
  -n 总数         总请求数
  -c 并发         并发线程数
  -t 超时         单请求超时秒（默认 5）
"""
import argparse
import threading
import time
import urllib.request
import urllib.error
import statistics


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """拦截 301/302，直接返回重定向响应，不去抓取目标站。"""
    def redirect_request(self, *args, **kwargs):
        return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-u", "--url", required=True)
    ap.add_argument("-n", "--total", type=int, default=10000)
    ap.add_argument("-c", "--concurrency", type=int, default=100)
    ap.add_argument("-t", "--timeout", type=float, default=5.0)
    args = ap.parse_args()

    latencies = []
    counts = {}
    lock = threading.Lock()
    sem = threading.Semaphore(args.concurrency)
    opener = urllib.request.build_opener(NoRedirect())

    def worker():
        while True:
            with lock:
                if args.total <= 0:
                    return
                args.total -= 1
            sem.acquire()
            try:
                req = urllib.request.Request(args.url, method="GET")
                t0 = time.perf_counter()
                try:
                    with opener.open(req, timeout=args.timeout) as r:
                        code = r.status
                except urllib.error.HTTPError as e:
                    code = e.code
                dt = (time.perf_counter() - t0) * 1000.0
                with lock:
                    latencies.append(dt)
                    counts[code] = counts.get(code, 0) + 1
            except Exception:
                with lock:
                    counts["ERR"] = counts.get("ERR", 0) + 1
            finally:
                sem.release()

    start = time.perf_counter()
    threads = [threading.Thread(target=worker, daemon=True) for _ in range(args.concurrency)]
    for th in threads:
        th.start()
    for th in threads:
        th.join()
    elapsed = time.perf_counter() - start

    latencies.sort()

    def pct(p):
        if not latencies:
            return 0.0
        k = (len(latencies) - 1) * p / 100.0
        f = int(k)
        c = min(f + 1, len(latencies) - 1)
        return latencies[f] + (latencies[c] - latencies[f]) * (k - f)

    total = len(latencies)
    print("=" * 52)
    print(f"URL            : {args.url}")
    print(f"总请求数       : {total}")
    print(f"耗时           : {elapsed:.2f}s")
    print(f"吞吐(QPS)      : {total / elapsed:.1f}")
    print(f"状态码         : {counts}")
    if total:
        print(f"延迟(ms) avg  : {statistics.mean(latencies):.2f}")
        print(f"延迟(ms) min  : {latencies[0]:.2f}")
        print(f"延迟(ms) max  : {latencies[-1]:.2f}")
        print(f"p50/p95/p99   : {pct(50):.2f} / {pct(95):.2f} / {pct(99):.2f}")
    print("=" * 52)


if __name__ == "__main__":
    main()
