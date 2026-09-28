-- Sprint 0 baseline: one schema per domain module so ownership is explicit from the start.
-- No business tables are created in Sprint 0.
CREATE SCHEMA IF NOT EXISTS platform;
CREATE SCHEMA IF NOT EXISTS identity;
CREATE SCHEMA IF NOT EXISTS tenant;
CREATE SCHEMA IF NOT EXISTS people;
CREATE SCHEMA IF NOT EXISTS operations;
CREATE SCHEMA IF NOT EXISTS payroll;
CREATE SCHEMA IF NOT EXISTS documents;
CREATE SCHEMA IF NOT EXISTS integrations;
CREATE SCHEMA IF NOT EXISTS analytics;
CREATE SCHEMA IF NOT EXISTS finance;

COMMENT ON SCHEMA platform IS 'Cross-cutting platform data (outbox, audit) - owned by platform';
COMMENT ON SCHEMA identity IS 'Private to the identity module';
COMMENT ON SCHEMA tenant IS 'Private to the tenant module';
COMMENT ON SCHEMA people IS 'Private to the people module';
COMMENT ON SCHEMA operations IS 'Private to the operations module';
COMMENT ON SCHEMA payroll IS 'Private to the payroll module';
COMMENT ON SCHEMA documents IS 'Private to the documents module';
COMMENT ON SCHEMA integrations IS 'Private to the integrations module';
COMMENT ON SCHEMA analytics IS 'Private to the analytics module';
COMMENT ON SCHEMA finance IS 'Private to the finance module';
