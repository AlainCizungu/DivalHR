-- =============================================================================================
-- V56: controlled partner accounts and bilingual product catalogue (MVP-120E, ADR 0044)
--
-- An approved application is NOT an active partner. A partner is created only from an application whose status is
-- exactly APPROVED_FOR_ONBOARDING (one-to-one), starts as DRAFT and is activated by an explicit platform-administrator
-- action. Products are versioned: a version's content is frozen as soon as it leaves DRAFT; editing a live product
-- creates a new draft version. Nothing here stores a credential, an endpoint, an employee attribute or an amount of
-- money that moved: the catalogue is information only.
--
-- Evidence (partners, products, versions, events, scope) is never deleted, truncated or rewritten; the guards below
-- are database triggers so no application bug or direct statement can bypass them. No GRANT or REVOKE is issued.
-- Rollback: db/rollback/V56__rollback.sql, which refuses once any partner row exists.
-- =============================================================================================

CREATE FUNCTION integrations.partner_account_immutable() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_account_immutable',
        MESSAGE = 'partner-account evidence is never deleted, truncated or updated';
END
$fn$;

-- ---------------------------------------------------------------------------------------------
-- Partner (status and row_version, with updated_at, are the only mutable columns)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE integrations.partner (
    id                      uuid        NOT NULL,
    reference               text        NOT NULL,
    application_id          uuid        NOT NULL,
    status                  text        NOT NULL,
    row_version             integer     NOT NULL,
    primary_category        text        NOT NULL,
    legal_name              text        NOT NULL,
    trading_name            text,
    country_of_registration char(2)     NOT NULL,
    website                 text        NOT NULL,
    supported_languages     text[]      NOT NULL,
    created_at              timestamptz NOT NULL,
    updated_at              timestamptz NOT NULL,
    CONSTRAINT integrations_partner_pkey PRIMARY KEY (id),
    CONSTRAINT integrations_partner_reference_key UNIQUE (reference),
    CONSTRAINT integrations_partner_application_key UNIQUE (application_id),
    CONSTRAINT integrations_partner_application FOREIGN KEY (application_id)
        REFERENCES integrations.partner_application (id),
    CONSTRAINT integrations_partner_reference CHECK (reference ~ '^PT-[0-9A-HJKMNP-TV-Z]{16}$'),
    CONSTRAINT integrations_partner_status CHECK (status IN ('DRAFT', 'ACTIVE', 'SUSPENDED', 'CLOSED')),
    CONSTRAINT integrations_partner_version CHECK (row_version BETWEEN 0 AND 100000),
    CONSTRAINT integrations_partner_category CHECK (primary_category IN
        ('BANK', 'MICROFINANCE', 'INSURANCE', 'HEALTH_BENEFITS', 'TELECOM', 'MOBILE_MONEY_PAYMENT', 'PENSION_SAVINGS',
         'OTHER_REGULATED_PROVIDER')),
    CONSTRAINT integrations_partner_legal_name CHECK (char_length(legal_name) BETWEEN 2 AND 200),
    CONSTRAINT integrations_partner_trading_name CHECK (
        trading_name IS NULL OR char_length(trading_name) BETWEEN 2 AND 200),
    CONSTRAINT integrations_partner_country CHECK (country_of_registration ~ '^[A-Z]{2}$'),
    CONSTRAINT integrations_partner_website CHECK (
        char_length(website) BETWEEN 10 AND 300 AND website ~ '^https://[^[:space:]]+$'),
    CONSTRAINT integrations_partner_languages CHECK (
        cardinality(supported_languages) BETWEEN 1 AND 2 AND supported_languages <@ ARRAY['en', 'fr']::text[]),
    CONSTRAINT integrations_partner_times CHECK (updated_at >= created_at)
);

COMMENT ON TABLE integrations.partner IS
    'MVP-120E controlled partner account. Holds only the approved business identity copied from the source application '
    '(no contact, email, phone, licence or registration reference, privacy contact, checklist note or encrypted '
    'payload). application_id is a private platform-audit link, never exposed to tenants or employees. A partner is not '
    'connected to any DivalHR operational system and holds no credential.';

CREATE INDEX integrations_partner_status_idx ON integrations.partner (status, created_at DESC, id DESC);
CREATE INDEX integrations_partner_category_idx ON integrations.partner (primary_category, created_at DESC, id DESC);

CREATE FUNCTION integrations.partner_insert_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    app integrations.partner_application%ROWTYPE;
BEGIN
    SELECT * INTO app FROM integrations.partner_application a WHERE a.id = NEW.application_id FOR SHARE;
    IF NOT FOUND OR app.status <> 'APPROVED_FOR_ONBOARDING' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_source_approved',
            MESSAGE = 'a partner is created only from an application approved for onboarding';
    END IF;
    IF NEW.status <> 'DRAFT' OR NEW.row_version <> 0 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_initial',
            MESSAGE = 'a partner is created as a draft at version 0';
    END IF;
    IF (NEW.primary_category, NEW.legal_name, NEW.trading_name, NEW.country_of_registration, NEW.website,
        NEW.supported_languages)
        IS DISTINCT FROM
       (app.primary_category, app.legal_name, app.trading_name, app.country_of_registration, app.website,
        app.supported_languages) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_copy',
            MESSAGE = 'a partner copies exactly the approved business identity of its application';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER integrations_partner_insert_guard BEFORE INSERT ON integrations.partner
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_insert_guard();

