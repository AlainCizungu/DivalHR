#!/bin/bash
# OPS-001: first-start initialisation of the test environment's PostgreSQL (empty data directory
# only). Two databases with separate least-privilege roles: the Core API's and Keycloak's.
# Passwords are read from the read-only secrets mount; they are never echoed or logged.
set -eu
secret() { tr -d '\r\n' < "$SECRETS_DIR/$1"; }
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
  --set app_user="$DIVALHR_DB_USER" --set app_password="$(secret DIVALHR_DB_PASSWORD)" \
  --set app_db="$DIVALHR_DB_NAME" \
  --set kc_user="$KEYCLOAK_DB_USER" --set kc_password="$(secret KEYCLOAK_DB_PASSWORD)" \
  --set kc_db="$KEYCLOAK_DB_NAME" <<'SQL'
CREATE ROLE :"app_user" LOGIN PASSWORD :'app_password' NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE DATABASE :"app_db" OWNER :"app_user";
REVOKE ALL ON DATABASE :"app_db" FROM PUBLIC;
CREATE ROLE :"kc_user" LOGIN PASSWORD :'kc_password' NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE DATABASE :"kc_db" OWNER :"kc_user";
REVOKE ALL ON DATABASE :"kc_db" FROM PUBLIC;
SQL
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$DIVALHR_DB_NAME" \
  -c 'CREATE EXTENSION IF NOT EXISTS btree_gist;'
