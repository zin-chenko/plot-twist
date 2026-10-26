-- Also run by postgres-init to upgrade existing volumes without deleting data.
SELECT 'CREATE DATABASE story' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'story') \gexec
SELECT 'CREATE DATABASE character' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'character') \gexec
SELECT 'CREATE DATABASE auth' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'auth') \gexec
