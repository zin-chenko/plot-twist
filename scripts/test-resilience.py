#!/usr/bin/env python3
"""Local integration test: scales/stops services; restores two API replicas, retains database data."""
import json, os, subprocess, time
from urllib.request import urlopen
from urllib.error import HTTPError

def run(*args, **kwargs):
    return subprocess.run(args, check=True, **kwargs)

def output(*args):
    return subprocess.check_output(args, text=True).strip()

def smoke(count):
    run('python3', 'scripts/smoke-test.py', env={**os.environ, 'EXPECTED_API_INSTANCES': str(count)})

base=os.getenv('GATEWAY_URL', 'http://127.0.0.1:' + os.getenv('GATEWAY_PORT', '8080'))
gateway=output('docker','compose','ps','-q','gateway')
try:
    run('docker','compose','up','-d','--no-build','--scale','api-service=3','--wait')
    smoke(3)
    assert gateway == output('docker','compose','ps','-q','gateway'), 'Gateway restarted'
    print('PASS: DNS discovers third replica without gateway restart', flush=True)
    api=output('docker','compose','ps','-q','api-service').splitlines()[0]
    try:
        run('docker','stop',api)
        deadline=time.monotonic()+20
        successful=0
        transient_errors=0
        while successful < 12:
            try:
                with urlopen(base+'/api/story',timeout=15) as response:
                    assert json.load(response)['service']=='api-service'
                successful+=1
            except HTTPError as error:
                assert error.code in (502,504), error.code
                transient_errors+=1
                successful=0
            assert time.monotonic() < deadline, 'Failover did not converge within 20 seconds'
            time.sleep(0.1)
        print(f'PASS: failover converges; 12 consecutive successes; transient errors={transient_errors}', flush=True)
    finally:
        run('docker','start',api)
finally:
    run('docker','compose','up','-d','--no-build','--scale','api-service=2','--wait')
try:
    run('docker','compose','stop','domain-service')
    try:
        urlopen(base+'/api/character',timeout=20)
        raise AssertionError('Expected 503')
    except HTTPError as error:
        assert error.code==503, error.code
    with urlopen(base+'/api/story',timeout=15) as response:
        assert response.status==200
    print('PASS: domain outage returns 503 while API stays available', flush=True)
finally:
    run('docker','compose','up','-d','--no-build','--wait')
smoke(2)
for service in ('api-service','domain-service','postgres'):
    for cid in output('docker','compose','ps','-q',service).splitlines():
        info=json.loads(output('docker','inspect',cid))[0]
        assert not info['HostConfig'].get('PortBindings'), service
print('PASS: no backend/database ports published', flush=True)
