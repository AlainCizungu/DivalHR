-- =============================================================================================
-- V55 (MVP-120D, Issue #215): partner applications and controlled onboarding.
--
-- ADR 0043 (D120D-1 to D120D-16). A generic, platform-level commercial intake: a prospective partner (bank, microfinance
-- institution, insurer, health-benefit provider, telecom operator, mobile-money or payment provider, pension provider or
-- similar) submits a bilingual application, and a platform administrator with exact MFA reviews it. It lives in the
-- bounded `integrations` schema, never in the payroll or salary-advance tables.
--
-- APPROVED_FOR_ONBOARDING only means DivalHR may begin contractual, security and technical discussions. Nothing here
-- creates a login, credential, listing, integration, employer or employee data access, lending authority or money
-- movement. Contact data and internal notes are confidential: they are stored only as AES-GCM ciphertext (the key lives
-- outside the database); only keyed one-way digests are kept for duplicate and rate-limit checks. There is no automatic
-- deletion: no legal retention period is invented (D120D-15). Applications are immutable commercial records.
--
-- Privileges: like V43 to V54 this migration issues no GRANT or REVOKE; the authority is in the triggers below.
-- =============================================================================================

CREATE FUNCTION integrations.partner_application_immutable() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_application_immutable',
        MESSAGE = 'partner-application evidence is never deleted, truncated or updated';
END
$fn$;

-- ---------------------------------------------------------------------------------------------
-- Application (status and row_version, with updated_at, are the only mutable columns)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE integrations.partner_application (
    id                      uuid        NOT NULL,
    reference               text        NOT NULL,
    status                  text        NOT NULL,
    row_version             integer     NOT NULL,
    primary_category        text        NOT NULL,
    legal_name              text        NOT NULL,
    trading_name            text,
    country_of_registration text        NOT NULL,
    countries_of_operation  text[]      NOT NULL,
    website                 text        NOT NULL,
    contact_role            text        NOT NULL,
    supported_languages     text[]      NOT NULL,
    api_availability        text        NOT NULL,
    regulator_name          text,
    service_summary         text        NOT NULL,
    confidential_ciphertext bytea       NOT NULL,
    email_digest            char(64)    NOT NULL,
    organization_fingerprint char(64)   NOT NULL,
    submission_locale       text        NOT NULL,
    submitted_at            timestamptz NOT NULL,
    updated_at              timestamptz NOT NULL,
    CONSTRAINT integrations_partner_application_pkey PRIMARY KEY (id),
    CONSTRAINT integrations_partner_application_reference_key UNIQUE (reference),
    CONSTRAINT integrations_partner_application_reference CHECK (reference ~ '^PA-[0-9A-HJKMNP-TV-Z]{12}$'),
    CONSTRAINT integrations_partner_application_status CHECK (status IN
        ('SUBMITTED', 'UNDER_REVIEW', 'MORE_INFORMATION_REQUIRED', 'APPROVED_FOR_ONBOARDING', 'DECLINED', 'WITHDRAWN')),
    CONSTRAINT integrations_partner_application_version CHECK (row_version BETWEEN 0 AND 100000),
    CONSTRAINT integrations_partner_application_category CHECK (primary_category IN
        ('BANK', 'MICROFINANCE', 'INSURANCE', 'HEALTH_BENEFITS', 'TELECOM', 'MOBILE_MONEY_PAYMENT', 'PENSION_SAVINGS',
         'OTHER_REGULATED_PROVIDER')),
    CONSTRAINT integrations_partner_application_legal_name CHECK (char_length(legal_name) BETWEEN 2 AND 200),
    CONSTRAINT integrations_partner_application_trading_name CHECK (
        trading_name IS NULL OR char_length(trading_name) BETWEEN 2 AND 200),
    CONSTRAINT integrations_partner_application_country CHECK (country_of_registration ~ '^[A-Z]{2}$'),
    CONSTRAINT integrations_partner_application_operation CHECK (
        cardinality(countries_of_operation) BETWEEN 1 AND 30
        AND array_to_string(countries_of_operation, ',') ~ '^[A-Z]{2}(,[A-Z]{2})*$'),
    CONSTRAINT integrations_partner_application_website CHECK (
        char_length(website) BETWEEN 10 AND 300 AND website ~ '^https://[^[:space:]]+$'),
    CONSTRAINT integrations_partner_application_role CHECK (char_length(contact_role) BETWEEN 2 AND 120),
    CONSTRAINT integrations_partner_application_languages CHECK (
        cardinality(supported_languages) BETWEEN 1 AND 2 AND supported_languages <@ ARRAY['en', 'fr']::text[]),
    CONSTRAINT integrations_partner_application_api CHECK (api_availability IN ('YES', 'NO', 'PLANNED')),
    CONSTRAINT integrations_partner_application_regulator CHECK (
        regulator_name IS NULL OR char_length(regulator_name) BETWEEN 2 AND 200),
    -- D120D-7: the regulatory information is required for the regulated categories (the licence or reference number is
    -- confidential and travels inside the ciphertext, so the service enforces it; the regulator name is checked here).
    CONSTRAINT integrations_partner_application_regulated CHECK (
        primary_category NOT IN ('BANK', 'MICROFINANCE', 'INSURANCE', 'TELECOM', 'MOBILE_MONEY_PAYMENT', 'PENSION_SAVINGS')
        OR regulator_name IS NOT NULL),
    CONSTRAINT integrations_partner_application_summary CHECK (char_length(service_summary) BETWEEN 20 AND 1000),
    CONSTRAINT integrations_partner_application_cipher CHECK (octet_length(confidential_ciphertext) BETWEEN 40 AND 4096),
    CONSTRAINT integrations_partner_application_email_digest CHECK (email_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT integrations_partner_application_org_digest CHECK (organization_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT integrations_partner_application_locale CHECK (submission_locale IN ('en', 'fr')),
    CONSTRAINT integrations_partner_application_times CHECK (updated_at >= submitted_at)
);

COMMENT ON TABLE integrations.partner_application IS
    'MVP-120D partner application (Confidential business contact data). Approval means only that onboarding discussions '
    'may begin. confidential_ciphertext holds contact name, email, phone, registration reference, licence number and '
    'privacy contact (AES-GCM, key outside the database). Digests are keyed one-way values for duplicate and rate-limit '
    'checks only. No automatic deletion and no invented retention period.';

CREATE INDEX integrations_partner_application_queue
    ON integrations.partner_application (status, submitted_at DESC, id DESC);
CREATE INDEX integrations_partner_application_category
    ON integrations.partner_application (primary_category, submitted_at DESC, id DESC);
CREATE INDEX integrations_partner_application_country
    ON integrations.partner_application (country_of_registration, submitted_at DESC, id DESC);
CREATE INDEX integrations_partner_application_duplicates
    ON integrations.partner_application (organization_fingerprint, email_digest);

CREATE FUNCTION integrations.partner_application_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    blocking integer;
BEGIN
    IF OLD.status IN ('APPROVED_FOR_ONBOARDING', 'DECLINED', 'WITHDRAWN') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_application_terminal',
            MESSAGE = 'a terminal partner application is never changed';
    END IF;
    IF (NEW.id, NEW.reference, NEW.primary_category, NEW.legal_name, NEW.trading_name, NEW.country_of_registration,
        NEW.countries_of_operation, NEW.website, NEW.contact_role, NEW.supported_languages, NEW.api_availability,
        NEW.regulator_name, NEW.service_summary, NEW.confidential_ciphertext, NEW.email_digest,
        NEW.organization_fingerprint, NEW.submission_locale, NEW.submitted_at)
        IS DISTINCT FROM
       (OLD.id, OLD.reference, OLD.primary_category, OLD.legal_name, OLD.trading_name, OLD.country_of_registration,
        OLD.countries_of_operation, OLD.website, OLD.contact_role, OLD.supported_languages, OLD.api_availability,
        OLD.regulator_name, OLD.service_summary, OLD.confidential_ciphertext, OLD.email_digest,
        OLD.organization_fingerprint, OLD.submission_locale, OLD.submitted_at) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_application_frozen',
            MESSAGE = 'the submitted content of a partner application is frozen';
    END IF;
    IF NEW.row_version <> OLD.row_version + 1 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_application_version',
            MESSAGE = 'the row version advances by exactly one';
    END IF;
    IF NEW.status <> OLD.status AND NOT (
           (OLD.status = 'SUBMITTED' AND NEW.status IN ('UNDER_REVIEW', 'WITHDRAWN'))
        OR (OLD.status = 'UNDER_REVIEW'
            AND NEW.status IN ('MORE_INFORMATION_REQUIRED', 'APPROVED_FOR_ONBOARDING', 'DECLINED', 'WITHDRAWN'))
        OR (OLD.status = 'MORE_INFORMATION_REQUIRED' AND NEW.status IN ('UNDER_REVIEW', 'DECLINED', 'WITHDRAWN'))) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_application_transition',
            MESSAGE = 'this partner application status transition is not allowed';
    END IF;
    IF NEW.status = 'APPROVED_FOR_ONBOARDING' THEN
        SELECT count(*) INTO blocking FROM integrations.partner_application_check c
         WHERE c.application_id = NEW.id
           AND c.check_code <> 'COMMERCIAL_DISCUSSION'
           AND c.result NOT IN ('SATISFIED', 'NOT_APPLICABLE');
        IF blocking > 0 THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_application_checklist',
                MESSAGE = 'every applicable mandatory due-diligence check must be satisfied before approval';
        END IF;
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER integrations_partner_application_guard BEFORE UPDATE ON integrations.partner_application
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_application_guard();
CREATE TRIGGER integrations_partner_application_no_delete BEFORE DELETE ON integrations.partner_application
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_application_immutable();
CREATE TRIGGER integrations_partner_application_no_truncate BEFORE TRUNCATE ON integrations.partner_application
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.partner_application_immutable();

-- ---------------------------------------------------------------------------------------------
-- Additional categories (primary category is on the application), capabilities, consent
-- ---------------------------------------------------------------------------------------------

CREATE TABLE integrations.partner_application_category (
    application_id uuid NOT NULL,
    position       integer NOT NULL,
    category       text NOT NULL,
    CONSTRAINT integrations_partner_application_category_pkey PRIMARY KEY (application_id, position),
    CONSTRAINT integrations_partner_application_category_unique UNIQUE (application_id, category),
    CONSTRAINT integrations_partner_application_category_application FOREIGN KEY (application_id)
        REFERENCES integrations.partner_application (id),
    CONSTRAINT integrations_partner_application_category_position CHECK (position BETWEEN 1 AND 3),
    CONSTRAINT integrations_partner_application_category_code CHECK (category IN
        ('BANK', 'MICROFINANCE', 'INSURANCE', 'HEALTH_BENEFITS', 'TELECOM', 'MOBILE_MONEY_PAYMENT', 'PENSION_SAVINGS',
         'OTHER_REGULATED_PROVIDER'))
);

CREATE TABLE integrations.partner_application_capability (
    application_id uuid NOT NULL,
    capability     text NOT NULL,
    CONSTRAINT integrations_partner_application_capability_pkey PRIMARY KEY (application_id, capability),
    CONSTRAINT integrations_partner_application_capability_application FOREIGN KEY (application_id)
        REFERENCES integrations.partner_application (id),
    CONSTRAINT integrations_partner_application_capability_code CHECK (capability IN
        ('SALARY_ADVANCE', 'EMPLOYEE_LOANS', 'HEALTH_INSURANCE', 'LIFE_INSURANCE', 'ACCIDENT_INSURANCE',
         'HEALTHCARE_BENEFITS', 'AIRTIME_DATA_BENEFITS', 'SMS_USSD_SERVICES', 'MOBILE_MONEY_DISBURSEMENT',
         'PAYMENT_COLLECTION', 'PENSION_SAVINGS', 'OTHER_EMPLOYEE_SERVICE'))
);
CREATE INDEX integrations_partner_application_capability_lookup
    ON integrations.partner_application_capability (capability, application_id);

COMMENT ON TABLE integrations.partner_application_capability IS
    'Capabilities are applicant assertions only: they are not verified or endorsed by DivalHR.';

CREATE TABLE integrations.partner_application_consent (
    application_id         uuid        NOT NULL,
    privacy_notice_version text        NOT NULL,
    consent_version        text        NOT NULL,
    acknowledged           boolean     NOT NULL,
    locale                 text        NOT NULL,
    accepted_at            timestamptz NOT NULL,
    CONSTRAINT integrations_partner_application_consent_pkey PRIMARY KEY (application_id),
    CONSTRAINT integrations_partner_application_consent_application FOREIGN KEY (application_id)
        REFERENCES integrations.partner_application (id),
    CONSTRAINT integrations_partner_application_consent_notice CHECK (privacy_notice_version ~ '^[A-Za-z0-9._-]{1,40}$'),
    CONSTRAINT integrations_partner_application_consent_version CHECK (consent_version ~ '^[A-Za-z0-9._-]{1,40}$'),
    CONSTRAINT integrations_partner_application_consent_given CHECK (acknowledged),
    CONSTRAINT integrations_partner_application_consent_locale CHECK (locale IN ('en', 'fr'))
);

-- ---------------------------------------------------------------------------------------------
-- Due-diligence checklist: one row per check; the immutable history below records every change
-- ---------------------------------------------------------------------------------------------

CREATE TABLE integrations.partner_application_check (
    application_id   uuid        NOT NULL,
    check_code       text        NOT NULL,
    result           text        NOT NULL,
    actor_subject    text,
    note_ciphertext  bytea,
    row_version      integer     NOT NULL,
    updated_at       timestamptz NOT NULL,
    CONSTRAINT integrations_partner_application_check_pkey PRIMARY KEY (application_id, check_code),
    CONSTRAINT integrations_partner_application_check_application FOREIGN KEY (application_id)
        REFERENCES integrations.partner_application (id),
    CONSTRAINT integrations_partner_application_check_code CHECK (check_code IN
        ('BUSINESS_IDENTITY', 'REGISTRATION_REFERENCE', 'REGULATORY_INFORMATION', 'SERVICE_SCOPE',
         'PRIVACY_SECURITY_CONTACT', 'TECHNICAL_API_READINESS', 'COMMERCIAL_DISCUSSION')),
    CONSTRAINT integrations_partner_application_check_result CHECK (result IN
        ('PENDING', 'SATISFIED', 'NOT_APPLICABLE', 'CONCERN')),
    CONSTRAINT integrations_partner_application_check_actor CHECK (
        (result = 'PENDING' AND actor_subject IS NULL) OR (result <> 'PENDING' AND char_length(actor_subject) BETWEEN 1 AND 255)),
    CONSTRAINT integrations_partner_application_check_note CHECK (
        note_ciphertext IS NULL OR octet_length(note_ciphertext) BETWEEN 40 AND 2048),
    CONSTRAINT integrations_partner_application_check_version CHECK (row_version BETWEEN 0 AND 100000)
);

CREATE FUNCTION integrations.partner_application_check_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    app integrations.partner_application%ROWTYPE;
BEGIN
    SELECT * INTO app FROM integrations.partner_application a WHERE a.id = OLD.application_id;
    IF app.status IN ('APPROVED_FOR_ONBOARDING', 'DECLINED', 'WITHDRAWN') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_application_terminal',
            MESSAGE = 'the checklist of a terminal partner application is never changed';
    END IF;
    IF NEW.application_id <> OLD.application_id OR NEW.check_code <> OLD.check_code THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_application_check_frozen',
            MESSAGE = 'a checklist row keeps its application and code';
    END IF;
    IF NEW.row_version <> OLD.row_version + 1 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_application_check_version',
            MESSAGE = 'the row version advances by exactly one';
    END IF;
    IF NEW.check_code = 'REGULATORY_INFORMATION' AND NEW.result = 'NOT_APPLICABLE'
        AND app.primary_category IN ('BANK', 'MICROFINANCE', 'INSURANCE', 'TELECOM', 'MOBILE_MONEY_PAYMENT',
                                     'PENSION_SAVINGS') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_application_check_regulated',
            MESSAGE = 'regulatory information is applicable to a regulated category';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER integrations_partner_application_check_guard BEFORE UPDATE ON integrations.partner_application_check
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_application_check_guard();
CREATE TRIGGER integrations_partner_application_check_no_delete BEFORE DELETE ON integrations.partner_application_check
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_application_immutable();
CREATE TRIGGER integrations_partner_application_check_no_truncate BEFORE TRUNCATE ON integrations.partner_application_check
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.partner_application_immutable();

-- ---------------------------------------------------------------------------------------------
-- Immutable status and checklist history (D120D-14). No contact details, notes or summary text.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE integrations.partner_application_event (
    application_id uuid        NOT NULL,
    sequence       integer     NOT NULL,
    event_type     text        NOT NULL,
    from_status    text,
    to_status      text        NOT NULL,
    check_code     text,
    check_result   text,
    reason_code    text,
    actor_type     text        NOT NULL,
    actor_subject  text,
    occurred_at    timestamptz NOT NULL,
    CONSTRAINT integrations_partner_application_event_pkey PRIMARY KEY (application_id, sequence),
    CONSTRAINT integrations_partner_application_event_application FOREIGN KEY (application_id)
        REFERENCES integrations.partner_application (id),
    CONSTRAINT integrations_partner_application_event_sequence CHECK (sequence BETWEEN 1 AND 100000),
    CONSTRAINT integrations_partner_application_event_shape CHECK (
        (event_type = 'SUBMITTED' AND sequence = 1 AND from_status IS NULL AND to_status = 'SUBMITTED'
            AND actor_type = 'APPLICANT')
        OR (event_type = 'REVIEW_STARTED' AND from_status IN ('SUBMITTED', 'MORE_INFORMATION_REQUIRED')
            AND to_status = 'UNDER_REVIEW' AND actor_type = 'PLATFORM_ADMIN')
        OR (event_type = 'INFORMATION_REQUESTED' AND from_status = 'UNDER_REVIEW'
            AND to_status = 'MORE_INFORMATION_REQUIRED' AND reason_code IS NOT NULL AND actor_type = 'PLATFORM_ADMIN')
        OR (event_type = 'CHECKLIST_UPDATED' AND from_status = to_status AND check_code IS NOT NULL
            AND check_result IS NOT NULL AND actor_type = 'PLATFORM_ADMIN')
        OR (event_type = 'APPROVED_FOR_ONBOARDING' AND from_status = 'UNDER_REVIEW'
            AND to_status = 'APPROVED_FOR_ONBOARDING' AND actor_type = 'PLATFORM_ADMIN')
        OR (event_type = 'DECLINED' AND from_status IN ('UNDER_REVIEW', 'MORE_INFORMATION_REQUIRED')
            AND to_status = 'DECLINED' AND reason_code IS NOT NULL AND actor_type = 'PLATFORM_ADMIN')
        OR (event_type = 'WITHDRAWAL_RECORDED' AND from_status IN ('SUBMITTED', 'UNDER_REVIEW', 'MORE_INFORMATION_REQUIRED')
            AND to_status = 'WITHDRAWN' AND reason_code IS NOT NULL AND actor_type = 'PLATFORM_ADMIN')),
    CONSTRAINT integrations_partner_application_event_check CHECK (
        (check_code IS NULL) = (check_result IS NULL)
        AND (check_code IS NULL OR check_code IN
            ('BUSINESS_IDENTITY', 'REGISTRATION_REFERENCE', 'REGULATORY_INFORMATION', 'SERVICE_SCOPE',
             'PRIVACY_SECURITY_CONTACT', 'TECHNICAL_API_READINESS', 'COMMERCIAL_DISCUSSION'))
        AND (check_result IS NULL OR check_result IN ('PENDING', 'SATISFIED', 'NOT_APPLICABLE', 'CONCERN'))),
    CONSTRAINT integrations_partner_application_event_reason CHECK (
        reason_code IS NULL OR reason_code IN
            ('APPLICANT_REQUEST', 'OUT_OF_SCOPE', 'INCOMPLETE_INFORMATION', 'REGULATORY_CONCERN',
             'DUE_DILIGENCE_CONCERN', 'NOT_PURSUING', 'CLARIFY_REGULATORY_INFORMATION', 'CLARIFY_SERVICE_SCOPE',
             'CLARIFY_TECHNICAL_READINESS', 'OTHER')),
    CONSTRAINT integrations_partner_application_event_actor CHECK (
        (actor_type = 'APPLICANT' AND actor_subject IS NULL)
        OR (actor_type = 'PLATFORM_ADMIN' AND char_length(actor_subject) BETWEEN 1 AND 255))
);

CREATE FUNCTION integrations.partner_application_event_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    latest integer;
    current_status text;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.application_id::text, 121));
    SELECT max(e.sequence) INTO latest FROM integrations.partner_application_event e
     WHERE e.application_id = NEW.application_id;
    IF NEW.sequence <> coalesce(latest, 0) + 1 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_application_event_sequence',
            MESSAGE = 'a history event takes the next contiguous sequence';
    END IF;
    SELECT a.status INTO current_status FROM integrations.partner_application a WHERE a.id = NEW.application_id;
    IF NEW.to_status IS DISTINCT FROM current_status THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_application_event_status',
            MESSAGE = 'a history event records the status the application actually has';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER integrations_partner_application_event_guard BEFORE INSERT ON integrations.partner_application_event
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_application_event_guard();
CREATE TRIGGER integrations_partner_application_event_no_update BEFORE UPDATE ON integrations.partner_application_event
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_application_immutable();
CREATE TRIGGER integrations_partner_application_event_no_delete BEFORE DELETE ON integrations.partner_application_event
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_application_immutable();
CREATE TRIGGER integrations_partner_application_event_no_truncate BEFORE TRUNCATE ON integrations.partner_application_event
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.partner_application_immutable();

-- ---------------------------------------------------------------------------------------------
-- Idempotent public submission evidence (D120D-10): digest of the key, never the key
-- ---------------------------------------------------------------------------------------------

CREATE TABLE integrations.partner_application_submission (
    key_digest          char(64)    NOT NULL,
    request_fingerprint char(64)    NOT NULL,
    application_id      uuid        NOT NULL,
    created_at          timestamptz NOT NULL,
    CONSTRAINT integrations_partner_application_submission_pkey PRIMARY KEY (key_digest),
    CONSTRAINT integrations_partner_application_submission_application UNIQUE (application_id),
    CONSTRAINT integrations_partner_application_submission_fk FOREIGN KEY (application_id)
        REFERENCES integrations.partner_application (id),
    CONSTRAINT integrations_partner_application_submission_key CHECK (key_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT integrations_partner_application_submission_fingerprint CHECK (request_fingerprint ~ '^[0-9a-f]{64}$')
);

-- ---------------------------------------------------------------------------------------------
-- Immutability of the child evidence, and completeness of a submission (deferred to commit)
-- ---------------------------------------------------------------------------------------------

CREATE TRIGGER integrations_partner_application_category_no_update BEFORE UPDATE ON integrations.partner_application_category
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_application_immutable();
CREATE TRIGGER integrations_partner_application_category_no_delete BEFORE DELETE ON integrations.partner_application_category
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_application_immutable();
CREATE TRIGGER integrations_partner_application_category_no_truncate BEFORE TRUNCATE ON integrations.partner_application_category
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.partner_application_immutable();
CREATE TRIGGER integrations_partner_application_capability_no_update BEFORE UPDATE ON integrations.partner_application_capability
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_application_immutable();
CREATE TRIGGER integrations_partner_application_capability_no_delete BEFORE DELETE ON integrations.partner_application_capability
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_application_immutable();
CREATE TRIGGER integrations_partner_application_capability_no_truncate BEFORE TRUNCATE ON integrations.partner_application_capability
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.partner_application_immutable();
CREATE TRIGGER integrations_partner_application_consent_no_update BEFORE UPDATE ON integrations.partner_application_consent
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_application_immutable();
CREATE TRIGGER integrations_partner_application_consent_no_delete BEFORE DELETE ON integrations.partner_application_consent
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_application_immutable();
CREATE TRIGGER integrations_partner_application_consent_no_truncate BEFORE TRUNCATE ON integrations.partner_application_consent
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.partner_application_immutable();
CREATE TRIGGER integrations_partner_application_submission_no_update BEFORE UPDATE ON integrations.partner_application_submission
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_application_immutable();
CREATE TRIGGER integrations_partner_application_submission_no_delete BEFORE DELETE ON integrations.partner_application_submission
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_application_immutable();
CREATE TRIGGER integrations_partner_application_submission_no_truncate BEFORE TRUNCATE ON integrations.partner_application_submission
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.partner_application_immutable();

CREATE FUNCTION integrations.partner_application_complete() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    app integrations.partner_application%ROWTYPE;
BEGIN
    SELECT * INTO app FROM integrations.partner_application a WHERE a.id = NEW.id;
    IF NOT EXISTS (SELECT 1 FROM integrations.partner_application_consent c WHERE c.application_id = app.id) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_application_consent_required',
            MESSAGE = 'a partner application records the privacy notice and consent version';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM integrations.partner_application_capability c WHERE c.application_id = app.id) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_application_capability_required',
            MESSAGE = 'a partner application requests at least one capability';
    END IF;
    IF EXISTS (SELECT 1 FROM integrations.partner_application_category c
                WHERE c.application_id = app.id AND c.category = app.primary_category) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_application_category_primary',
            MESSAGE = 'an additional category differs from the primary category';
    END IF;
    IF (SELECT count(*) FROM integrations.partner_application_check c WHERE c.application_id = app.id) <> 7 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_application_checklist_complete',
            MESSAGE = 'a partner application starts with its complete due-diligence checklist';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM integrations.partner_application_event e
                    WHERE e.application_id = app.id AND e.sequence = 1 AND e.event_type = 'SUBMITTED') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_application_submitted_event',
            MESSAGE = 'a partner application records its submission event';
    END IF;
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER integrations_partner_application_complete
    AFTER INSERT ON integrations.partner_application
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_application_complete();
