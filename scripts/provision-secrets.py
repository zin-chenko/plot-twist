#!/usr/bin/env python3
"""Create a new local production secret directory; never print or overwrite secret values."""
import getpass
import os
from pathlib import Path
import secrets
import shutil
import subprocess


def main():
    if not shutil.which('openssl'):
        raise SystemExit('Install OpenSSL first')
    directory = Path('secrets')
    if directory.exists():
        raise SystemExit('Refusing to overwrite an existing secrets directory')
    smtp = getpass.getpass('Production SMTP password: ')
    if not smtp:
        raise SystemExit('SMTP password is required for the supplied production configuration')
    iam = input('Use an AWS workload/instance IAM role instead of static S3 keys? [y/N] ').strip().lower() == 'y'
    access = '' if iam else getpass.getpass('S3 access key ID: ')
    secret = '' if iam else getpass.getpass('S3 secret access key: ')
    if not iam and (not access or not secret):
        raise SystemExit('Both S3 credentials are required')
    directory.mkdir(mode=0o700)
    values = {'pg-admin-password': secrets.token_urlsafe(32), 'auth-db-password': secrets.token_urlsafe(32),
              'domain-db-password': secrets.token_urlsafe(32), 'smtp-password': smtp,
              's3-access-key': access, 's3-secret-key': secret,
              'grafana-admin-password': secrets.token_urlsafe(32), 'qdrant-api-key': secrets.token_urlsafe(32), 'rabbitmq-password': secrets.token_urlsafe(32)}
    for name, value in values.items():
        fd = os.open(directory / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as file:
            file.write(value)
    fd = os.open(directory / 'signing.pk8', os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'wb') as file:
        generator = subprocess.Popen(['openssl', 'genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:3072'],
                                     stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        try:
            subprocess.run(['openssl', 'pkcs8', '-topk8', '-nocrypt', '-outform', 'DER'],
                           stdin=generator.stdout, stdout=file, stderr=subprocess.DEVNULL, check=True)
        finally:
            generator.stdout.close()
        if generator.wait() != 0:
            raise SystemExit('Key generation failed; retain and inspect the new directory securely')
    print('Secrets created privately. No values were printed.')
    print('On the production Linux host, set file ownership/permissions as described in docs/production.md.')


if __name__ == '__main__':
    main()
