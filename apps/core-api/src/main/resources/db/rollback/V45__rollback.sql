-- Manual rollback for V45__payroll_statutory_preview.sql (MVP-094B, Issue #179). NEVER run by Flyway.
--
-- Prefer rolling back only the application. This script restores the V44 schema exactly, but it
-- REFUSES once any MVP-094B evidence exists: statutory activations, employee statutory profiles and
-- statutory calculations (with their lines and components) are the record of how a statutory preview was
-- derived, and the rule pack tables hold the verification evidence (reviewer, organization, attestation,
-- source hashes) of any pack that was ever verified. None of it is erased by a schema rollback; there is
-- no override, no deletion and no retention decision. The ONLY rows this script lets go with the tables
-- are the unverified seed (the DRAFT pack CD-SALARY-PREVIEW version 1 and its six sources, no sha256, no
-- reviewer data) when nothing references it. Nothing outside the V45 tables, triggers and functions is
-- touched; V45 changed no earlier object.
--
-- It does NOT edit flyway_schema_history: removing the V45 row is a separate, controlled operator step,
-- taken only when the application is rolled back too (runbook in docs/OPS-HR-DEV.md).
--
-- Run in one transaction.
BEGIN;
LOCK TABLE payroll.statutory_rule_pack, payroll.statutory_rule_pack_source, payroll.statutory_activation,
    payroll.statutory_profile, payroll.statutory_calculation, payroll.statutory_calculation_line,
    payroll.statutory_calculation_component IN ACCESS EXCLUSIVE MODE;
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM payroll.statutory_activation)
        OR EXISTS (SELECT 1 FROM payroll.statutory_profile)
        OR EXISTS (SELECT 1 FROM payroll.statutory_calculation)
        OR EXISTS (SELECT 1 FROM payroll.statutory_calculation_line)
        OR EXISTS (SELECT 1 FROM payroll.statutory_calculation_component) THEN
        RAISE EXCEPTION 'V45 rollback refused: statutory activation, profile or calculation rows exist';
    END IF;
    -- Only the unverified seed may go: any other pack, any verified pack, any hashed source is evidence.
    IF EXISTS (SELECT 1 FROM payroll.statutory_rule_pack
                WHERE NOT (code = 'CD-SALARY-PREVIEW' AND version = 1 AND status = 'DRAFT'
                           AND reviewer_name IS NULL AND reviewer_organization IS NULL
                           AND reviewed_on IS NULL AND verified_at IS NULL AND NOT attested))
        OR EXISTS (SELECT 1 FROM payroll.statutory_rule_pack_source s
                    WHERE s.sha256 IS NOT NULL
                       OR s.pack_id NOT IN (SELECT id FROM payroll.statutory_rule_pack
                                             WHERE code = 'CD-SALARY-PREVIEW' AND version = 1)) THEN
        RAISE EXCEPTION 'V45 rollback refused: rule pack evidence beyond the unverified seed exists';
    END IF;
END
$$;

DROP TABLE payroll.statutory_calculation_component;
DROP TABLE payroll.statutory_calculation_line;
DROP TABLE payroll.statutory_calculation;
DROP TABLE payroll.statutory_profile;
DROP TABLE payroll.statutory_activation;
DROP TABLE payroll.statutory_rule_pack_source;
DROP TABLE payroll.statutory_rule_pack;
DROP FUNCTION payroll.statutory_calculation_line_exact();
DROP FUNCTION payroll.statutory_calculation_exact();
DROP FUNCTION payroll.statutory_calculation_component_guard();
DROP FUNCTION payroll.statutory_calculation_line_guard();
DROP FUNCTION payroll.statutory_calculation_guard();
DROP FUNCTION payroll.statutory_profile_guard();
DROP FUNCTION payroll.statutory_activation_guard();
DROP FUNCTION payroll.statutory_rule_pack_exact();
DROP FUNCTION payroll.statutory_rule_pack_source_guard();
DROP FUNCTION payroll.statutory_rule_pack_guard();
DROP FUNCTION payroll.statutory_rules_digest(jsonb);
DROP FUNCTION payroll.statutory_immutable();
COMMIT;
