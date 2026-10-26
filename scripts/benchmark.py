#!/usr/bin/env python3
"""Bounded read benchmark against own profile. Uses the private CLI token file; never prints tokens."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import statistics
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from client import load_token


def run(base, token, rps, seconds):
    if not (1 <= rps <= 40 and 1 <= seconds <= 60):
        raise ValueError('This smoke benchmark is bounded to 1..40 RPS and 1..60 seconds')
    def one(_):
        started = time.perf_counter()
        try:
            with urlopen(Request(base + '/api/v1/profile', headers={'Authorization': 'Bearer ' + token}), timeout=20) as response:
                response.read()
                code = response.status
        except HTTPError as error:
            code = error.code
        except OSError:
            code = 0
        return code, (time.perf_counter() - started) * 1000
    began = time.perf_counter()
    with ThreadPoolExecutor(max_workers=min(40, rps * 2)) as pool:
        futures = []
        for index in range(rps * seconds):
            delay = began + index / rps - time.perf_counter()
            if delay > 0:
                time.sleep(delay)
            futures.append(pool.submit(one, index))
        results = [future.result() for future in futures]
    latencies = sorted(value for _, value in results)
    failures = sum(code != 200 for code, _ in results)
    report = {'requests': len(results), 'requestedRps': rps, 'failures': failures,
              'p50Ms': round(statistics.median(latencies), 2),
              'p95Ms': round(latencies[int((len(latencies) - 1) * .95)], 2),
              'p99Ms': round(latencies[int((len(latencies) - 1) * .99)], 2)}
    print(json.dumps(report))
    if failures:
        raise RuntimeError('Read benchmark had non-200 responses; inspect rate limits, dependencies and token lifetime')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default='http://localhost:8080')
    parser.add_argument('--token-file', type=Path, default=Path('.credentials/session.json'))
    parser.add_argument('--rps', type=int, default=20)
    parser.add_argument('--seconds', type=int, default=15)
    args = parser.parse_args()
    run(args.url.rstrip('/'), load_token(args.token_file), args.rps, args.seconds)
