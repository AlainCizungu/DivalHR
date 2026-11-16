-- ---------------------------------------------------------------------------------------------
-- Manual rollback for V51__statutory_pack_governance.sql (MVP-097A, Issue #200). NEVER run by Flyway.
--
-- Prefer rolling back only the application. This script restores the V50 schema exactly, but it REFUSES once any
-- MVP-097A business row exists: a reference case, validation run, review, transition, or a governance row that is
-- anything other than an untouched DRAFT or an untouched LEGACY quarantine row (no evidence, row_version 1). That evidence records who validated,
-- reviewed and activated a statutory pack; none of it is erased by a schema rollback; there is no override, no
-- deletion and no retention decision. V51 seeded nothing and changed no statutory content.
--
-- It does NOT edit flyway_schema_history: removing the V51 row is a separate, controlled operator step, taken only
-- when the application is rolled back too (runbook in docs/OPS-HR-DEV.md).
--
-- Run in one transaction.
-- ---------------------------------------------------------------------------------------------
BEGIN;
LOCK TABLE payroll.statutory_pack_transition, payroll.statutory_pack_review,
    payroll.statutory_pack_validation_run, payroll.statutory_reference_case,
    payroll.statutory_pack_governance, payroll.statutory_rule_pack IN ACCESS EXCLUSIVE MODE;
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM payroll.statutory_pack_transition)
        OR EXISTS (SELECT 1 FROM payroll.statutory_pack_review)
        OR EXISTS (SELECT 1 FROM payroll.statutory_pack_validation_run)
        OR EXISTS (SELECT 1 FROM payroll.statutory_reference_case)
        OR EXISTS (SELECT 1 FROM payroll.statutory_pack_governance
                   WHERE state NOT IN ('DRAFT', 'LEGACY') OR row_version <> 1 OR source_authority IS NOT NULL
                      OR source_title IS NOT NULL OR source_reference IS NOT NULL
                      OR source_bundle_sha256 IS NOT NULL OR supersedes_pack_id IS NOT NULL) THEN
        RAISE EXCEPTION 'V51 rollback refused: statutory pack governance rows exist';
    END IF;
END
$$;

DROP TRIGGER payroll_statutory_rule_pack_governance_birth ON payroll.statutory_rule_pack;
DROP FUNCTION payroll.statutory_pack_evidence_complete(payroll.statutory_pack_governance);
DROP TABLE payroll.statutory_pack_transition;
DROP TABLE payroll.statutory_pack_review;
DROP TABLE payroll.statutory_pack_validation_run;
DROP TABLE payroll.statutory_reference_case;
DROP TABLE payroll.statutory_pack_governance; -- its triggers go with it
DROP FUNCTION payroll.statutory_pack_governance_birth();
DROP FUNCTION payroll.statutory_pack_governance_no_legacy_insert();
DROP FUNCTION payroll.statutory_pack_governance_follow();
DROP FUNCTION payroll.statutory_pack_governance_guard();
DROP FUNCTION payroll.statutory_pack_review_guard();
DROP FUNCTION payroll.statutory_pack_validation_run_guard();
DROP FUNCTION payroll.statutory_reference_case_guard();
DROP FUNCTION payroll.statutory_pack_evidence_immutable();

-- Restores the V45 pack guard exactly.
CREATE OR REPLACE FUNCTION payroll.statutory_rule_pack_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_rule_pack_forward',
                MESSAGE = 'a rule pack is created DRAFT and moves DRAFT -> VERIFIED -> RETIRED only';
        END IF;
        RETURN NEW;
    END IF;
    -- UPDATE: identity, interval, method, rules and creation never change.
    IF NEW.id <> OLD.id OR NEW.code <> OLD.code OR NEW.version <> OLD.version
        OR NEW.country <> OLD.country OR NEW.currency <> OLD.currency
        OR NEW.effective_from <> OLD.effective_from
        OR NEW.effective_to IS DISTINCT FROM OLD.effective_to
        OR NEW.method_version <> OLD.method_version
        OR NEW.rules IS DISTINCT FROM OLD.rules OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_rule_pack_immutable',
            MESSAGE = 'a rule pack''s identity, interval, method and rules never change: correct it as a new version';
    END IF;
    -- The verification evidence is set by DRAFT -> VERIFIED only and never changes afterwards.
    IF OLD.status IN ('VERIFIED', 'RETIRED') AND (
        NEW.reviewer_name IS DISTINCT FROM OLD.reviewer_name
        OR NEW.reviewer_organization IS DISTINCT FROM OLD.reviewer_organization
        OR NEW.reviewed_on IS DISTINCT FROM OLD.reviewed_on
        OR NEW.attested <> OLD.attested
        OR NEW.verified_at IS DISTINCT FROM OLD.verified_at) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            CONSTRAINT = 'payroll_statutory_rule_pack_evidence_immutable',
            MESSAGE = 'the verification evidence of a rule pack never changes once VERIFIED';
    END IF;
    IF NOT ((OLD.status = 'DRAFT' AND NEW.status = 'VERIFIED')
            OR (OLD.status = 'VERIFIED' AND NEW.status = 'RETIRED')) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_rule_pack_forward',
            MESSAGE = 'a rule pack moves DRAFT -> VERIFIED -> RETIRED only';
    END IF;
    RETURN NEW;
END
$fn$;
COMMIT;
