-- ---------------------------------------------------------------------------------------------
-- Manual rollback for V56__partner_accounts_and_catalogue.sql (MVP-120E). NEVER run by Flyway.
--
-- Prefer rolling back only the application. This script restores the V55 schema exactly, but it REFUSES once any
-- partner, product, version, scope or history row exists: partner accounts and the catalogue are immutable commercial
-- records and nothing here deletes them, there is no override. V56 changed no earlier row and no V55 object.
--
-- It does NOT edit flyway_schema_history: removing the V56 row is a separate, controlled operator step, taken only when
-- the application is rolled back too (runbook in docs/OPS-HR-DEV.md).
--
-- Run in one transaction.
BEGIN;
LOCK TABLE integrations.partner, integrations.partner_category, integrations.partner_capability,
    integrations.partner_country, integrations.partner_event, integrations.partner_product,
    integrations.partner_product_version, integrations.partner_product_country,
    integrations.partner_product_currency, integrations.partner_product_event IN ACCESS EXCLUSIVE MODE;
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM integrations.partner)
        OR EXISTS (SELECT 1 FROM integrations.partner_category)
        OR EXISTS (SELECT 1 FROM integrations.partner_capability)
        OR EXISTS (SELECT 1 FROM integrations.partner_country)
        OR EXISTS (SELECT 1 FROM integrations.partner_event)
        OR EXISTS (SELECT 1 FROM integrations.partner_product)
        OR EXISTS (SELECT 1 FROM integrations.partner_product_version)
        OR EXISTS (SELECT 1 FROM integrations.partner_product_country)
        OR EXISTS (SELECT 1 FROM integrations.partner_product_currency)
        OR EXISTS (SELECT 1 FROM integrations.partner_product_event) THEN
        RAISE EXCEPTION 'V56 rollback refused: partner or catalogue rows exist';
    END IF;
END
$$;

DROP TABLE integrations.partner_product_event;
DROP TABLE integrations.partner_product_currency;
DROP TABLE integrations.partner_product_country;
DROP TABLE integrations.partner_product_version;
DROP TABLE integrations.partner_product;
DROP TABLE integrations.partner_event;
DROP TABLE integrations.partner_country;
DROP TABLE integrations.partner_capability;
DROP TABLE integrations.partner_category;
DROP TABLE integrations.partner;
DROP FUNCTION integrations.partner_product_event_guard();
DROP FUNCTION integrations.partner_product_version_guard();
DROP FUNCTION integrations.partner_product_version_insert_guard();
DROP FUNCTION integrations.partner_product_currency_guard();
DROP FUNCTION integrations.partner_product_country_guard();
DROP FUNCTION integrations.partner_product_guard();
DROP FUNCTION integrations.partner_complete();
DROP FUNCTION integrations.partner_event_guard();
DROP FUNCTION integrations.partner_country_guard();
DROP FUNCTION integrations.partner_capability_guard();
DROP FUNCTION integrations.partner_category_guard();
DROP FUNCTION integrations.partner_guard();
DROP FUNCTION integrations.partner_insert_guard();
DROP FUNCTION integrations.partner_account_immutable();
COMMIT;