CREATE FUNCTION integrations.partner_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    blocking integer;
BEGIN
    IF OLD.status = 'CLOSED' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_terminal',
            MESSAGE = 'a closed partner is never changed';
    END IF;
    IF (NEW.id, NEW.reference, NEW.application_id, NEW.primary_category, NEW.legal_name, NEW.trading_name,
        NEW.country_of_registration, NEW.website, NEW.supported_languages, NEW.created_at)
        IS DISTINCT FROM
       (OLD.id, OLD.reference, OLD.application_id, OLD.primary_category, OLD.legal_name, OLD.trading_name,
        OLD.country_of_registration, OLD.website, OLD.supported_languages, OLD.created_at) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_frozen',
            MESSAGE = 'the approved identity of a partner is frozen';
    END IF;
    IF NEW.row_version <> OLD.row_version + 1 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_version_step',
            MESSAGE = 'a partner change advances the version by exactly one';
    END IF;
    IF NOT ((OLD.status = 'DRAFT' AND NEW.status IN ('ACTIVE', 'CLOSED'))
         OR (OLD.status = 'ACTIVE' AND NEW.status IN ('SUSPENDED', 'CLOSED'))
         OR (OLD.status = 'SUSPENDED' AND NEW.status IN ('ACTIVE', 'CLOSED'))
         OR (OLD.status = NEW.status)) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_transition',
            MESSAGE = 'this partner status change is not allowed';
    END IF;
    IF NEW.status = 'CLOSED' THEN
        SELECT count(*) INTO blocking
          FROM integrations.partner_product_version v
          JOIN integrations.partner_product p ON p.id = v.product_id
         WHERE p.partner_id = OLD.id AND v.status = 'ACTIVE';
        IF blocking > 0 THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_active_products',
                MESSAGE = 'a partner with an active product cannot be closed';
        END IF;
    END IF;
    RETURN NEW;
END
$fn$;

-- The product tables are created below; the function body only runs at update time, after all of V56 exists.
CREATE TABLE integrations.partner_category (
    partner_id uuid NOT NULL,
    position   integer NOT NULL,
    category   text NOT NULL,
    CONSTRAINT integrations_partner_category_pkey PRIMARY KEY (partner_id, position),
    CONSTRAINT integrations_partner_category_unique UNIQUE (partner_id, category),
    CONSTRAINT integrations_partner_category_partner FOREIGN KEY (partner_id) REFERENCES integrations.partner (id),
    CONSTRAINT integrations_partner_category_position CHECK (position BETWEEN 1 AND 8),
    CONSTRAINT integrations_partner_category_code CHECK (category IN
        ('BANK', 'MICROFINANCE', 'INSURANCE', 'HEALTH_BENEFITS', 'TELECOM', 'MOBILE_MONEY_PAYMENT', 'PENSION_SAVINGS',
         'OTHER_REGULATED_PROVIDER'))
);

CREATE TABLE integrations.partner_capability (
    partner_id uuid NOT NULL,
    capability text NOT NULL,
    CONSTRAINT integrations_partner_capability_pkey PRIMARY KEY (partner_id, capability),
    CONSTRAINT integrations_partner_capability_partner FOREIGN KEY (partner_id) REFERENCES integrations.partner (id),
    CONSTRAINT integrations_partner_capability_code CHECK (capability IN
        ('SALARY_ADVANCE', 'EMPLOYEE_LOANS', 'HEALTH_INSURANCE', 'LIFE_INSURANCE', 'ACCIDENT_INSURANCE',
         'HEALTHCARE_BENEFITS', 'AIRTIME_DATA_BENEFITS', 'SMS_USSD_SERVICES', 'MOBILE_MONEY_DISBURSEMENT',
         'PAYMENT_COLLECTION', 'PENSION_SAVINGS', 'OTHER_EMPLOYEE_SERVICE'))
);

-- Approved operating scope. APPLICATION rows are copied from the application at creation; AMENDMENT rows are added
-- only by a separately recorded SCOPE_AMENDED event (D120E-7). Scope is only ever extended, never removed.
CREATE TABLE integrations.partner_country (
    partner_id   uuid        NOT NULL,
    country_code char(2)     NOT NULL,
    origin       text        NOT NULL,
    added_at     timestamptz NOT NULL,
    CONSTRAINT integrations_partner_country_pkey PRIMARY KEY (partner_id, country_code),
    CONSTRAINT integrations_partner_country_partner FOREIGN KEY (partner_id) REFERENCES integrations.partner (id),
    CONSTRAINT integrations_partner_country_code CHECK (country_code ~ '^[A-Z]{2}$'),
    CONSTRAINT integrations_partner_country_origin CHECK (origin IN ('APPLICATION', 'AMENDMENT'))
);

