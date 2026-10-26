#!/usr/bin/env python3
"""Native OAuth2 PKCE client and profile CLI. No frontend or third-party Python packages."""
import argparse
import base64
import getpass
import hashlib
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import os
from pathlib import Path
import secrets
import tempfile
import time
from urllib.error import HTTPError
from urllib.parse import parse_qs, urlencode, urlparse
from urllib.request import Request, urlopen
import webbrowser


def call(base, method, path, body=None, token=None, headers=None):
    headers = dict(headers or {})
    if token:
        headers['Authorization'] = 'Bearer ' + token
    if isinstance(body, dict):
        body = json.dumps(body).encode()
        headers['Content-Type'] = 'application/json'
    try:
        with urlopen(Request(base + path, data=body, headers=headers, method=method), timeout=30) as response:
            return response.read(), response.headers, response.status
    except HTTPError as error:
        try:
            problem = json.load(error)
            message = problem.get('detail', problem.get('title', 'Request rejected'))
        except (ValueError, UnicodeError):
            message = 'Request rejected'
        raise RuntimeError(f'HTTP {error.code}: {message}') from None


def save_token(path, tokens):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    payload = {'access_token': tokens['access_token'], 'expires_at': time.time() + tokens['expires_in']}
    fd, temporary = tempfile.mkstemp(dir=path.parent)
    try:
        with os.fdopen(fd, 'w') as file:
            json.dump(payload, file)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def load_token(path):
    if path.is_symlink():
        raise RuntimeError('Refusing a symlink as a credential file')
    stat = path.stat()
    if os.name == 'posix' and (stat.st_mode & 0o077 or stat.st_uid != os.getuid()):
        raise RuntimeError('Credential file must be owned by you with mode 0600')
    session = json.loads(path.read_text())
    if session['expires_at'] < time.time() + 10:
        raise RuntimeError('Access token expired; run login again')
    return session['access_token']


