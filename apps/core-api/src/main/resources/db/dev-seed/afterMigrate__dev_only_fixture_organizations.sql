-- DEVELOPMENT-ONLY FIXTURES. Loaded solely when DIVALHR_ENVIRONMENT=development
-- (DevelopmentSeedFlywayCustomizer); absent in test, staging and production.
-- Creates organizations for the published dev realm tenants A and B so their users can own
-- hierarchy records. Fixture setup only: no business audit or outbox events. Idempotent.
INSERT INTO tenant.organization
    (id, name, country_code, default_locale, timezone, status, created_at, created_by)
VALUES
    ('00000000-0000-4000-8000-00000000000a', 'DEV-ONLY Fixture Tenant A', 'CD', 'fr',
     'Africa/Kinshasa', 'ACTIVE', now(), 'dev-seed'),
    ('00000000-0000-4000-8000-00000000000b', 'DEV-ONLY Fixture Tenant B', 'CD', 'en',
     'Africa/Lubumbashi', 'ACTIVE', now(), 'dev-seed')
ON CONFLICT (id) DO NOTHING;

INSERT INTO tenant.organization_currency (organization_id, currency_code)
VALUES
    ('00000000-0000-4000-8000-00000000000a', 'CDF'),
    ('00000000-0000-4000-8000-00000000000a', 'USD'),
    ('00000000-0000-4000-8000-00000000000b', 'CDF')
ON CONFLICT DO NOTHING;