CREATE FUNCTION integrations.partner_category_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    app_id uuid;
BEGIN
    SELECT p.application_id INTO app_id FROM integrations.partner p WHERE p.id = NEW.partner_id;
    IF EXISTS (SELECT 1 FROM integrations.partner_event e WHERE e.partner_id = NEW.partner_id) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_sealed',
            MESSAGE = 'the approved scope of a partner is fixed once it is created';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM integrations.partner_application_category c
                    WHERE c.application_id = app_id AND c.category = NEW.category) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_category_source',
            MESSAGE = 'a partner category must be an approved category of its application';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE FUNCTION integrations.partner_capability_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    app_id uuid;
BEGIN
    SELECT p.application_id INTO app_id FROM integrations.partner p WHERE p.id = NEW.partner_id;
    IF EXISTS (SELECT 1 FROM integrations.partner_event e WHERE e.partner_id = NEW.partner_id) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_sealed',
            MESSAGE = 'the approved scope of a partner is fixed once it is created';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM integrations.partner_application_capability c
                    WHERE c.application_id = app_id AND c.capability = NEW.capability) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_capability_source',
            MESSAGE = 'a partner capability must be an approved capability of its application';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE FUNCTION integrations.partner_country_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    app_id uuid;
    current_status text;
    sealed boolean;
BEGIN
    SELECT p.application_id, p.status INTO app_id, current_status
      FROM integrations.partner p WHERE p.id = NEW.partner_id;
    SELECT EXISTS (SELECT 1 FROM integrations.partner_event e WHERE e.partner_id = NEW.partner_id) INTO sealed;
    IF NEW.origin = 'AMENDMENT' THEN
        IF current_status = 'CLOSED' OR NOT sealed THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_scope_amendment',
                MESSAGE = 'a scope amendment applies to an existing partner that is not closed';
        END IF;
        RETURN NEW;
    END IF;
    IF sealed THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_sealed',
            MESSAGE = 'the approved scope of a partner is fixed once it is created';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM integrations.partner_application a
                    WHERE a.id = app_id AND NEW.country_code = ANY (a.countries_of_operation)) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_country_source',
            MESSAGE = 'a partner country must be an approved country of its application';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER integrations_partner_category_guard BEFORE INSERT ON integrations.partner_category
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_category_guard();
CREATE TRIGGER integrations_partner_capability_guard BEFORE INSERT ON integrations.partner_capability
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_capability_guard();
CREATE TRIGGER integrations_partner_country_guard BEFORE INSERT ON integrations.partner_country
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_country_guard();

CREATE TRIGGER integrations_partner_category_no_update BEFORE UPDATE ON integrations.partner_category
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_account_immutable();
CREATE TRIGGER integrations_partner_category_no_delete BEFORE DELETE ON integrations.partner_category
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_account_immutable();
CREATE TRIGGER integrations_partner_category_no_truncate BEFORE TRUNCATE ON integrations.partner_category
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.partner_account_immutable();
CREATE TRIGGER integrations_partner_capability_no_update BEFORE UPDATE ON integrations.partner_capability
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_account_immutable();
CREATE TRIGGER integrations_partner_capability_no_delete BEFORE DELETE ON integrations.partner_capability
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_account_immutable();
CREATE TRIGGER integrations_partner_capability_no_truncate BEFORE TRUNCATE ON integrations.partner_capability
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.partner_account_immutable();
CREATE TRIGGER integrations_partner_country_no_update BEFORE UPDATE ON integrations.partner_country
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_account_immutable();
CREATE TRIGGER integrations_partner_country_no_delete BEFORE DELETE ON integrations.partner_country
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_account_immutable();
CREATE TRIGGER integrations_partner_country_no_truncate BEFORE TRUNCATE ON integrations.partner_country
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.partner_account_immutable();

CREATE INDEX integrations_partner_country_lookup ON integrations.partner_country (country_code, partner_id);
CREATE INDEX integrations_partner_capability_lookup ON integrations.partner_capability (capability, partner_id);

