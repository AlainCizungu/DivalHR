-- SYNTHETIC, TEST-ONLY FIXTURE (MVP-097A, Issue #200, ADR 0040).
--
-- A DRAFT statutory pack with reference cases for the governance journeys of
-- apps/web/e2e/statutory-pack-governance.spec.ts. No endpoint creates a pack or its reference cases (the
-- real DRC candidate is the seeded DRAFT CD-SALARY-PREVIEW and is never touched here), so the journeys
-- create this one as the database owner, the way synthetic-verified-rule-pack.sql does.
--
-- The rates, brackets, amounts and dates are SYNTHETIC. They prove the validation, review and
-- scheduling mechanism only: they state no law and carry no legal meaning. The effective interval lies
-- in 2090 so that the pack can never overlap anything real. The code is SYNTHETIC-TEST-*.
--
-- Variables: pack_id (a UUID), pack_code (SYNTHETIC-TEST-...), with_evidence ('true' or 'false': the
-- source evidence of the governance row, which a submission requires).

BEGIN;

INSERT INTO payroll.statutory_rule_pack
    (id, code, version, country, currency, status, effective_from, effective_to, method_version, rules, created_at)
VALUES (:'pack_id', :'pack_code', 1, 'CD', 'CDF', 'DRAFT', DATE '2090-01-01', DATE '2090-12-31', '1',
        $rules${
  "schemaVersion": 1,
  "components": [
    {"id": "SYN_EMPLOYEE_SOCIAL", "party": "EMPLOYEE", "category": "SOCIAL_CONTRIBUTION",
     "order": 1, "requires": "CNSS_SUBJECT", "base": "GROSS", "sourceKey": "CNSS_RATES",
     "method": {"type": "RATE", "rateBp": 500}, "rounding": "HALF_UP"},
    {"id": "SYN_EMPLOYER_SOCIAL", "party": "EMPLOYER", "category": "SOCIAL_CONTRIBUTION",
     "order": 2, "requires": "CNSS_SUBJECT", "base": "GROSS", "sourceKey": "CNSS_RATES",
     "method": {"type": "RATE", "rateBp": 500}, "rounding": "HALF_UP"},
    {"id": "SYN_TAX", "party": "EMPLOYEE", "category": "INCOME_TAX",
     "order": 3, "requires": "IRPP_SUBJECT", "base": "GROSS", "sourceKey": "DGI_IRPP",
     "method": {"type": "PROGRESSIVE", "periodsPerYear": 12, "capBp": 3000,
                "brackets": [{"upToAnnual": 1944000, "rateBp": 300},
                             {"upToAnnual": null, "rateBp": 1500}]},
     "rounding": "HALF_UP"}
  ]
}$rules$::jsonb, now());

INSERT INTO payroll.statutory_rule_pack_source (pack_id, seq, source_key, title, url, sha256, retrieved_on)
VALUES
    (:'pack_id', 1, 'DGI_IRPP', 'Synthetic test source: tax bands',
     'https://dgi.gouv.cd/impot-sur-le-revenu-des-personnes-physiques-irpp/', repeat('a', 64), DATE '2026-10-29'),
    (:'pack_id', 2, 'CNSS_RATES', 'Synthetic test source: social rates',
     'https://cnss.cd/?page_id=1388', repeat('b', 64), DATE '2026-10-29');

INSERT INTO payroll.statutory_reference_case (pack_id, seq, case_key, inputs, expected)
VALUES
    (:'pack_id', 1, 'BOTH',
     '{"cnssSubject":true,"irppSubject":true,"grossMinor":100000}'::jsonb,
     '{"employeeDeductionsMinor":8000,"employerContributionsMinor":5000,"components":{"SYN_EMPLOYEE_SOCIAL":5000,"SYN_EMPLOYER_SOCIAL":5000,"SYN_TAX":3000}}'::jsonb),
    (:'pack_id', 2, 'NEITHER',
     '{"cnssSubject":false,"irppSubject":false,"grossMinor":100000}'::jsonb,
     '{"employeeDeductionsMinor":0,"employerContributionsMinor":0,"components":{}}'::jsonb),
    (:'pack_id', 3, 'SOCIAL_ONLY',
     '{"cnssSubject":true,"irppSubject":false,"grossMinor":200000}'::jsonb,
     '{"employeeDeductionsMinor":10000,"employerContributionsMinor":10000,"components":{"SYN_EMPLOYEE_SOCIAL":10000,"SYN_EMPLOYER_SOCIAL":10000,"SYN_TAX":0}}'::jsonb);

\if :with_evidence
UPDATE payroll.statutory_pack_governance
   SET source_authority = 'Synthetic test authority', source_title = 'Synthetic test source bundle',
       source_published_on = DATE '2026-10-01', source_reference = 'https://example.test/synthetic-source',
       source_bundle_sha256 = repeat('d', 64), evidence_schema_version = 1,
       row_version = row_version + 1, transition_actor = 'synthetic-fixture', transition_at = now()
 WHERE pack_id = :'pack_id';
\endif

COMMIT;
