-- SYNTHETIC, TEST-ONLY FIXTURE (MVP-094B, Issue #179, ADR 0034 section 2).
--
-- A VERIFIED statutory rule pack cannot be created through the API: no endpoint exists, by design.
-- The browser journeys of apps/web/e2e/statutory.spec.ts need one, so they create this pack through
-- the same database path as the other e2e helpers (`docker compose exec postgres psql`, support.ts
-- `sqlScript`), as the database owner.
--
-- It is NOT a professional verification and carries no legal meaning: the code is SYNTHETIC-TEST-*,
-- the reviewer is "Synthetic Test Reviewer" / "Test Only", the hashes are fake. It must never be
-- referenced from a migration, seed data, a dev script or a deployment. The repository and the
-- hr-dev environment seed only the DRAFT pack CD-SALARY-PREVIEW.
--
-- Variables: pack_id (a UUID) and pack_code (SYNTHETIC-TEST-...). The pack is created DRAFT with its
-- sources, then walked through the governed lifecycle to ACTIVE in the same transaction.

BEGIN;

INSERT INTO payroll.statutory_rule_pack
    (id, code, version, country, currency, status, effective_from, effective_to, method_version, rules, created_at)
VALUES (:'pack_id', :'pack_code', 1, 'CD', 'CDF', 'DRAFT', DATE '2020-01-01', NULL, '1',
        $rules${
  "schemaVersion": 1,
  "components": [
    {"id": "CNSS_EMPLOYEE_PENSION", "party": "EMPLOYEE", "category": "SOCIAL_CONTRIBUTION",
     "order": 1, "requires": "CNSS_SUBJECT", "base": "GROSS", "sourceKey": "CNSS_RATES",
     "method": {"type": "RATE", "rateBp": 500}, "rounding": "HALF_UP"},
    {"id": "CNSS_EMPLOYER_PENSION", "party": "EMPLOYER", "category": "SOCIAL_CONTRIBUTION",
     "order": 2, "requires": "CNSS_SUBJECT", "base": "GROSS", "sourceKey": "CNSS_RATES",
     "method": {"type": "RATE", "rateBp": 500}, "rounding": "HALF_UP"},
    {"id": "IRPP_SALARY_WITHHOLDING", "party": "EMPLOYEE", "category": "INCOME_TAX",
     "order": 3, "requires": "IRPP_SUBJECT", "base": "GROSS", "sourceKey": "DGI_IRPP",
     "method": {"type": "PROGRESSIVE", "periodsPerYear": 12, "capBp": 3000,
                "brackets": [{"upToAnnual": 1944000, "rateBp": 300},
                             {"upToAnnual": 21600000, "rateBp": 1500},
                             {"upToAnnual": 43200000, "rateBp": 3000},
                             {"upToAnnual": null, "rateBp": 4000}]},
     "rounding": "HALF_UP"}
  ]
}$rules$::jsonb, now());

INSERT INTO payroll.statutory_rule_pack_source (pack_id, seq, source_key, title, url, sha256, retrieved_on)
VALUES
    (:'pack_id', 1, 'DGI_IRPP', 'Synthetic test source: IRPP bands',
     'https://dgi.gouv.cd/impot-sur-le-revenu-des-personnes-physiques-irpp/',
     'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', DATE '2026-10-29'),
    (:'pack_id', 2, 'CNSS_RATES', 'Synthetic test source: CNSS rates',
     'https://cnss.cd/?page_id=1388',
     'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb', DATE '2026-10-29');

-- MVP-097A (Issue #200): the legacy VERIFIED status can no longer be written directly. The pack walks
-- the governed lifecycle DRAFT -> VALIDATED -> REVIEWED -> ACTIVE as the database owner, with synthetic
-- evidence and a synthetic independent reviewer. Any overlapping ACTIVE CD version is retired first
-- (one ACTIVE version per country and period).
DO $walk$
DECLARE
    other uuid;
BEGIN
    FOR other IN
        SELECT o.pack_id FROM payroll.statutory_pack_governance o
          JOIN payroll.statutory_rule_pack op ON op.id = o.pack_id
         WHERE op.country = 'CD' AND o.state IN ('ACTIVE', 'SCHEDULED') AND o.pack_id <> :'pack_id'
    LOOP
        UPDATE payroll.statutory_pack_governance
           SET state = 'RETIRED', retired_at = now(), retired_by = 'synthetic-reviewer',
               retire_reason = 'SUPERSEDED', row_version = row_version + 1,
               transition_actor = 'synthetic-reviewer', transition_at = now()
         WHERE pack_id = other;
    END LOOP;
END
$walk$;

UPDATE payroll.statutory_pack_governance
   SET source_authority = 'Synthetic test authority', source_title = 'Synthetic test source bundle',
       source_published_on = DATE '2026-10-01', source_reference = 'https://example.test/synthetic-source',
       source_bundle_sha256 = repeat('d', 64), evidence_schema_version = 1,
       row_version = row_version + 1, transition_actor = 'synthetic-submitter', transition_at = now()
 WHERE pack_id = :'pack_id';

INSERT INTO payroll.statutory_reference_case (pack_id, seq, case_key, inputs, expected)
VALUES (:'pack_id', 1, 'SYNTHETIC-CASE', '{}'::jsonb, '{}'::jsonb);

INSERT INTO payroll.statutory_pack_validation_run
    (id, pack_id, run_by, run_at, outcome, check_count, failed_count, summary, pack_sha256, fixture_sha256, engine_version)
SELECT gen_random_uuid(), p.id, 'synthetic-submitter', now(), 'PASSED', 1, 0,
       '[{"code":"PACK_PARSES","passed":true}]'::jsonb, p.rules_digest, repeat('c', 64), 'pack-validator-1'
  FROM payroll.statutory_rule_pack p WHERE p.id = :'pack_id';

UPDATE payroll.statutory_pack_governance g
   SET state = 'VALIDATED', pack_sha256 = p.rules_digest, fixture_sha256 = repeat('c', 64),
       validated_at = now(), row_version = g.row_version + 1,
       transition_actor = 'synthetic-submitter', transition_at = now()
  FROM payroll.statutory_rule_pack p WHERE p.id = g.pack_id AND g.pack_id = :'pack_id';

UPDATE payroll.statutory_pack_governance
   SET submitted_by = 'synthetic-submitter', submitted_at = now(), row_version = row_version + 1,
       transition_actor = 'synthetic-submitter', transition_at = now()
 WHERE pack_id = :'pack_id';

INSERT INTO payroll.statutory_pack_review (pack_id, decision, reviewer_subject, decided_at, confirmed, mfa_verified)
VALUES (:'pack_id', 'APPROVED', 'synthetic-reviewer', now(), true, true);

UPDATE payroll.statutory_pack_governance
   SET state = 'REVIEWED', row_version = row_version + 1,
       transition_actor = 'synthetic-reviewer', transition_at = now()
 WHERE pack_id = :'pack_id';

UPDATE payroll.statutory_pack_governance g
   SET state = 'ACTIVE', active_from = p.effective_from, active_to = p.effective_to,
       activated_at = now(), activated_by = 'synthetic-reviewer', row_version = g.row_version + 1,
       transition_actor = 'synthetic-reviewer', transition_at = now()
  FROM payroll.statutory_rule_pack p WHERE p.id = g.pack_id AND g.pack_id = :'pack_id';

COMMIT;