-- ---------------------------------------------------------------------------------------------
-- Partner history (append-only)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE integrations.partner_event (
    partner_id      uuid        NOT NULL,
    sequence        integer     NOT NULL,
    event_type      text        NOT NULL,
    from_status     text,
    to_status       text        NOT NULL,
    reason_code     text,
    note_ciphertext bytea,
    item_count      integer,
    actor_subject   text        NOT NULL,
    occurred_at     timestamptz NOT NULL,
    CONSTRAINT integrations_partner_event_pkey PRIMARY KEY (partner_id, sequence),
    CONSTRAINT integrations_partner_event_partner FOREIGN KEY (partner_id) REFERENCES integrations.partner (id),
    CONSTRAINT integrations_partner_event_sequence CHECK (sequence BETWEEN 1 AND 100000),
    CONSTRAINT integrations_partner_event_shape CHECK (
        (event_type = 'CREATED' AND sequence = 1 AND from_status IS NULL AND to_status = 'DRAFT'
            AND reason_code IS NULL AND item_count IS NULL)
        OR (event_type = 'ACTIVATED' AND from_status = 'DRAFT' AND to_status = 'ACTIVE'
            AND reason_code IS NULL AND item_count IS NULL)
        OR (event_type = 'SUSPENDED' AND from_status = 'ACTIVE' AND to_status = 'SUSPENDED'
            AND reason_code IS NOT NULL AND item_count IS NULL)
        OR (event_type = 'REACTIVATED' AND from_status = 'SUSPENDED' AND to_status = 'ACTIVE'
            AND reason_code IS NULL AND item_count IS NULL)
        OR (event_type = 'CLOSED' AND from_status IN ('DRAFT', 'ACTIVE', 'SUSPENDED') AND to_status = 'CLOSED'
            AND item_count IS NULL)
        OR (event_type = 'SCOPE_AMENDED' AND from_status = to_status AND to_status <> 'CLOSED'
            AND reason_code IS NULL AND item_count BETWEEN 1 AND 30)),
    CONSTRAINT integrations_partner_event_reason CHECK (
        reason_code IS NULL OR reason_code IN
            ('COMPLIANCE_REVIEW', 'COMMERCIAL_HOLD', 'PARTNER_REQUEST', 'REGULATORY_RESTRICTION', 'SECURITY_CONCERN',
             'PROGRAM_ENDED', 'OTHER')),
    CONSTRAINT integrations_partner_event_note CHECK (
        (reason_code = 'OTHER') = (note_ciphertext IS NOT NULL)
        AND (note_ciphertext IS NULL OR octet_length(note_ciphertext) BETWEEN 40 AND 4096)),
    CONSTRAINT integrations_partner_event_actor CHECK (char_length(actor_subject) BETWEEN 1 AND 255)
);

CREATE FUNCTION integrations.partner_event_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    latest integer;
    current_status text;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.partner_id::text, 122));
    SELECT max(e.sequence) INTO latest FROM integrations.partner_event e WHERE e.partner_id = NEW.partner_id;
    IF NEW.sequence <> coalesce(latest, 0) + 1 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_event_sequence',
            MESSAGE = 'a history event takes the next contiguous sequence';
    END IF;
    SELECT p.status INTO current_status FROM integrations.partner p WHERE p.id = NEW.partner_id;
    IF NEW.to_status IS DISTINCT FROM current_status THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_event_status',
            MESSAGE = 'a history event records the status the partner actually has';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER integrations_partner_event_guard BEFORE INSERT ON integrations.partner_event
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_event_guard();
CREATE TRIGGER integrations_partner_event_no_update BEFORE UPDATE ON integrations.partner_event
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_account_immutable();
CREATE TRIGGER integrations_partner_event_no_delete BEFORE DELETE ON integrations.partner_event
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_account_immutable();
CREATE TRIGGER integrations_partner_event_no_truncate BEFORE TRUNCATE ON integrations.partner_event
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.partner_account_immutable();

-- A partner is complete only with its CREATED event and at least one approved country (checked at commit).
CREATE FUNCTION integrations.partner_complete() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM integrations.partner_event e WHERE e.partner_id = NEW.id AND e.sequence = 1)
        OR NOT EXISTS (SELECT 1 FROM integrations.partner_country c WHERE c.partner_id = NEW.id) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_complete',
            MESSAGE = 'a partner is created together with its history and approved scope';
    END IF;
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER integrations_partner_complete AFTER INSERT ON integrations.partner
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION integrations.partner_complete();

CREATE TRIGGER integrations_partner_guard BEFORE UPDATE ON integrations.partner
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_guard();
CREATE TRIGGER integrations_partner_no_delete BEFORE DELETE ON integrations.partner
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_account_immutable();
CREATE TRIGGER integrations_partner_no_truncate BEFORE TRUNCATE ON integrations.partner
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.partner_account_immutable();

-- ---------------------------------------------------------------------------------------------
-- Product (stable identity) and its immutable-once-submitted versions
-- ---------------------------------------------------------------------------------------------

