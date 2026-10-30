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
-- sources, then moved DRAFT -> VERIFIED in the same transaction, as the pack state machine requires.

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

UPDATE payroll.statutory_rule_pack
   SET status = 'VERIFIED',
       reviewer_name = 'Synthetic Test Reviewer',
       reviewer_organization = 'Test Only',
       reviewed_on = DATE '2026-10-29',
       attested = true,
       verified_at = now()
 WHERE id = :'pack_id';

COMMIT;
