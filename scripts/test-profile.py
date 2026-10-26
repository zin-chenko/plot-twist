#!/usr/bin/env python3
"""Isolated local E2E: real registration/mail/PKCE/PostgreSQL/S3 and account isolation."""
import base64
import concurrent.futures
import json
import os
import re
import secrets
import sys
import struct
import subprocess
import time
import uuid
import zlib
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from auth_support import BASE, user_grant, machine_token

MAIL = os.getenv('MAILPIT_URL', 'http://127.0.0.1:' + os.getenv('MAILPIT_PORT', '8025'))


def request(method, path, body=None, token=None, headers=None, expected=200):
    headers = dict(headers or {})
    if token:
        headers['Authorization'] = 'Bearer ' + token
    if isinstance(body, dict):
        body = json.dumps(body).encode()
        headers['Content-Type'] = 'application/json'
    req = Request(BASE + path, data=body, headers=headers, method=method)
    try:
        response = urlopen(req, timeout=30)
    except HTTPError as error:
        response = error
    with response:
        assert response.status == expected, f'{method} {path}: expected {expected}, got {response.status}'
        data = response.read()
        if data and 'json' in response.headers.get('Content-Type', ''):
            data = json.loads(data)
        return data, response.headers


def mail_code(address, subject):
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        with urlopen(MAIL + '/api/v1/messages', timeout=5) as response:
            messages = json.load(response).get('messages', [])
        for message in messages:
            if subject not in message.get('Subject', ''):
                continue
            if not any(person.get('Address') == address for person in message.get('To', [])):
                continue
            with urlopen(MAIL + '/api/v1/message/' + message['ID'], timeout=5) as response:
                text = json.load(response).get('Text', '')
            match = re.search(r'(?m)^([A-Za-z0-9_-]{43})\r?$', text)
            if match:
                return match.group(1)
        time.sleep(1)
    raise AssertionError('Expected verification/reset email was not delivered')


def png():
    def chunk(kind, data):
        return struct.pack('!I', len(data)) + kind + data + struct.pack('!I', zlib.crc32(kind + data) & 0xffffffff)
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('!IIBBBBB', 2, 2, 8, 2, 0, 0, 0))
            + chunk(b'tEXt', b'Comment\x00PRIVATE_METADATA_MARKER')
            + chunk(b'IDAT', zlib.compress(b'\x00\xff\x00\x00\x00\xff\x00' * 2)) + chunk(b'IEND', b''))


def account():
    address = 'e2e-' + uuid.uuid4().hex + '@example.test'
    password = 'Test-' + secrets.token_urlsafe(24)
    request('POST', '/auth/v1/registrations', {'email': address, 'password': password}, expected=202)
    code = mail_code(address, 'Verify')
    request('POST', '/auth/v1/email-verifications', {'token': code}, expected=204)
    request('POST', '/auth/v1/email-verifications', {'token': code}, expected=400)
    tokens, _ = user_grant('openid api.read api.write', address, password)
    return address, password, tokens['access_token']