CREATE TABLE integrations.partner_product (
    id               uuid        NOT NULL,
    partner_id       uuid        NOT NULL,
    product_code     text        NOT NULL,
    public_reference text        NOT NULL,
    product_type     text        NOT NULL,
    created_at       timestamptz NOT NULL,
    CONSTRAINT integrations_partner_product_pkey PRIMARY KEY (id),
    CONSTRAINT integrations_partner_product_code_key UNIQUE (partner_id, product_code),
    CONSTRAINT integrations_partner_product_public_key UNIQUE (public_reference),
    CONSTRAINT integrations_partner_product_partner FOREIGN KEY (partner_id) REFERENCES integrations.partner (id),
    CONSTRAINT integrations_partner_product_code CHECK (product_code ~ '^[A-Z0-9][A-Z0-9_-]{1,39}$'),
    CONSTRAINT integrations_partner_product_public CHECK (public_reference ~ '^PP-[0-9A-HJKMNP-TV-Z]{16}$'),
    CONSTRAINT integrations_partner_product_type CHECK (product_type IN
        ('SALARY_ADVANCE', 'EMPLOYEE_LOAN', 'HEALTH_INSURANCE', 'LIFE_INSURANCE', 'OTHER_INSURANCE',
         'TELECOM_VOICE_DATA', 'DEVICE_FINANCING', 'HEALTHCARE', 'WELLNESS', 'TRAINING', 'OTHER_BENEFIT'))
);

COMMENT ON TABLE integrations.partner_product IS
    'MVP-120E product identity. product_code is internal to the platform; public_reference is the only identifier a '
    'tenant or employee ever sees. The content lives in partner_product_version.';

CREATE FUNCTION integrations.partner_product_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    current_status text;
BEGIN
    SELECT p.status INTO current_status FROM integrations.partner p WHERE p.id = NEW.partner_id FOR SHARE;
    IF current_status IS NULL OR current_status = 'CLOSED' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_product_partner_open',
            MESSAGE = 'a product belongs to a partner that is not closed';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER integrations_partner_product_guard BEFORE INSERT ON integrations.partner_product
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_product_guard();
CREATE TRIGGER integrations_partner_product_no_update BEFORE UPDATE ON integrations.partner_product
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_account_immutable();
CREATE TRIGGER integrations_partner_product_no_delete BEFORE DELETE ON integrations.partner_product
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_account_immutable();
CREATE TRIGGER integrations_partner_product_no_truncate BEFORE TRUNCATE ON integrations.partner_product
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.partner_account_immutable();

CREATE TABLE integrations.partner_product_version (
    id             uuid        NOT NULL,
    product_id     uuid        NOT NULL,
    version        integer     NOT NULL,
    status         text        NOT NULL,
    row_version    integer     NOT NULL,
    name_en        text,
    name_fr        text,
    summary_en     text,
    summary_fr     text,
    eligibility_en text,
    eligibility_fr text,
    disclosure_en  text,
    disclosure_fr  text,
    info_url       text,
    effective_date date,
    end_date       date,
    min_months     integer,
    max_months     integer,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    CONSTRAINT integrations_partner_product_version_pkey PRIMARY KEY (id),
    CONSTRAINT integrations_partner_product_version_key UNIQUE (product_id, version),
    CONSTRAINT integrations_partner_product_version_product FOREIGN KEY (product_id)
        REFERENCES integrations.partner_product (id),
    CONSTRAINT integrations_partner_product_version_number CHECK (version BETWEEN 1 AND 1000),
    CONSTRAINT integrations_partner_product_version_status CHECK (status IN
        ('DRAFT', 'UNDER_REVIEW', 'ACTIVE', 'PAUSED', 'RETIRED')),
    CONSTRAINT integrations_partner_product_version_rowv CHECK (row_version BETWEEN 0 AND 100000),
    CONSTRAINT integrations_partner_product_version_lengths CHECK (
        (name_en IS NULL OR char_length(name_en) BETWEEN 2 AND 120)
        AND (name_fr IS NULL OR char_length(name_fr) BETWEEN 2 AND 120)
        AND (summary_en IS NULL OR char_length(summary_en) BETWEEN 10 AND 300)
        AND (summary_fr IS NULL OR char_length(summary_fr) BETWEEN 10 AND 300)
        AND (eligibility_en IS NULL OR char_length(eligibility_en) BETWEEN 10 AND 500)
        AND (eligibility_fr IS NULL OR char_length(eligibility_fr) BETWEEN 10 AND 500)
        AND (disclosure_en IS NULL OR char_length(disclosure_en) BETWEEN 20 AND 2000)
        AND (disclosure_fr IS NULL OR char_length(disclosure_fr) BETWEEN 20 AND 2000)),
    CONSTRAINT integrations_partner_product_version_url CHECK (
        info_url IS NULL OR (char_length(info_url) BETWEEN 10 AND 300 AND info_url ~ '^https://[^[:space:]]+$')),
    CONSTRAINT integrations_partner_product_version_dates CHECK (end_date IS NULL OR effective_date IS NULL
        OR end_date >= effective_date),
    CONSTRAINT integrations_partner_product_version_months CHECK (
        (min_months IS NULL) = (max_months IS NULL)
        AND (min_months IS NULL OR (min_months BETWEEN 1 AND 600 AND max_months BETWEEN min_months AND 600))),
    -- A version that has left DRAFT is complete in both languages and dated.
    CONSTRAINT integrations_partner_product_version_complete CHECK (
        status = 'DRAFT'
        OR (name_en IS NOT NULL AND name_fr IS NOT NULL AND summary_en IS NOT NULL AND summary_fr IS NOT NULL
            AND eligibility_en IS NOT NULL AND eligibility_fr IS NOT NULL AND disclosure_en IS NOT NULL
            AND disclosure_fr IS NOT NULL AND effective_date IS NOT NULL)),
    CONSTRAINT integrations_partner_product_version_times CHECK (updated_at >= created_at)
);