def login(base, path, callback):
    redirect = urlparse(callback)
    if redirect.scheme != 'http' or redirect.hostname != '127.0.0.1' or redirect.path != '/callback':
        raise RuntimeError('Native callback must be http://127.0.0.1:PORT/callback')
    state = secrets.token_urlsafe(32)
    verifier = secrets.token_urlsafe(48)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip('=')
    result = {}

    class Callback(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass  # Authorization codes must never appear in request logs.

        def do_GET(self):
            query = parse_qs(urlparse(self.path).query)
            valid = (urlparse(self.path).path == '/callback' and len(query.get('state', [])) == 1
                     and secrets.compare_digest(query['state'][0], state))
            code = query.get('code', [])
            if valid and len(code) == 1:
                result['code'] = code[0]
            self.send_response(200 if 'code' in result else 400)
            self.send_header('Content-Type', 'text/plain; charset=utf-8')
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            self.wfile.write(b'Login completed. Return to the terminal.' if 'code' in result else b'Authorization failed.')

    with HTTPServer(('127.0.0.1', redirect.port or 18080), Callback) as server:
        server.timeout = 1
        fields = {'response_type': 'code', 'client_id': 'plot-twist-web', 'redirect_uri': callback,
                  'scope': 'api.read api.write', 'state': state, 'code_challenge': challenge,
                  'code_challenge_method': 'S256'}
        url = base + '/oauth2/authorize?' + urlencode(fields)
        print('Open this URL in your local browser to sign in (expires in 3 minutes):\n' + url)
        webbrowser.open(url)
        deadline = time.monotonic() + 180
        while not result and time.monotonic() < deadline:
            server.handle_request()
    if not result:
        raise RuntimeError('Login timed out')
    data = urlencode({'grant_type': 'authorization_code', 'client_id': 'plot-twist-web',
                      'redirect_uri': callback, 'code': result['code'], 'code_verifier': verifier}).encode()
    body, _, _ = call(base, 'POST', '/oauth2/token', data, headers={'Content-Type': 'application/x-www-form-urlencoded'})
    save_token(path, json.loads(body))
    print('Signed in. Credentials saved privately; no password is stored.')


def password():
    first = getpass.getpass('Password (15 to 128 characters): ')
    if first != getpass.getpass('Repeat password: '):
        raise RuntimeError('Passwords do not match')
    return first


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default=os.getenv('GATEWAY_URL', 'http://localhost:8080'))
    parser.add_argument('--token-file', type=Path, default=Path('.credentials/session.json'))
    sub = parser.add_subparsers(dest='command', required=True)
    for name in ('register', 'resend', 'forgot'):
        sub.add_parser(name).add_argument('email')
    sub.add_parser('verify')
    sub.add_parser('reset')
    p = sub.add_parser('login')
    p.add_argument('--redirect-uri', default=os.getenv('AUTH_REDIRECT_URI', 'http://127.0.0.1:18080/callback'))
    for name in ('logout', 'whoami', 'profile', 'delete-profile', 'photo-delete', 'token'):
        sub.add_parser(name)
    sub.add_parser('profile-put').add_argument('file', type=Path)
    sub.add_parser('photo-put').add_argument('file', type=Path)
    sub.add_parser('photo-get').add_argument('file', type=Path)
    args = parser.parse_args()
    base = args.url.rstrip('/')
    parsed = urlparse(base)
    if parsed.scheme != 'https' and not (parsed.scheme == 'http' and parsed.hostname in ('127.0.0.1', 'localhost')):
        raise RuntimeError('Use HTTPS except for loopback development')
    command = args.command
    if command == 'login':
        login(base, args.token_file, args.redirect_uri)
        return
    if command == 'logout':
        args.token_file.unlink(missing_ok=True)
        print('Local credentials removed. Previously issued JWTs expire within their normal lifetime.')
        return
    if command in ('register', 'resend', 'forgot', 'verify', 'reset'):
        if command == 'register':
            path, body = '/auth/v1/registrations', {'email': args.email, 'password': password()}
        elif command in ('resend', 'forgot'):
            path = '/auth/v1/' + ('email-verifications/requests' if command == 'resend' else 'password-resets/requests')
            body = {'email': args.email}
        else:
            body = {'token': getpass.getpass('One-time code from email: ')}
            if command == 'reset':
                body['password'] = password()
            path = '/auth/v1/' + ('email-verifications' if command == 'verify' else 'password-resets')
        call(base, 'POST', path, body)
        print('Request accepted. Check your email if a message was requested.')
        return
    token = load_token(args.token_file)
    if command == 'token':
        print(token)  # Explicit export command; never used by application logging.
        return
    if command in ('profile', 'whoami'):
        body, _, _ = call(base, 'GET', '/api/v1/profile' if command == 'profile' else '/api/me', token=token)
    elif command == 'photo-get':
        body, _, _ = call(base, 'GET', '/api/v1/profile/photo', token=token)
        fd = os.open(args.file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'wb') as file:
            file.write(body)
        print('Photo saved.')
        return
    else:
        try:
            _, headers, _ = call(base, 'GET', '/api/v1/profile', token=token)
            condition = {'If-Match': headers['ETag']}
        except RuntimeError as error:
            if not str(error).startswith('HTTP 404') or command != 'profile-put':
                raise
            condition = {'If-None-Match': '*'}
        if command == 'profile-put':
            body, _, _ = call(base, 'PUT', '/api/v1/profile', json.loads(args.file.read_text()), token, condition)
        elif command == 'photo-put':
            if args.file.stat().st_size > 5 * 1024 * 1024:
                raise RuntimeError('Maximum photo size is 5 MiB')
            body, _, _ = call(base, 'PUT', '/api/v1/profile/photo', args.file.read_bytes(), token,
                              {**condition, 'Content-Type': 'application/octet-stream'})
        else:
            body, _, _ = call(base, 'DELETE', '/api/v1/profile/photo' if command == 'photo-delete' else '/api/v1/profile', token=token, headers=condition)
    if body:
        print(json.dumps(json.loads(body), ensure_ascii=False, indent=2))
    else:
        print('Done.')


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError, ValueError) as error:
        raise SystemExit(str(error))
