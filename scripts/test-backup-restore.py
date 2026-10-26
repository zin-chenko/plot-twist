#!/usr/bin/env python3
"""Local DB restore drill: restores into fresh temporary databases; never overwrites source data."""
import subprocess
import tempfile
import time
import uuid


def command(*args, **kwargs):
    return subprocess.run(['docker', 'compose', 'exec', '-T', 'postgres', *args], check=True, **kwargs)


def sql(database, statement):
    result = command('psql', '-U', 'plot_twist', '-d', database, '-At', '-v', 'ON_ERROR_STOP=1', '-c', statement, capture_output=True)
    return result.stdout.decode().strip()


def main():
    started = time.monotonic()
    with tempfile.TemporaryDirectory(prefix='plot-twist-restore-') as directory:
        for source, table in [('auth', 'users'), ('profiles', 'user_profiles')]:
            temporary = 'restore_' + uuid.uuid4().hex
            dump = directory + '/' + source + '.dump'
            expected = sql(source, 'SELECT count(*) FROM ' + table)
            with open(dump, 'wb') as file:
                command('pg_dump', '-U', 'plot_twist', '-Fc', source, stdout=file, stderr=subprocess.PIPE)
            sql('postgres', 'CREATE DATABASE ' + temporary)
            try:
                with open(dump, 'rb') as file:
                    command('pg_restore', '-U', 'plot_twist', '-d', temporary, '--exit-on-error', stdin=file,
                            stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
                actual = sql(temporary, 'SELECT count(*) FROM ' + table)
                assert actual == expected, 'Restored row count differs from source snapshot'
            finally:
                sql('postgres', 'DROP DATABASE ' + temporary + ' WITH (FORCE)')
    print(f'PASS: both logical PostgreSQL backups restored into isolated DBs ({time.monotonic() - started:.1f}s). S3/key disaster recovery is a separate operator drill.')


if __name__ == '__main__':
    main()