COMMENT ON TABLE integrations.partner_product_version IS
    'MVP-120E product version. Content is editable only while DRAFT; once submitted it is frozen, and a change to a live '
    'product is a new version. Information only: no application, enrollment, connection or employee attribute.';

-- One working version and one live version per product.
CREATE UNIQUE INDEX integrations_partner_product_one_working ON integrations.partner_product_version (product_id)
    WHERE status IN ('DRAFT', 'UNDER_REVIEW');
CREATE UNIQUE INDEX integrations_partner_product_one_live ON integrations.partner_product_version (product_id)
    WHERE status IN ('ACTIVE', 'PAUSED');
CREATE INDEX integrations_partner_product_version_active ON integrations.partner_product_version
    (effective_date, end_date, product_id) WHERE status = 'ACTIVE';
CREATE INDEX integrations_partner_product_by_partner ON integrations.partner_product (partner_id, product_code);

CREATE TABLE integrations.partner_product_country (
    version_id   uuid    NOT NULL,
    country_code char(2) NOT NULL,
    CONSTRAINT integrations_partner_product_country_pkey PRIMARY KEY (version_id, country_code),
    CONSTRAINT integrations_partner_product_country_version FOREIGN KEY (version_id)
        REFERENCES integrations.partner_product_version (id),
    CONSTRAINT integrations_partner_product_country_code CHECK (country_code ~ '^[A-Z]{2}$')
);
CREATE INDEX integrations_partner_product_country_lookup
    ON integrations.partner_product_country (country_code, version_id);

-- Currency is any ISO 4217 code at the storage level; the service restricts the currently offered set (CDF, USD), so
-- adding a currency later changes configuration, not stored records. Limits are per currency.
CREATE TABLE integrations.partner_product_currency (
    version_id uuid          NOT NULL,
    currency   char(3)       NOT NULL,
    min_amount numeric(18,2),
    max_amount numeric(18,2),
    CONSTRAINT integrations_partner_product_currency_pkey PRIMARY KEY (version_id, currency),
    CONSTRAINT integrations_partner_product_currency_version FOREIGN KEY (version_id)
        REFERENCES integrations.partner_product_version (id),
    CONSTRAINT integrations_partner_product_currency_code CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT integrations_partner_product_currency_limits CHECK (
        (min_amount IS NULL) = (max_amount IS NULL)
        AND (min_amount IS NULL OR (min_amount >= 0 AND max_amount >= min_amount AND max_amount <= 1000000000000)))
);

CREATE FUNCTION integrations.partner_product_country_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    version_status text;
    owner uuid;
    target_version uuid;
BEGIN
    target_version := CASE WHEN TG_OP = 'DELETE' THEN OLD.version_id ELSE NEW.version_id END;
    SELECT v.status, p.partner_id INTO version_status, owner
      FROM integrations.partner_product_version v JOIN integrations.partner_product p ON p.id = v.product_id
     WHERE v.id = target_version FOR SHARE OF v;
    IF version_status IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_product_child_draft',
            MESSAGE = 'only a draft product version can change its countries and currencies';
    END IF;
    IF TG_OP = 'INSERT' AND NOT EXISTS (
            SELECT 1 FROM integrations.partner_country c
             WHERE c.partner_id = owner AND c.country_code = NEW.country_code) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_product_country_scope',
            MESSAGE = 'a product country must be within the approved operating scope of its partner';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END
$fn$;

CREATE FUNCTION integrations.partner_product_currency_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    version_status text;
    target_version uuid;
BEGIN
    target_version := CASE WHEN TG_OP = 'DELETE' THEN OLD.version_id ELSE NEW.version_id END;
    SELECT v.status INTO version_status FROM integrations.partner_product_version v
     WHERE v.id = target_version FOR SHARE;
    IF version_status IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_product_child_draft',
            MESSAGE = 'only a draft product version can change its countries and currencies';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END
$fn$;

CREATE TRIGGER integrations_partner_product_country_guard BEFORE INSERT OR DELETE ON integrations.partner_product_country
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_product_country_guard();
CREATE TRIGGER integrations_partner_product_country_no_update BEFORE UPDATE ON integrations.partner_product_country
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_account_immutable();
CREATE TRIGGER integrations_partner_product_country_no_truncate BEFORE TRUNCATE ON integrations.partner_product_country
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.partner_account_immutable();
CREATE TRIGGER integrations_partner_product_currency_guard BEFORE INSERT OR DELETE ON integrations.partner_product_currency
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_product_currency_guard();
CREATE TRIGGER integrations_partner_product_currency_no_update BEFORE UPDATE ON integrations.partner_product_currency
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_account_immutable();
CREATE TRIGGER integrations_partner_product_currency_no_truncate BEFORE TRUNCATE ON integrations.partner_product_currency
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.partner_account_immutable();

