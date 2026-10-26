\getenv auth_password AUTH_DB_PASSWORD
\getenv domain_password DOMAIN_DB_PASSWORD
SELECT 'CREATE DATABASE auth' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'auth') \gexec
SELECT 'CREATE DATABASE profiles' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'profiles') \gexec
SELECT 'CREATE ROLE app_auth LOGIN' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'app_auth') \gexec
SELECT 'CREATE ROLE app_domain LOGIN' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'app_domain') \gexec
ALTER ROLE app_auth NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD :'auth_password';
ALTER ROLE app_domain NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD :'domain_password';
REVOKE CONNECT ON DATABASE auth, profiles, postgres FROM PUBLIC;
GRANT CONNECT ON DATABASE auth TO app_auth;
GRANT CONNECT ON DATABASE profiles TO app_domain;
\connect auth
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO app_auth;
SELECT format('ALTER TABLE public.%I OWNER TO app_auth', tablename) FROM pg_tables WHERE schemaname = 'public' \gexec
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO app_auth;
\connect profiles
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO app_domain;
SELECT format('ALTER TABLE public.%I OWNER TO app_domain', tablename) FROM pg_tables WHERE schemaname = 'public' \gexec
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO app_domain;
