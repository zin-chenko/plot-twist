SELECT 'CREATE DATABASE auth' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'auth') \gexec
SELECT 'CREATE DATABASE profiles' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'profiles') \gexec
