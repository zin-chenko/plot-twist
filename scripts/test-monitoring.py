#!/usr/bin/env python3
"""Check the local provisioned dashboard, real targets and live HTTP histograms."""
import base64
import json
import math
import os
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

PROM = 'http://127.0.0.1:' + os.getenv('PROMETHEUS_PORT', '9090')
GRAFANA = 'http://127.0.0.1:' + os.getenv('GRAFANA_PORT', '3000')
GATEWAY = 'http://127.0.0.1:' + os.getenv('GATEWAY_PORT', '8080')


def read(url, headers=None):
    with urlopen(Request(url, headers=headers or {}), timeout=10) as response:
        return json.load(response)


def query(expression):
    result = read(PROM + '/api/v1/query?' + urlencode({'query': expression}))
    assert result['status'] == 'success', 'Prometheus query failed'
    return result['data']['result']


def check():
    password = os.getenv('GRAFANA_ADMIN_PASSWORD', 'plot-twist-local')
    credentials = base64.b64encode(('admin:' + password).encode()).decode()
    headers = {'Authorization': 'Basic ' + credentials}
    assert read(GRAFANA + '/api/health')['database'] == 'ok'
    assert read(GRAFANA + '/api/datasources/uid/prometheus/health', headers)['status'] == 'OK'
    dashboard = read(GRAFANA + '/api/dashboards/uid/plot-twist-overview', headers)['dashboard']
    assert dashboard['uid'] == 'plot-twist-overview' and len(dashboard['panels']) >= 15

    expected = int(os.getenv('EXPECTED_API_INSTANCES', '2'))
    deadline = time.monotonic() + 75
    while True:
        targets = read(PROM + '/api/v1/targets')['data']['activeTargets']
        counts = {job: sum(target['labels']['job'] == job for target in targets)
                  for job in ('api-service', 'auth-service', 'domain-service')}
        if (counts['api-service'] >= expected and counts['auth-service'] >= 1
                and counts['domain-service'] >= 1 and all(t['health'] == 'up' for t in targets)):
            break
        assert time.monotonic() < deadline, 'Some Java scrape targets are absent or unhealthy'
        time.sleep(2)

    for metric in ('jvm_memory_used_bytes', 'process_cpu_usage', 'hikaricp_connections_active',
                   'auth_email_outbox_pending', 'auth_email_outbox_dead', 'profile_media_cleanup_pending',
                   'http_server_requests_seconds_bucket'):
        assert query(metric), 'Required metric is missing: ' + metric
    replicas = {value['metric'].get('replica') for value in query('jvm_info{job="api-service"}')}
    assert None not in replicas and len(replicas) >= expected, 'Replica tags are missing'

    # Low-rate, unauthenticated probes: no accounts, passwords or user data are touched.
    # Continue across two scrapes so an actual counter increase is visible to rate().
    deadline = time.monotonic() + 65
    while True:
        try:
            with urlopen(GATEWAY + '/api/story', timeout=5) as response:
                raise AssertionError('Protected API unexpectedly accepted an anonymous request')
        except HTTPError as error:
            assert error.code == 401, 'Unexpected response to anonymous monitoring probe'
        rate = query('sum(rate(http_server_requests_seconds_count{job="api-service",uri="/api/story",status="401"}[2m]))')
        latency = query('histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{job="api-service",uri="/api/story"}[2m])))')
        if (rate and latency and float(rate[0]['value'][1]) > 0
                and math.isfinite(float(latency[0]['value'][1]))):
            break
        assert time.monotonic() < deadline, 'HTTP RPS/p95 did not receive real samples'
        time.sleep(2)

    for panel in dashboard['panels']:
        for target in panel.get('targets', []):
            expression = target['expr'].replace('$service', '.*').replace('$instance', '.*').replace('$__rate_interval', '2m')
            query(expression)  # Empty queues/idle error series are valid; PromQL errors are not.
    try:
        with urlopen(GATEWAY + '/actuator/prometheus', timeout=5):
            raise AssertionError('Actuator metrics leaked through the public gateway')
    except HTTPError as error:
        assert error.code == 404
    print('PASS: provisioned Grafana dashboard/data source; all Java replicas UP; JVM/DB/queues; real HTTP RPS and p95; all panel queries valid; public actuator blocked')
    print('The check generated a few intentional 401 API requests; they appear in the HTTP charts.')


if __name__ == '__main__':
    try:
        check()
    except (HTTPError, URLError) as error:
        raise SystemExit('Monitoring endpoint unavailable or credentials rejected: ' + type(error).__name__)
