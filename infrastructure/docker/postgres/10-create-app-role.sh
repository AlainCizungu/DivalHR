#!/bin/sh
# Creates the least-privileged Core API role and database. Runs once on an empty data volume.
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
  --set app_user="$DIVALHR_DB_USER" --set app_password="$DIVALHR_DB_PASSWORD" --set app_db="$DIVALHR_DB_NAME" <<'SQL'
CREATE ROLE :"app_user" LOGIN PASSWORD :'app_password' NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE DATABASE :"app_db" OWNER :"app_user";
REVOKE ALL ON DATABASE :"app_db" FROM PUBLIC;
SQL