CREATE FUNCTION integrations.partner_product_version_insert_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    latest integer;
    partner_status text;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.product_id::text, 123));
    SELECT p.status INTO partner_status
      FROM integrations.partner_product x JOIN integrations.partner p ON p.id = x.partner_id
     WHERE x.id = NEW.product_id FOR SHARE OF p;
    IF partner_status IS NULL OR partner_status = 'CLOSED' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_product_version_partner_open',
            MESSAGE = 'a product version belongs to a partner that is not closed';
    END IF;
    SELECT max(v.version) INTO latest FROM integrations.partner_product_version v WHERE v.product_id = NEW.product_id;
    IF NEW.version <> coalesce(latest, 0) + 1 OR NEW.status <> 'DRAFT' OR NEW.row_version <> 0 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_product_version_initial',
            MESSAGE = 'a product version is the next number, created as a draft at row version 0';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER integrations_partner_product_version_insert_guard BEFORE INSERT ON integrations.partner_product_version
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_product_version_insert_guard();

CREATE FUNCTION integrations.partner_product_version_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    kind text;
    owner uuid;
    partner_status text;
    countries integer;
    currencies integer;
    unlimited integer;
BEGIN
    IF OLD.status = 'RETIRED' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_product_version_terminal',
            MESSAGE = 'a retired product version is never changed';
    END IF;
    IF (NEW.id, NEW.product_id, NEW.version, NEW.created_at) IS DISTINCT FROM
       (OLD.id, OLD.product_id, OLD.version, OLD.created_at) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_product_version_frozen',
            MESSAGE = 'the identity of a product version is frozen';
    END IF;
    IF NEW.row_version <> OLD.row_version + 1 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_product_version_step',
            MESSAGE = 'a product version change advances the row version by exactly one';
    END IF;
    IF NOT ((OLD.status = 'DRAFT' AND NEW.status IN ('DRAFT', 'UNDER_REVIEW', 'RETIRED'))
         OR (OLD.status = 'UNDER_REVIEW' AND NEW.status IN ('DRAFT', 'ACTIVE', 'RETIRED'))
         OR (OLD.status = 'ACTIVE' AND NEW.status IN ('PAUSED', 'RETIRED'))
         OR (OLD.status = 'PAUSED' AND NEW.status IN ('ACTIVE', 'RETIRED'))) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_product_version_transition',
            MESSAGE = 'this product status change is not allowed';
    END IF;
    IF (NEW.name_en, NEW.name_fr, NEW.summary_en, NEW.summary_fr, NEW.eligibility_en, NEW.eligibility_fr,
        NEW.disclosure_en, NEW.disclosure_fr, NEW.info_url, NEW.effective_date, NEW.end_date, NEW.min_months,
        NEW.max_months)
        IS DISTINCT FROM
       (OLD.name_en, OLD.name_fr, OLD.summary_en, OLD.summary_fr, OLD.eligibility_en, OLD.eligibility_fr,
        OLD.disclosure_en, OLD.disclosure_fr, OLD.info_url, OLD.effective_date, OLD.end_date, OLD.min_months,
        OLD.max_months)
       AND NOT (OLD.status = 'DRAFT' AND NEW.status = 'DRAFT') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_product_version_content',
            MESSAGE = 'the content of a submitted product version is frozen';
    END IF;
    SELECT x.product_type, x.partner_id INTO kind, owner
      FROM integrations.partner_product x WHERE x.id = NEW.product_id;
    SELECT p.status INTO partner_status FROM integrations.partner p WHERE p.id = owner FOR SHARE;
    IF partner_status = 'CLOSED' AND NEW.status <> 'RETIRED' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_product_version_partner_open',
            MESSAGE = 'only retirement is possible under a closed partner';
    END IF;
    IF NEW.status = 'ACTIVE' AND OLD.status <> 'ACTIVE' AND partner_status <> 'ACTIVE' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_product_version_partner_active',
            MESSAGE = 'a product becomes active only while its partner is active';
    END IF;
    IF NEW.status = 'UNDER_REVIEW' THEN
        SELECT count(*) INTO countries FROM integrations.partner_product_country c WHERE c.version_id = NEW.id;
        SELECT count(*) INTO currencies FROM integrations.partner_product_currency c WHERE c.version_id = NEW.id;
        IF countries < 1 OR currencies < 1 THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_product_version_scope',
                MESSAGE = 'a submitted product has at least one country and one currency';
        END IF;
        IF kind IN ('SALARY_ADVANCE', 'EMPLOYEE_LOAN') THEN
            SELECT count(*) INTO unlimited FROM integrations.partner_product_currency c
             WHERE c.version_id = NEW.id AND c.min_amount IS NULL;
            IF unlimited > 0 OR NEW.min_months IS NULL THEN
                RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_product_version_limits',
                    MESSAGE = 'a salary advance or employee loan states monetary and duration limits';
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER integrations_partner_product_version_guard BEFORE UPDATE ON integrations.partner_product_version
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_product_version_guard();
CREATE TRIGGER integrations_partner_product_version_no_delete BEFORE DELETE ON integrations.partner_product_version
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_account_immutable();
CREATE TRIGGER integrations_partner_product_version_no_truncate BEFORE TRUNCATE ON integrations.partner_product_version
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.partner_account_immutable();

