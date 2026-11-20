#!/usr/bin/env bash
set -Eeuo pipefail

psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set=ON_ERROR_STOP=1 <<'SQL'
\getenv dev_role PAYMESH_DEV_USERNAME
\getenv dev_password PAYMESH_DEV_PASSWORD
\getenv test_role PAYMESH_TEST_USERNAME
\getenv test_password PAYMESH_TEST_PASSWORD

CREATE ROLE :"dev_role" LOGIN PASSWORD :'dev_password';
CREATE DATABASE paymesh_dev OWNER :"dev_role";
REVOKE CONNECT ON DATABASE paymesh_dev FROM PUBLIC;
GRANT CONNECT ON DATABASE paymesh_dev TO :"dev_role";

CREATE ROLE :"test_role" LOGIN PASSWORD :'test_password';
CREATE DATABASE paymesh_test OWNER :"test_role";
REVOKE CONNECT ON DATABASE paymesh_test FROM PUBLIC;
GRANT CONNECT ON DATABASE paymesh_test TO :"test_role";
SQL
