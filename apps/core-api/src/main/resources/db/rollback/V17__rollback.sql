-- Manual rollback for V17__contract_coverage_index.sql (MVP-031A, Issue #73). NEVER run by Flyway.
--
-- Prefer rolling back only the application: the index is harmless to earlier versions. This
-- script drops the coverage index and restores the V16 schema exactly; no data is touched. Run
-- in one transaction, then remove the V17 row from flyway_schema_history only if the application
-- is also rolled back.
BEGIN;

DROP INDEX documents.contract_coverage_order;

COMMIT;