-- ---------------------------------------------------------------------------------------------
-- Product history (append-only; one chain per product across all of its versions)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE integrations.partner_product_event (
    product_id      uuid        NOT NULL,
    sequence        integer     NOT NULL,
    version         integer     NOT NULL,
    event_type      text        NOT NULL,
    from_status     text,
    to_status       text        NOT NULL,
    reason_code     text,
    note_ciphertext bytea,
    actor_subject   text        NOT NULL,
    occurred_at     timestamptz NOT NULL,
    CONSTRAINT integrations_partner_product_event_pkey PRIMARY KEY (product_id, sequence),
    CONSTRAINT integrations_partner_product_event_product FOREIGN KEY (product_id)
        REFERENCES integrations.partner_product (id),
    CONSTRAINT integrations_partner_product_event_version FOREIGN KEY (product_id, version)
        REFERENCES integrations.partner_product_version (product_id, version),
    CONSTRAINT integrations_partner_product_event_sequence CHECK (sequence BETWEEN 1 AND 100000),
    CONSTRAINT integrations_partner_product_event_shape CHECK (
        (event_type = 'VERSION_CREATED' AND from_status IS NULL AND to_status = 'DRAFT' AND reason_code IS NULL)
        OR (event_type = 'SUBMITTED' AND from_status = 'DRAFT' AND to_status = 'UNDER_REVIEW'
            AND reason_code IS NULL)
        OR (event_type = 'RETURNED' AND from_status = 'UNDER_REVIEW' AND to_status = 'DRAFT'
            AND reason_code IS NULL)
        OR (event_type = 'PUBLISHED' AND from_status = 'UNDER_REVIEW' AND to_status = 'ACTIVE'
            AND reason_code IS NULL)
        OR (event_type = 'PAUSED' AND from_status = 'ACTIVE' AND to_status = 'PAUSED' AND reason_code IS NOT NULL)
        OR (event_type = 'RESUMED' AND from_status = 'PAUSED' AND to_status = 'ACTIVE' AND reason_code IS NULL)
        OR (event_type = 'RETIRED' AND from_status IN ('DRAFT', 'UNDER_REVIEW', 'ACTIVE', 'PAUSED')
            AND to_status = 'RETIRED' AND reason_code IS NOT NULL)
        OR (event_type = 'SUPERSEDED' AND from_status IN ('ACTIVE', 'PAUSED') AND to_status = 'RETIRED'
            AND reason_code IS NULL)),
    CONSTRAINT integrations_partner_product_event_reason CHECK (
        reason_code IS NULL OR reason_code IN
            ('COMPLIANCE_REVIEW', 'COMMERCIAL_HOLD', 'PARTNER_REQUEST', 'REGULATORY_RESTRICTION', 'SECURITY_CONCERN',
             'PROGRAM_ENDED', 'OTHER')),
    CONSTRAINT integrations_partner_product_event_note CHECK (
        (reason_code = 'OTHER') = (note_ciphertext IS NOT NULL)
        AND (note_ciphertext IS NULL OR octet_length(note_ciphertext) BETWEEN 40 AND 4096)),
    CONSTRAINT integrations_partner_product_event_actor CHECK (char_length(actor_subject) BETWEEN 1 AND 255)
);

CREATE FUNCTION integrations.partner_product_event_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    latest integer;
    current_status text;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.product_id::text, 124));
    SELECT max(e.sequence) INTO latest FROM integrations.partner_product_event e WHERE e.product_id = NEW.product_id;
    IF NEW.sequence <> coalesce(latest, 0) + 1 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_product_event_sequence',
            MESSAGE = 'a history event takes the next contiguous sequence';
    END IF;
    SELECT v.status INTO current_status FROM integrations.partner_product_version v
     WHERE v.product_id = NEW.product_id AND v.version = NEW.version;
    IF NEW.to_status IS DISTINCT FROM current_status THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_product_event_status',
            MESSAGE = 'a history event records the status the product version actually has';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER integrations_partner_product_event_guard BEFORE INSERT ON integrations.partner_product_event
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_product_event_guard();
CREATE TRIGGER integrations_partner_product_event_no_update BEFORE UPDATE ON integrations.partner_product_event
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_account_immutable();
CREATE TRIGGER integrations_partner_product_event_no_delete BEFORE DELETE ON integrations.partner_product_event
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_account_immutable();
CREATE TRIGGER integrations_partner_product_event_no_truncate BEFORE TRUNCATE ON integrations.partner_product_event
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.partner_account_immutable();
