#!/bin/sh
# Provisions the database extensions the Core API schema needs (MVP-021, M21-3): btree_gist backs
# the employment-history exclusion constraints. Runs once on an empty data volume, as the
# superuser. V14 also creates it when missing (it is a trusted extension and the application role
# owns the database), so existing volumes need no manual step. The application rollback never
# drops it.
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$DIVALHR_DB_NAME" <<'SQL'
CREATE EXTENSION IF NOT EXISTS btree_gist;
SQL