def check():
    address, password, token = account()
    _, _, other = account()
    request('GET', '/api/v1/profile', expected=401)
    request('GET', '/api/v1/profile', token=token, expected=404)
    request('GET', '/api/v1/profile', token=machine_token(), expected=403)
    payload = {'displayName': 'Example User', 'givenName': 'Example', 'familyName': 'User',
               'bio': 'Private profile', 'birthDate': '1990-01-02'}
    profile, headers = request('PUT', '/api/v1/profile', payload, token, {'If-None-Match': '*'}, expected=201)
    assert profile['displayName'] == payload['displayName'] and profile['version'] == 0
    etag = headers['ETag']
    request('PUT', '/api/v1/profile', payload, token, expected=428)
    request('PUT', '/api/v1/profile', payload, token, {'If-None-Match': '*'}, expected=412)
    request('PUT', '/api/v1/profile', {**payload, 'accountId': str(uuid.uuid4())}, token, {'If-Match': etag}, expected=400)
    request('GET', '/api/v1/profile', token=other, expected=404)
    request('GET', '/api/v1/profile/photo', token=other, expected=404)
    # Simultaneous updates must not silently lose data.
    def update(i):
        req = Request(BASE + '/api/v1/profile', method='PUT', data=json.dumps({**payload, 'displayName': f'Update {i}'}).encode(),
                      headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json', 'If-Match': etag})
        try:
            with urlopen(req, timeout=20) as response:
                return response.status
        except HTTPError as error:
            return error.code
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(update, range(2)))
    assert sorted(results) == [200, 412], results
    _, headers = request('GET', '/api/v1/profile', token=token)
    etag = headers['ETag']
    request('PUT', '/api/v1/profile/photo', b'<svg onload="alert(1)"/>', token,
            {'If-Match': etag, 'Content-Type': 'application/octet-stream'}, expected=415)
    photo_profile, headers = request('PUT', '/api/v1/profile/photo', png(), token,
                                    {'If-Match': etag, 'Content-Type': 'application/octet-stream'})
    assert photo_profile['photo']['contentType'] == 'image/jpeg'
    image, photo_headers = request('GET', '/api/v1/profile/photo', token=token)
    assert image.startswith(b'\xff\xd8') and b'PRIVATE_METADATA_MARKER' not in image
    assert photo_headers['Cache-Control'] == 'no-store'
    request('GET', '/api/v1/profile/photo', token=token, headers={'If-None-Match': photo_headers['ETag']}, expected=304)
    request('GET', '/api/v1/profile/photo', token=other, expected=404)
    new_profile, headers = request('DELETE', '/api/v1/profile/photo', token=token, headers={'If-Match': headers['ETag']})
    assert new_profile['photo'] is None
    request('GET', '/api/v1/profile/photo', token=token, expected=404)
    request('POST', '/auth/v1/registrations', {'email': address.upper(), 'password': 'Other-Long-Password-123'}, expected=202)
    request('POST', '/auth/v1/password-resets/requests', {'email': address}, expected=202)
    reset = mail_code(address, 'Reset')
    new_password = 'New-' + secrets.token_urlsafe(24)
    request('POST', '/auth/v1/password-resets', {'token': reset, 'password': new_password}, expected=204)
    request('POST', '/auth/v1/password-resets', {'token': reset, 'password': new_password}, expected=400)
    fresh, _ = user_grant('openid api.read api.write', address.upper(), new_password)
    saved, _ = request('GET', '/api/v1/profile', token=fresh['access_token'])
    assert saved['accountId'] == profile['accountId'], 'Password reset changed stable identity'
    if '--benchmark' in sys.argv:
        from benchmark import run
        run(BASE, fresh['access_token'], 20, 15)
    request('DELETE', '/api/v1/profile', token=fresh['access_token'], headers={'If-Match': request('GET', '/api/v1/profile', token=fresh['access_token'])[1]['ETag']}, expected=204)
    request('GET', '/api/v1/profile', token=fresh['access_token'], expected=404)
    for user, forbidden in [('app_auth', 'profiles'), ('app_domain', 'auth')]:
        result = subprocess.run(['docker', 'compose', 'exec', '-T', 'postgres', 'psql', '-U', user, '-d', forbidden, '-c', 'SELECT 1'], capture_output=True)
        assert result.returncode != 0, 'Database isolation failed'
    metrics = subprocess.check_output(['docker', 'compose', 'exec', '-T', 'gateway', 'wget', '-Y', 'off', '-qO-', 'http://auth-service:8080/actuator/prometheus'])
    assert b'auth_email_outbox_pending' in metrics
    assert b'jvm_memory_used_bytes' in metrics
    print('PASS: registration, SMTP verification, PKCE, stable identity, own profile, concurrent ETags, private S3 photo, image sanitization, password reset, DB isolation, metrics')


if __name__ == '__main__':
    check()
