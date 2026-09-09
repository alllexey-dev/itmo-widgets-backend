#!/bin/sh
set -eu

: "${DB_USER:?Set the application role}"
: "${DB_PASSWORD:?Set the application password}"
if [ "$DB_USER" = "$POSTGRES_USER" ]; then
    echo 'The application role must differ from the PostgreSQL administrator.' >&2
    exit 1
fi

# Bootstrap only the role and its schema permissions. Flyway owns all app tables.
# psql reads passwords from its environment, not shell-expanded SQL or argv.
psql --no-psqlrc --set=ON_ERROR_STOP=1 \
    --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
\getenv app_user DB_USER
\getenv app_password DB_PASSWORD
\getenv app_database POSTGRES_DB
CREATE ROLE :"app_user" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD :'app_password';
REVOKE ALL ON DATABASE :"app_database" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"app_database" TO :"app_user";
REVOKE ALL ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO :"app_user";
SQL
