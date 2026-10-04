-- Manual rollback for V16 (MVP-030, Issue #51). NEVER run by Flyway.
--
-- Prefer rolling back only the application. This script restores the V15 schema exactly
-- (signature-tested), but it REFUSES once any MVP-030 data exists: a template, a template
-- version, an issued contract, an acknowledgement or a contract guard row. Issued contracts and
-- acknowledgement evidence must never be erased, so there is no override flag.
--
-- It does NOT edit flyway_schema_history: removing the V16 row is a separate, controlled operator
-- step, taken only when the application is rolled back too (runbook in docs/SECURITY.md).
--
-- Run in one transaction.
BEGIN;

LOCK TABLE documents.contract_template, documents.contract_template_version,
    documents.contract_employment_guard, documents.contract, documents.contract_acknowledgement
    IN ACCESS EXCLUSIVE MODE;

DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM documents.contract_template)
        OR EXISTS (SELECT 1 FROM documents.contract_template_version)
        OR EXISTS (SELECT 1 FROM documents.contract_employment_guard)
        OR EXISTS (SELECT 1 FROM documents.contract)
        OR EXISTS (SELECT 1 FROM documents.contract_acknowledgement) THEN
        RAISE EXCEPTION 'V16 rollback refused: contract data exists';
    END IF;
END
$$;

DROP TABLE documents.contract_acknowledgement;
DROP TABLE documents.contract;
DROP TABLE documents.contract_employment_guard;
DROP TABLE documents.contract_template_version;
DROP TABLE documents.contract_template;
DROP FUNCTION documents.contract_acknowledgement_guard();
DROP FUNCTION documents.contract_guard();
DROP FUNCTION documents.contract_employment_guard_immutable();
DROP FUNCTION documents.contract_template_version_guard();
DROP FUNCTION documents.contract_template_guard();
DROP FUNCTION documents.contract_no_truncate();

COMMIT;
