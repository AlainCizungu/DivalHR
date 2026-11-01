-- MVP-095A (Issue #183): salary payment destinations and immutable payment-batch preparation from one
-- approved MVP-094C payroll package (decisions D95A-1 to D95A-13, ADR 0036). Additive to V46; the first
-- story of the Payroll Disbursement epic.
--
-- PAYMENT PREPARATION ONLY. NO MONEY HAS BEEN SENT. Nothing here contacts a bank or a mobile-money
-- provider, marks anyone paid, proves a payment or verifies an account with a provider. A destination is
-- RECORDED, never VERIFIED.
--
-- Ownership: the payroll module; only the bounded com.divalhr.core.payroll package reads or writes these
-- objects. The keys to tenant.legal_entity and people.employee are the documented modular-monolith
-- integrity exception (constraints, never reads), like V37 and V43 to V46. The batch refers to
-- payroll.payroll_package and payroll.payroll_payslip (same module) by composite foreign keys.
--
-- Classification: Restricted financial and HR data. Account numbers, phone numbers, holder names, branch or
-- routing values and explanations are stored ONLY inside the authenticated encrypted envelope
-- (payment_destination.sealed, AES-256-GCM, versioned, bound to tenant, employee, destination and field set
-- by the application). Plaintext columns hold only the method, the controlled provider code and name, the
-- ISO country, the MASKED value shown after entry, a keyed fingerprint used for duplicate detection only,
-- and the closed reason. Fingerprints, ciphertext, amounts, employee numbers and identities never enter
-- audit metadata, outbox payloads, idempotency receipts, cursors, metrics, logs or error responses.
--
-- Shape:
--   * payment_destination: append-only, effective-dated history, one row per recorded version (sequence
--     1..n). The CURRENT destination of an employee on a date is the row with the greatest effective_from
--     not after that date. A destination is replaced only by appending the next version (strictly later
--     effective_from); it is never edited, ended or deleted.
--   * payment_batch: one immutable PREPARED batch (or its CANCELLED successor state) of one approved
--     package; chain version per package, at most 25; at most one PREPARED batch per package (partial unique
--     index). The only legal update is PREPARED -> CANCELLED, with its cancellation evidence recorded first.
--   * payment_batch_line: append-only payable lines copied from the immutable payslips (positive net only):
--     employee, employee number, method, provider, masked destination, destination version, net amount.
--   * payment_batch_cancel: append-only cancellation evidence (closed reason, no-money acknowledgement).
--
-- The database checks (deferred) that a batch equals its package: the same entity, period, currency,
-- version and approval digest, an APPROVED package, one line per positive-net payslip with the payslip's
-- exact net, the line count, the total and the zero-net exclusion count. No amount is calculated here.
--
-- Payroll payment guard: a package with a PREPARED batch cannot be voided (trigger on payroll_package,
-- SQLSTATE 23514, constraint payroll_payment_batch_blocks_void). A cancelled batch releases the guard.
--
-- Indexes (queries of ADR 0036):
--   * current destination per employee: payment_destination_history (tenant, employee, sequence DESC);
--   * duplicate detection: payment_destination_fingerprint (tenant, fingerprint);
--   * batch list per entity, newest first: payment_batch_history; batches of a package: unique chain key;
--   * batch lines by employee id (keyset): payment_batch_line_page.
--
-- Privileges: like V43 to V46 this migration issues no GRANT or REVOKE (the application role owns the
-- schema objects); the authority is in the triggers below. Seeds nothing.
--
-- Rollback: db/rollback/V47__rollback.sql (manual, never run by Flyway) restores V46 exactly only while no
-- destination, batch, line or cancellation row exists; no override, no retention decision.

CREATE FUNCTION payroll.payment_immutable() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payment_immutable',
        MESSAGE = 'payment destination and payment batch evidence is never deleted, truncated or updated';
END
$fn$;

-- ---------------------------------------------------------------------------------------------
-- Payment destinations (append-only history)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE payroll.payment_destination (
    tenant_id           uuid        NOT NULL,
    employee_id         uuid        NOT NULL,
    id                  uuid        NOT NULL,
    sequence            integer     NOT NULL,
    effective_from      date        NOT NULL,
    method              text        NOT NULL,
    country_code        char(2),
    provider_code       text,
    provider_name       text,
    masked_value        text,
    fingerprint         char(64),
    sealed              bytea,
    key_version         smallint,
    status              text        NOT NULL,
    reason_code         text        NOT NULL,
    recorded_at         timestamptz NOT NULL,
    recorded_by_subject text        NOT NULL,
    CONSTRAINT payment_destination_pkey PRIMARY KEY (tenant_id, employee_id, id),
    CONSTRAINT payment_destination_tenant_id_unique UNIQUE (tenant_id, id),
    CONSTRAINT payment_destination_sequence_unique UNIQUE (tenant_id, employee_id, sequence),
    CONSTRAINT payment_destination_employee
        FOREIGN KEY (tenant_id, employee_id) REFERENCES people.employee (tenant_id, id),
    CONSTRAINT payment_destination_sequence_range CHECK (sequence BETWEEN 1 AND 1000),
    CONSTRAINT payment_destination_effective CHECK (effective_from BETWEEN DATE '2000-01-01' AND DATE '2999-12-31'),
    CONSTRAINT payment_destination_method CHECK (method IN ('BANK_ACCOUNT', 'MOBILE_MONEY', 'MANUAL')),
    CONSTRAINT payment_destination_status CHECK (status = 'RECORDED'),
    CONSTRAINT payment_destination_reason CHECK (reason_code IN
        ('INITIAL_SETUP', 'EMPLOYEE_REQUEST', 'PROVIDER_CHANGE', 'ACCOUNT_CORRECTION', 'OTHER_CONTROLLED')),
    CONSTRAINT payment_destination_shape CHECK (
        (method = 'MANUAL' AND country_code IS NULL AND provider_code IS NULL AND provider_name IS NULL
            AND masked_value IS NULL AND fingerprint IS NULL AND sealed IS NULL AND key_version IS NULL)
        OR (method IN ('BANK_ACCOUNT', 'MOBILE_MONEY') AND country_code IS NOT NULL AND provider_code IS NOT NULL
            AND provider_name IS NOT NULL AND masked_value IS NOT NULL AND fingerprint IS NOT NULL
            AND sealed IS NOT NULL AND key_version IS NOT NULL)),
    CONSTRAINT payment_destination_country CHECK (country_code IS NULL OR country_code ~ '^[A-Z]{2}$'),
    CONSTRAINT payment_destination_provider CHECK (
        provider_code IS NULL OR (provider_code ~ '^[A-Z][A-Z0-9_]{1,39}$'
            AND char_length(provider_name) BETWEEN 1 AND 80 AND provider_name !~ '[[:cntrl:]]')),
    CONSTRAINT payment_destination_masked CHECK (
        masked_value IS NULL OR (char_length(masked_value) BETWEEN 1 AND 40 AND masked_value !~ '[[:cntrl:]]')),
    CONSTRAINT payment_destination_fingerprint CHECK (fingerprint IS NULL OR fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT payment_destination_sealed CHECK (sealed IS NULL OR octet_length(sealed) BETWEEN 30 AND 2048),
    CONSTRAINT payment_destination_key_version CHECK (key_version IS NULL OR key_version BETWEEN 1 AND 1000),
    CONSTRAINT payment_destination_subject CHECK (char_length(recorded_by_subject) BETWEEN 1 AND 255)
);

CREATE INDEX payment_destination_history ON payroll.payment_destination (tenant_id, employee_id, sequence DESC);
CREATE INDEX payment_destination_fingerprint ON payroll.payment_destination (tenant_id, fingerprint)
    WHERE fingerprint IS NOT NULL;

CREATE FUNCTION payroll.payment_destination_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    last payroll.payment_destination%ROWTYPE;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(
        'payroll.payment-destination:' || NEW.tenant_id::text || ':' || NEW.employee_id::text, 0));
    SELECT * INTO last FROM payroll.payment_destination
        WHERE tenant_id = NEW.tenant_id AND employee_id = NEW.employee_id
        ORDER BY sequence DESC LIMIT 1;
    IF NEW.sequence <> coalesce(last.sequence, 0) + 1 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payment_destination_sequence',
            MESSAGE = 'a payment destination is appended as the next version of its employee';
    END IF;
    IF last.id IS NOT NULL AND NEW.effective_from <= last.effective_from THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payment_destination_effective_order',
            MESSAGE = 'a replacement destination takes effect strictly after the one it replaces';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER payment_destination_guard BEFORE INSERT ON payroll.payment_destination
    FOR EACH ROW EXECUTE FUNCTION payroll.payment_destination_guard();
CREATE TRIGGER payment_destination_immutable BEFORE UPDATE OR DELETE ON payroll.payment_destination
    FOR EACH ROW EXECUTE FUNCTION payroll.payment_immutable();
CREATE TRIGGER payment_destination_no_truncate BEFORE TRUNCATE ON payroll.payment_destination
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.payment_immutable();

COMMENT ON TABLE payroll.payment_destination IS
    'MVP-095A append-only effective-dated salary payment destination (RECORDED, never verified with a provider); sensitive values only inside the encrypted envelope; restricted financial and HR data';

-- ---------------------------------------------------------------------------------------------
-- Payment batches
-- ---------------------------------------------------------------------------------------------

CREATE TABLE payroll.payment_batch (
    tenant_id               uuid        NOT NULL,
    legal_entity_id         uuid        NOT NULL,
    id                      uuid        NOT NULL,
    package_id              uuid        NOT NULL,
    version                 integer     NOT NULL,
    status                  text        NOT NULL,
    period_start            date        NOT NULL,
    currency                text        NOT NULL,
    package_version         integer     NOT NULL,
    package_digest          char(64)    NOT NULL,
    line_count              integer     NOT NULL,
    zero_net_excluded_count integer     NOT NULL,
    bank_count              integer     NOT NULL,
    mobile_count            integer     NOT NULL,
    manual_count            integer     NOT NULL,
    total_net_minor         bigint      NOT NULL,
    destinations_digest     char(64)    NOT NULL,
    batch_digest            char(64)    NOT NULL,
    reviewed_totals         boolean     NOT NULL,
    understands_no_money    boolean     NOT NULL,
    prepared_at             timestamptz NOT NULL,
    prepared_by_subject     text        NOT NULL,
    cancelled_at            timestamptz,
    CONSTRAINT payment_batch_pkey PRIMARY KEY (tenant_id, legal_entity_id, id),
    CONSTRAINT payment_batch_tenant_id_unique UNIQUE (tenant_id, id),
    CONSTRAINT payment_batch_version_unique UNIQUE (tenant_id, package_id, version),
    CONSTRAINT payment_batch_package FOREIGN KEY (tenant_id, legal_entity_id, package_id)
        REFERENCES payroll.payroll_package (tenant_id, legal_entity_id, id),
    CONSTRAINT payment_batch_version_range CHECK (version BETWEEN 1 AND 25),
    CONSTRAINT payment_batch_status CHECK (status IN ('PREPARED', 'CANCELLED')),
    CONSTRAINT payment_batch_lifecycle CHECK (
        (status = 'PREPARED' AND cancelled_at IS NULL)
        OR (status = 'CANCELLED' AND cancelled_at IS NOT NULL AND cancelled_at >= prepared_at)),
    CONSTRAINT payment_batch_currency CHECK (currency = 'CDF'),
    CONSTRAINT payment_batch_month CHECK (
        period_start = date_trunc('month', period_start)::date
        AND period_start BETWEEN DATE '2000-01-01' AND DATE '2999-12-01'),
    CONSTRAINT payment_batch_counts CHECK (
        line_count BETWEEN 1 AND 5000 AND zero_net_excluded_count BETWEEN 0 AND 5000
        AND bank_count >= 0 AND mobile_count >= 0 AND manual_count >= 0
        AND bank_count + mobile_count + manual_count = line_count),
    CONSTRAINT payment_batch_total CHECK (total_net_minor BETWEEN 1 AND 5000000000000),
    CONSTRAINT payment_batch_digests CHECK (
        package_digest ~ '^[0-9a-f]{64}$' AND destinations_digest ~ '^[0-9a-f]{64}$'
        AND batch_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT payment_batch_acknowledgement CHECK (reviewed_totals AND understands_no_money),
    CONSTRAINT payment_batch_subject CHECK (char_length(prepared_by_subject) BETWEEN 1 AND 255)
);

-- At most one PREPARED batch per package.
CREATE UNIQUE INDEX payment_batch_one_prepared ON payroll.payment_batch (tenant_id, package_id)
    WHERE status = 'PREPARED';
-- Batch list of an entity: newest first (keyset: prepared_at DESC, id DESC).
CREATE INDEX payment_batch_history ON payroll.payment_batch (tenant_id, legal_entity_id, prepared_at DESC, id DESC);
CREATE INDEX payment_batch_package_fk ON payroll.payment_batch (tenant_id, legal_entity_id, package_id);

CREATE TABLE payroll.payment_batch_line (
    tenant_id            uuid    NOT NULL,
    legal_entity_id      uuid    NOT NULL,
    batch_id             uuid    NOT NULL,
    package_id           uuid    NOT NULL,
    id                   uuid    NOT NULL,
    payslip_id           uuid    NOT NULL,
    employee_id          uuid    NOT NULL,
    employee_number      text    NOT NULL,
    method               text    NOT NULL,
    country_code         char(2),
    provider_code        text,
    provider_name        text,
    masked_value         text,
    destination_id       uuid,
    destination_sequence integer,
    net_minor            bigint  NOT NULL,
    CONSTRAINT payment_batch_line_pkey PRIMARY KEY (tenant_id, batch_id, id),
    CONSTRAINT payment_batch_line_employee_unique UNIQUE (tenant_id, batch_id, employee_id),
    CONSTRAINT payment_batch_line_payslip_unique UNIQUE (tenant_id, batch_id, payslip_id),
    CONSTRAINT payment_batch_line_batch FOREIGN KEY (tenant_id, legal_entity_id, batch_id)
        REFERENCES payroll.payment_batch (tenant_id, legal_entity_id, id),
    CONSTRAINT payment_batch_line_payslip FOREIGN KEY (tenant_id, package_id, payslip_id)
        REFERENCES payroll.payroll_payslip (tenant_id, package_id, id),
    CONSTRAINT payment_batch_line_destination FOREIGN KEY (tenant_id, employee_id, destination_id)
        REFERENCES payroll.payment_destination (tenant_id, employee_id, id),
    CONSTRAINT payment_batch_line_method CHECK (method IN ('BANK_ACCOUNT', 'MOBILE_MONEY', 'MANUAL')),
    CONSTRAINT payment_batch_line_number
        CHECK (char_length(employee_number) BETWEEN 1 AND 64 AND employee_number !~ '[[:cntrl:]]'),
    CONSTRAINT payment_batch_line_net CHECK (net_minor BETWEEN 1 AND 1000000000),
    CONSTRAINT payment_batch_line_destination_shape CHECK (
        (method = 'MANUAL' AND country_code IS NULL AND provider_code IS NULL AND provider_name IS NULL
            AND masked_value IS NULL)
        OR (method IN ('BANK_ACCOUNT', 'MOBILE_MONEY') AND country_code IS NOT NULL AND provider_code IS NOT NULL
            AND provider_name IS NOT NULL AND masked_value IS NOT NULL)),
    CONSTRAINT payment_batch_line_destination_ref CHECK (
        (destination_id IS NULL) = (destination_sequence IS NULL)
        AND (destination_sequence IS NULL OR destination_sequence BETWEEN 1 AND 1000))
);

-- Lines by employee id (keyset), and the foreign keys.
CREATE INDEX payment_batch_line_page ON payroll.payment_batch_line (tenant_id, batch_id, employee_id);
CREATE INDEX payment_batch_line_payslip_fk ON payroll.payment_batch_line (tenant_id, package_id, payslip_id);
CREATE INDEX payment_batch_line_destination_fk ON payroll.payment_batch_line (tenant_id, employee_id, destination_id);

CREATE TABLE payroll.payment_batch_cancel (
    tenant_id               uuid        NOT NULL,
    legal_entity_id         uuid        NOT NULL,
    batch_id                uuid        NOT NULL,
    id                      uuid        NOT NULL,
    reason_code             text        NOT NULL,
    no_money_acknowledged   boolean     NOT NULL,
    batch_digest            char(64)    NOT NULL,
    cancelled_at            timestamptz NOT NULL,
    cancelled_by_subject    text        NOT NULL,
    CONSTRAINT payment_batch_cancel_pkey PRIMARY KEY (tenant_id, legal_entity_id, id),
    CONSTRAINT payment_batch_cancel_batch_unique UNIQUE (tenant_id, batch_id),
    CONSTRAINT payment_batch_cancel_batch FOREIGN KEY (tenant_id, legal_entity_id, batch_id)
        REFERENCES payroll.payment_batch (tenant_id, legal_entity_id, id),
    CONSTRAINT payment_batch_cancel_reason CHECK (reason_code IN
        ('DESTINATION_CORRECTION', 'PACKAGE_CORRECTION', 'PREPARED_IN_ERROR', 'OTHER_CONTROLLED')),
    CONSTRAINT payment_batch_cancel_acknowledgement CHECK (no_money_acknowledged),
    CONSTRAINT payment_batch_cancel_digest CHECK (batch_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT payment_batch_cancel_subject CHECK (char_length(cancelled_by_subject) BETWEEN 1 AND 255)
);

-- ---------------------------------------------------------------------------------------------
-- Guards
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION payroll.payment_batch_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    latest integer;
    pkg    payroll.payroll_package%ROWTYPE;
    cancel payroll.payment_batch_cancel%ROWTYPE;
BEGIN
    IF TG_OP = 'INSERT' THEN
        PERFORM pg_advisory_xact_lock(hashtextextended(
            'payroll.payment-batch:' || NEW.tenant_id::text || ':' || NEW.package_id::text, 0));
        SELECT max(version) INTO latest FROM payroll.payment_batch
            WHERE tenant_id = NEW.tenant_id AND package_id = NEW.package_id;
        IF NEW.status <> 'PREPARED' OR NEW.cancelled_at IS NOT NULL OR NEW.version <> coalesce(latest, 0) + 1 THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payment_batch_version',
                MESSAGE = 'a payment batch is created PREPARED as the next version of its package chain';
        END IF;
        SELECT * INTO pkg FROM payroll.payroll_package
            WHERE tenant_id = NEW.tenant_id AND legal_entity_id = NEW.legal_entity_id AND id = NEW.package_id
            FOR SHARE;
        IF pkg.id IS NULL THEN
            RETURN NEW; -- a missing package is refused by its foreign key
        END IF;
        IF pkg.status <> 'APPROVED' OR pkg.version <> NEW.package_version OR pkg.approval_digest <> NEW.package_digest
            OR pkg.period_start <> NEW.period_start OR pkg.currency <> NEW.currency
            OR NEW.prepared_at < pkg.approved_at THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payment_batch_package_state',
                MESSAGE = 'a payment batch is prepared from the APPROVED payroll package it records exactly';
        END IF;
        IF EXISTS (SELECT 1 FROM payroll.payroll_package p
                    WHERE p.tenant_id = pkg.tenant_id AND p.legal_entity_id = pkg.legal_entity_id
                      AND p.period_start = pkg.period_start AND p.currency = pkg.currency
                      AND p.version > pkg.version) THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payment_batch_package_latest',
                MESSAGE = 'a payment batch is prepared only from the latest package of its entity and month';
        END IF;
        RETURN NEW;
    END IF;
    -- UPDATE: the sole legal update is PREPARED -> CANCELLED, once, with its cancellation evidence.
    IF OLD.status <> 'PREPARED' OR NEW.status <> 'CANCELLED'
        OR NEW.tenant_id <> OLD.tenant_id OR NEW.legal_entity_id <> OLD.legal_entity_id
        OR NEW.id <> OLD.id OR NEW.package_id <> OLD.package_id OR NEW.version <> OLD.version
        OR NEW.period_start <> OLD.period_start OR NEW.currency <> OLD.currency
        OR NEW.package_version <> OLD.package_version OR NEW.package_digest <> OLD.package_digest
        OR NEW.line_count <> OLD.line_count OR NEW.zero_net_excluded_count <> OLD.zero_net_excluded_count
        OR NEW.bank_count <> OLD.bank_count OR NEW.mobile_count <> OLD.mobile_count
        OR NEW.manual_count <> OLD.manual_count OR NEW.total_net_minor <> OLD.total_net_minor
        OR NEW.destinations_digest <> OLD.destinations_digest OR NEW.batch_digest <> OLD.batch_digest
        OR NEW.reviewed_totals <> OLD.reviewed_totals OR NEW.understands_no_money <> OLD.understands_no_money
        OR NEW.prepared_at <> OLD.prepared_at OR NEW.prepared_by_subject <> OLD.prepared_by_subject THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payment_batch_lifecycle_only',
            MESSAGE = 'a payment batch is only ever cancelled, once, and nothing else about it changes';
    END IF;
    SELECT * INTO cancel FROM payroll.payment_batch_cancel
        WHERE tenant_id = OLD.tenant_id AND batch_id = OLD.id;
    IF NOT FOUND OR cancel.cancelled_at <> NEW.cancelled_at OR cancel.batch_digest <> OLD.batch_digest THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payment_batch_cancel_evidence',
            MESSAGE = 'a payment batch is cancelled only with its cancellation evidence, recorded first';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER payment_batch_guard BEFORE INSERT OR UPDATE ON payroll.payment_batch
    FOR EACH ROW EXECUTE FUNCTION payroll.payment_batch_guard();
CREATE TRIGGER payment_batch_no_delete BEFORE DELETE ON payroll.payment_batch
    FOR EACH ROW EXECUTE FUNCTION payroll.payment_immutable();
CREATE TRIGGER payment_batch_no_truncate BEFORE TRUNCATE ON payroll.payment_batch
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.payment_immutable();
CREATE TRIGGER payment_batch_line_immutable BEFORE UPDATE OR DELETE ON payroll.payment_batch_line
    FOR EACH ROW EXECUTE FUNCTION payroll.payment_immutable();
CREATE TRIGGER payment_batch_line_no_truncate BEFORE TRUNCATE ON payroll.payment_batch_line
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.payment_immutable();
CREATE TRIGGER payment_batch_cancel_immutable BEFORE UPDATE OR DELETE ON payroll.payment_batch_cancel
    FOR EACH ROW EXECUTE FUNCTION payroll.payment_immutable();
CREATE TRIGGER payment_batch_cancel_no_truncate BEFORE TRUNCATE ON payroll.payment_batch_cancel
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.payment_immutable();

-- Cancellation evidence guard: only for a PREPARED batch, with the batch's own digest.
CREATE FUNCTION payroll.payment_batch_cancel_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    batch payroll.payment_batch%ROWTYPE;
BEGIN
    SELECT * INTO batch FROM payroll.payment_batch
        WHERE tenant_id = NEW.tenant_id AND legal_entity_id = NEW.legal_entity_id AND id = NEW.batch_id
        FOR UPDATE;
    IF batch.id IS NULL THEN
        RETURN NEW; -- a missing batch is refused by its foreign key
    END IF;
    IF batch.status <> 'PREPARED' OR batch.batch_digest <> NEW.batch_digest OR NEW.cancelled_at < batch.prepared_at THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payment_batch_cancel_state',
            MESSAGE = 'only a PREPARED payment batch is cancelled, and its cancellation records its digest';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER payment_batch_cancel_guard BEFORE INSERT ON payroll.payment_batch_cancel
    FOR EACH ROW EXECUTE FUNCTION payroll.payment_batch_cancel_guard();

-- A line is appended only to a batch created in the same transaction (a PREPARED, uncancelled batch).
CREATE FUNCTION payroll.payment_batch_line_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    batch payroll.payment_batch%ROWTYPE;
    slip  payroll.payroll_payslip%ROWTYPE;
    dest  payroll.payment_destination%ROWTYPE;
BEGIN
    SELECT * INTO batch FROM payroll.payment_batch
        WHERE tenant_id = NEW.tenant_id AND legal_entity_id = NEW.legal_entity_id AND id = NEW.batch_id;
    IF batch.id IS NULL THEN
        RETURN NEW;
    END IF;
    IF batch.status <> 'PREPARED' OR batch.package_id <> NEW.package_id THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payment_batch_line_state',
            MESSAGE = 'a payment batch line belongs to a PREPARED batch of its own package';
    END IF;
    SELECT * INTO slip FROM payroll.payroll_payslip
        WHERE tenant_id = NEW.tenant_id AND package_id = NEW.package_id AND id = NEW.payslip_id;
    IF slip.id IS NULL THEN
        RETURN NEW;
    END IF;
    IF slip.employee_id <> NEW.employee_id OR slip.employee_number <> NEW.employee_number
        OR slip.net_minor <> NEW.net_minor OR slip.excluded THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payment_batch_line_payslip',
            MESSAGE = 'a payment line copies the employee, the number and the net amount of its payslip exactly';
    END IF;
    IF NEW.destination_id IS NOT NULL THEN
        SELECT * INTO dest FROM payroll.payment_destination
            WHERE tenant_id = NEW.tenant_id AND employee_id = NEW.employee_id AND id = NEW.destination_id;
        IF dest.id IS NOT NULL AND (dest.sequence <> NEW.destination_sequence OR dest.method <> NEW.method
            OR dest.provider_code IS DISTINCT FROM NEW.provider_code
            OR dest.masked_value IS DISTINCT FROM NEW.masked_value
            OR dest.country_code IS DISTINCT FROM NEW.country_code
            OR dest.provider_name IS DISTINCT FROM NEW.provider_name) THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payment_batch_line_destination_copy',
                MESSAGE = 'a payment line records its destination version exactly, masked';
        END IF;
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER payment_batch_line_guard BEFORE INSERT ON payroll.payment_batch_line
    FOR EACH ROW EXECUTE FUNCTION payroll.payment_batch_line_guard();

-- Deferred equality of a batch with its package: one line per positive-net, non-excluded payslip, the
-- counts by method, the zero-net exclusion count and the total.
CREATE FUNCTION payroll.payment_batch_check(p_tenant uuid, p_batch uuid) RETURNS void LANGUAGE plpgsql AS
$fn$
DECLARE
    b          payroll.payment_batch%ROWTYPE;
    n_lines    integer;
    n_bank     integer;
    n_mobile   integer;
    n_manual   integer;
    sum_net    bigint;
    n_payable  integer;
    n_zero     integer;
    sum_slips  bigint;
BEGIN
    SELECT * INTO b FROM payroll.payment_batch WHERE tenant_id = p_tenant AND id = p_batch;
    IF b.id IS NULL THEN
        RETURN;
    END IF;
    SELECT count(*), count(*) FILTER (WHERE method = 'BANK_ACCOUNT'), count(*) FILTER (WHERE method = 'MOBILE_MONEY'),
           count(*) FILTER (WHERE method = 'MANUAL'), coalesce(sum(net_minor), 0)
        INTO n_lines, n_bank, n_mobile, n_manual, sum_net
        FROM payroll.payment_batch_line WHERE tenant_id = b.tenant_id AND batch_id = b.id;
    SELECT count(*) FILTER (WHERE NOT excluded AND net_minor > 0), count(*) FILTER (WHERE NOT excluded AND net_minor = 0),
           coalesce(sum(net_minor) FILTER (WHERE NOT excluded AND net_minor > 0), 0)
        INTO n_payable, n_zero, sum_slips
        FROM payroll.payroll_payslip WHERE tenant_id = b.tenant_id AND package_id = b.package_id;
    IF n_lines <> b.line_count OR n_bank <> b.bank_count OR n_mobile <> b.mobile_count OR n_manual <> b.manual_count
        OR sum_net <> b.total_net_minor OR n_payable <> b.line_count OR sum_slips <> b.total_net_minor
        OR n_zero + (SELECT count(*) FROM payroll.payroll_payslip s WHERE s.tenant_id = b.tenant_id
                       AND s.package_id = b.package_id AND s.excluded) <> b.zero_net_excluded_count THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payment_batch_exact',
            MESSAGE = 'a payment batch holds exactly one line per positive-net payslip of its package, with matching counts and total';
    END IF;
END
$fn$;

CREATE FUNCTION payroll.payment_batch_exact() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    PERFORM payroll.payment_batch_check(NEW.tenant_id, NEW.id);
    RETURN NULL;
END
$fn$;

CREATE FUNCTION payroll.payment_batch_line_exact() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    PERFORM payroll.payment_batch_check(NEW.tenant_id, NEW.batch_id);
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER payment_batch_exact AFTER INSERT ON payroll.payment_batch
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION payroll.payment_batch_exact();
CREATE CONSTRAINT TRIGGER payment_batch_line_exact AFTER INSERT ON payroll.payment_batch_line
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION payroll.payment_batch_line_exact();

-- ---------------------------------------------------------------------------------------------
-- Payroll payment guard: a package with a PREPARED batch is never voided.
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION payroll.payment_batch_blocks_void() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    IF NEW.status = 'VOIDED' AND EXISTS (
        SELECT 1 FROM payroll.payment_batch b
         WHERE b.tenant_id = OLD.tenant_id AND b.package_id = OLD.id AND b.status = 'PREPARED') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_payment_batch_blocks_void',
            MESSAGE = 'a payroll package with a PREPARED payment batch cannot be voided; cancel the batch first';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER payroll_package_payment_guard BEFORE UPDATE ON payroll.payroll_package
    FOR EACH ROW EXECUTE FUNCTION payroll.payment_batch_blocks_void();

COMMENT ON TABLE payroll.payment_batch IS
    'MVP-095A immutable salary payment batch prepared from one approved payroll package (payment preparation only: no money sent, no provider contacted, not proof of payment); restricted financial data';
COMMENT ON TABLE payroll.payment_batch_line IS
    'MVP-095A append-only payable line of a payment batch copied from an immutable payslip, with the masked destination version';
COMMENT ON TABLE payroll.payment_batch_cancel IS
    'MVP-095A append-only cancellation evidence of a payment batch (closed reason; nothing was sent through DivalHR)';
