-- MVP-093A (Issue #175): a tenant administrator with exact MFA records an employee's effective-dated
-- MONTHLY base compensation in CDF or USD without destroying history; a linked employee reads only
-- their own (decisions D93-1 to D93-16). Additive to V42; the second increment of the payroll module.
--
-- Ownership: the payroll module; only the bounded com.divalhr.core.payroll package reads or writes
-- these objects. The keys to tenant.organization and people.employee are the documented
-- modular-monolith integrity exception (constraints, never reads), like V16, V24, V25, V32, V41 and
-- V42. Employment containment of an interval is decided by the service through the people port
-- (it cannot be a constraint without reading people tables).
--
-- Classification: Restricted financial and HR data. The amount and the administrator explanation never
-- leave this schema except in authorized API responses; audit metadata, outbox payloads, idempotency
-- records, cursors, metrics, logs and error responses never carry them (D93-3).
--
-- Shape:
--   * compensation_change: append-only command evidence, one increasing sequence per employee
--     (ESTABLISHED, CHANGED, CORRECTED, SCHEDULED_CHANGE_CANCELLED). The compensation version of an
--     employee is the last sequence (0 when none). Never updated, deleted or truncated.
--   * compensation: the effective-dated timeline of facts (transaction-time evidence, like
--     people.employment_assignment). A fact is ACTIVE while superseded_seq IS NULL. The only update
--     ever allowed marks an active fact superseded by the latest change; a correction or a
--     cancellation appends fresh facts and keeps every earlier one. Never deleted or truncated.
--   * The active facts of an employee never overlap (exclusion constraint), are gap-free after the
--     first, and only the last is open-ended; the active timeline equals the latest committed change
--     (a deferred exact check). Gaps are allowed only before the first fact.
--   * CDF has exponent 0 (1 to 1,000,000,000 francs) and USD exponent 2 (1 to 10,000,000 minor units,
--     that is 0.01 to 100,000.00); amounts are integer minor units; frequency is exactly MONTHLY;
--     nothing converts or combines currencies.
--   * At most 100 changes per employee, enforced in the database under a per-employee advisory lock.
--
-- Rollback: db/rollback/V43__rollback.sql (manual, never run by Flyway) restores V42 exactly only
-- while no compensation or change row exists; no override, no retention decision.

CREATE FUNCTION payroll.compensation_immutable() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_compensation_immutable',
        MESSAGE = 'compensation evidence is never deleted, truncated or updated';
END
$fn$;

-- Restricted plain text: NFC, bounded, no edge whitespace, control or format characters, markup,
-- links or encoded content (the ADR 0030 grammar, owned by payroll so no module reads another's function).
CREATE FUNCTION payroll.compensation_text_valid(value text, min_length integer, max_length integer)
    RETURNS boolean LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE AS
$fn$
SELECT value IS NFC NORMALIZED
    AND char_length(value) BETWEEN min_length AND max_length
    AND value !~ '^[[:space:]]' AND value !~ '[[:space:]]$'
    AND value !~ '[\u0001-\u001F\u007F-\u009F\u00AD\u200B-\u200F\u2028-\u202E\u2060-\u2064\u2066-\u206F\uFEFF\uE000-\uF8FF\uFFF9-\uFFFB]'
    AND value !~ '[<>\\]'
    AND value !~* '(https?:|www\.|://|%[0-9a-f]{2}|&#|&[a-z][a-z0-9]{1,31};)'
$fn$;

-- ---------------------------------------------------------------------------------------------
-- Change (append-only command evidence)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE payroll.compensation_change (
    tenant_id                uuid        NOT NULL,
    employee_id              uuid        NOT NULL,
    id                       uuid        NOT NULL,
    seq                      integer     NOT NULL,
    action                   text        NOT NULL,
    occurred_at              timestamptz NOT NULL,
    actor_subject            text        NOT NULL,
    actor_role               text        NOT NULL,
    -- The value the change introduced (for a cancellation: the value that was cancelled).
    currency                 text        NOT NULL,
    exponent                 smallint    NOT NULL,
    amount_minor             bigint      NOT NULL,
    effective_from           date        NOT NULL,
    -- CORRECTED and SCHEDULED_CHANGE_CANCELLED name the earlier change they act on.
    target_change_id         uuid,
    reason_code              text,
    explanation              text,
    retroactive_acknowledged boolean     NOT NULL,
    -- The confirmed preview or cancellation digest (integrity evidence, no amount).
    preview_digest           text        NOT NULL,
    CONSTRAINT payroll_compensation_change_pkey PRIMARY KEY (tenant_id, employee_id, id),
    CONSTRAINT payroll_compensation_change_seq_unique UNIQUE (tenant_id, employee_id, seq),
    CONSTRAINT payroll_compensation_change_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.organization (id),
    CONSTRAINT payroll_compensation_change_employee FOREIGN KEY (tenant_id, employee_id)
        REFERENCES people.employee (tenant_id, id),
    CONSTRAINT payroll_compensation_change_target FOREIGN KEY (tenant_id, employee_id, target_change_id)
        REFERENCES payroll.compensation_change (tenant_id, employee_id, id),
    CONSTRAINT payroll_compensation_change_seq CHECK (seq >= 1),
    CONSTRAINT payroll_compensation_change_action CHECK (action IN
        ('ESTABLISHED', 'CHANGED', 'CORRECTED', 'SCHEDULED_CHANGE_CANCELLED')),
    CONSTRAINT payroll_compensation_change_actor CHECK (
        char_length(actor_subject) BETWEEN 1 AND 255 AND actor_role = 'tenant-admin'),
    CONSTRAINT payroll_compensation_change_money CHECK (
        (currency = 'CDF' AND exponent = 0 AND amount_minor BETWEEN 1 AND 1000000000)
        OR (currency = 'USD' AND exponent = 2 AND amount_minor BETWEEN 1 AND 10000000)),
    CONSTRAINT payroll_compensation_change_date CHECK (
        effective_from BETWEEN DATE '1900-01-01' AND DATE '2999-12-31'),
    CONSTRAINT payroll_compensation_change_shape CHECK (
        (action IN ('ESTABLISHED', 'CHANGED') AND target_change_id IS NULL
            AND reason_code IS NULL AND explanation IS NULL)
        OR (action = 'CORRECTED' AND target_change_id IS NOT NULL AND reason_code IS NOT NULL
            AND explanation IS NOT NULL AND retroactive_acknowledged)
        OR (action = 'SCHEDULED_CHANGE_CANCELLED' AND target_change_id IS NOT NULL
            AND reason_code IS NULL AND explanation IS NULL)),
    CONSTRAINT payroll_compensation_change_reason CHECK (reason_code IS NULL OR reason_code IN
        ('DATA_ENTRY_ERROR', 'WRONG_EFFECTIVE_DATE', 'WRONG_CURRENCY', 'OTHER_CORRECTION')),
    CONSTRAINT payroll_compensation_change_explanation CHECK (
        explanation IS NULL OR payroll.compensation_text_valid(explanation, 10, 500)),
    CONSTRAINT payroll_compensation_change_digest CHECK (preview_digest ~ '^[0-9a-f]{64}$')
);

COMMENT ON TABLE payroll.compensation_change IS
    'MVP-093A append-only monthly compensation command evidence: one increasing sequence per employee, never updated or deleted';

-- Hourly quota counts and the newest-first history page.
CREATE INDEX payroll_compensation_change_actor ON payroll.compensation_change
    (tenant_id, actor_subject, occurred_at);
CREATE INDEX payroll_compensation_change_tenant ON payroll.compensation_change
    (tenant_id, occurred_at);

-- ---------------------------------------------------------------------------------------------
-- Compensation (effective-dated timeline of facts)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE payroll.compensation (
    tenant_id        uuid     NOT NULL,
    employee_id      uuid     NOT NULL,
    id               uuid     NOT NULL,
    -- The change that introduced this value and start (kept when a later change only shortens or
    -- lengthens the end by superseding and re-inserting the fact).
    origin_change_id uuid     NOT NULL,
    created_seq      integer  NOT NULL,
    superseded_seq   integer,
    frequency        text     NOT NULL,
    currency         text     NOT NULL,
    exponent         smallint NOT NULL,
    amount_minor     bigint   NOT NULL,
    effective_from   date     NOT NULL,
    effective_to     date,
    CONSTRAINT payroll_compensation_pkey PRIMARY KEY (tenant_id, employee_id, id),
    CONSTRAINT payroll_compensation_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.organization (id),
    CONSTRAINT payroll_compensation_employee FOREIGN KEY (tenant_id, employee_id)
        REFERENCES people.employee (tenant_id, id),
    CONSTRAINT payroll_compensation_origin FOREIGN KEY (tenant_id, employee_id, origin_change_id)
        REFERENCES payroll.compensation_change (tenant_id, employee_id, id),
    CONSTRAINT payroll_compensation_created FOREIGN KEY (tenant_id, employee_id, created_seq)
        REFERENCES payroll.compensation_change (tenant_id, employee_id, seq),
    CONSTRAINT payroll_compensation_superseded FOREIGN KEY (tenant_id, employee_id, superseded_seq)
        REFERENCES payroll.compensation_change (tenant_id, employee_id, seq),
    CONSTRAINT payroll_compensation_frequency CHECK (frequency = 'MONTHLY'),
    CONSTRAINT payroll_compensation_money CHECK (
        (currency = 'CDF' AND exponent = 0 AND amount_minor BETWEEN 1 AND 1000000000)
        OR (currency = 'USD' AND exponent = 2 AND amount_minor BETWEEN 1 AND 10000000)),
    CONSTRAINT payroll_compensation_dates CHECK (
        effective_from BETWEEN DATE '1900-01-01' AND DATE '2999-12-31'
        AND (effective_to IS NULL
            OR (effective_to >= effective_from AND effective_to <= DATE '2999-12-31'))),
    CONSTRAINT payroll_compensation_seq CHECK (
        created_seq >= 1 AND (superseded_seq IS NULL OR superseded_seq > created_seq)),
    -- At most one effective monthly compensation fact on any business date (active facts only).
    CONSTRAINT payroll_compensation_no_overlap EXCLUDE USING gist (
        tenant_id WITH =, employee_id WITH =,
        daterange(effective_from, effective_to, '[]') WITH &&) WHERE (superseded_seq IS NULL)
);

COMMENT ON TABLE payroll.compensation IS
    'MVP-093A transaction-time timeline of monthly compensation facts; only supersession ever updates a row; never deleted';

CREATE INDEX payroll_compensation_active ON payroll.compensation
    (tenant_id, employee_id, effective_from) WHERE superseded_seq IS NULL;
CREATE INDEX payroll_compensation_origin ON payroll.compensation
    (tenant_id, employee_id, origin_change_id);

-- ---------------------------------------------------------------------------------------------
-- Guards
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION payroll.compensation_change_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    previous payroll.compensation_change%ROWTYPE;
    target payroll.compensation_change%ROWTYPE;
BEGIN
    -- One command per employee at a time keeps the sequence and the limit exact.
    PERFORM pg_advisory_xact_lock(hashtextextended(
        'payroll.compensation:' || NEW.tenant_id::text || ':' || NEW.employee_id::text, 0));
    SELECT * INTO previous FROM payroll.compensation_change
        WHERE tenant_id = NEW.tenant_id AND employee_id = NEW.employee_id
        ORDER BY seq DESC LIMIT 1;
    IF NEW.seq <> COALESCE(previous.seq, 0) + 1 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_compensation_change_sequence',
            MESSAGE = 'compensation changes follow one increasing sequence per employee';
    END IF;
    IF NEW.seq > 100 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_compensation_change_limit',
            MESSAGE = 'at most 100 compensation changes per employee';
    END IF;
    IF previous.seq IS NULL AND NEW.action <> 'ESTABLISHED' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_compensation_change_first',
            MESSAGE = 'the first compensation change is the establishment';
    END IF;
    IF previous.seq IS NOT NULL AND NEW.action = 'ESTABLISHED' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_compensation_change_first',
            MESSAGE = 'the establishment is the first and only one';
    END IF;
    IF previous.seq IS NOT NULL AND NEW.occurred_at < previous.occurred_at THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_compensation_change_time',
            MESSAGE = 'compensation changes are recorded in time order';
    END IF;
    IF NEW.target_change_id IS NOT NULL THEN
        SELECT * INTO target FROM payroll.compensation_change
            WHERE tenant_id = NEW.tenant_id AND employee_id = NEW.employee_id AND id = NEW.target_change_id;
        IF target.seq IS NULL OR target.seq >= NEW.seq
            OR (NEW.action = 'CORRECTED' AND target.action = 'SCHEDULED_CHANGE_CANCELLED')
            OR (NEW.action = 'SCHEDULED_CHANGE_CANCELLED' AND target.action <> 'CHANGED') THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_compensation_change_target',
                MESSAGE = 'a correction acts on an earlier fact change; a cancellation on an earlier scheduled change';
        END IF;
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER payroll_compensation_change_guard BEFORE INSERT ON payroll.compensation_change
    FOR EACH ROW EXECUTE FUNCTION payroll.compensation_change_guard();
CREATE TRIGGER payroll_compensation_change_immutable BEFORE UPDATE OR DELETE ON payroll.compensation_change
    FOR EACH ROW EXECUTE FUNCTION payroll.compensation_immutable();
CREATE TRIGGER payroll_compensation_change_no_truncate BEFORE TRUNCATE ON payroll.compensation_change
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.compensation_immutable();

CREATE FUNCTION payroll.compensation_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    latest integer;
BEGIN
    SELECT max(seq) INTO latest FROM payroll.compensation_change
        WHERE tenant_id = NEW.tenant_id AND employee_id = NEW.employee_id;
    IF TG_OP = 'INSERT' THEN
        -- A fact is always written by the latest change of its employee, in the same transaction.
        IF latest IS NULL OR NEW.created_seq <> latest OR NEW.superseded_seq IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_compensation_creation',
                MESSAGE = 'a compensation fact is created active by the latest change of its employee';
        END IF;
        RETURN NEW;
    END IF;
    -- UPDATE: the sole legal update supersedes an active fact by the latest change, nothing else moves.
    IF OLD.superseded_seq IS NOT NULL OR NEW.superseded_seq IS NULL OR NEW.superseded_seq <> latest
        OR NEW.tenant_id <> OLD.tenant_id OR NEW.employee_id <> OLD.employee_id OR NEW.id <> OLD.id
        OR NEW.origin_change_id <> OLD.origin_change_id OR NEW.created_seq <> OLD.created_seq
        OR NEW.frequency <> OLD.frequency OR NEW.currency <> OLD.currency OR NEW.exponent <> OLD.exponent
        OR NEW.amount_minor <> OLD.amount_minor OR NEW.effective_from <> OLD.effective_from
        OR NEW.effective_to IS DISTINCT FROM OLD.effective_to THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_compensation_supersession',
            MESSAGE = 'a compensation fact is only ever superseded, once, by the latest change';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER payroll_compensation_guard BEFORE INSERT OR UPDATE ON payroll.compensation
    FOR EACH ROW EXECUTE FUNCTION payroll.compensation_guard();
CREATE TRIGGER payroll_compensation_no_delete BEFORE DELETE ON payroll.compensation
    FOR EACH ROW EXECUTE FUNCTION payroll.compensation_immutable();
CREATE TRIGGER payroll_compensation_no_truncate BEFORE TRUNCATE ON payroll.compensation
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.compensation_immutable();

-- Deferred exact match (at commit, whatever the statement order): the active timeline is legal and
-- equals the latest committed change.
CREATE FUNCTION payroll.compensation_exact() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    last_change payroll.compensation_change%ROWTYPE;
    active integer;
    broken integer;
    fact payroll.compensation%ROWTYPE;
    remaining integer;
BEGIN
    SELECT * INTO last_change FROM payroll.compensation_change
        WHERE tenant_id = NEW.tenant_id AND employee_id = NEW.employee_id
        ORDER BY seq DESC LIMIT 1;
    IF last_change.seq IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_compensation_exact',
            MESSAGE = 'compensation facts need a change';
    END IF;
    -- Gap-free after the first, only the last open-ended.
    SELECT count(*) INTO active FROM payroll.compensation
        WHERE tenant_id = NEW.tenant_id AND employee_id = NEW.employee_id AND superseded_seq IS NULL;
    SELECT count(*) INTO broken FROM (
        SELECT effective_to, effective_from,
               lead(effective_from) OVER (ORDER BY effective_from) AS next_from
        FROM payroll.compensation
        WHERE tenant_id = NEW.tenant_id AND employee_id = NEW.employee_id AND superseded_seq IS NULL) t
        WHERE (next_from IS NOT NULL AND effective_to IS DISTINCT FROM next_from - 1)
           OR (next_from IS NULL AND effective_to IS NOT NULL);
    IF broken > 0 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_compensation_exact',
            MESSAGE = 'the active compensation timeline must be gap-free after the first fact and end open';
    END IF;
    IF last_change.action IN ('ESTABLISHED', 'CHANGED', 'CORRECTED') THEN
        SELECT * INTO fact FROM payroll.compensation
            WHERE tenant_id = NEW.tenant_id AND employee_id = NEW.employee_id
                AND origin_change_id = last_change.id AND superseded_seq IS NULL;
        IF fact.id IS NULL OR fact.created_seq <> last_change.seq
            OR fact.currency <> last_change.currency OR fact.exponent <> last_change.exponent
            OR fact.amount_minor <> last_change.amount_minor
            OR fact.effective_from <> last_change.effective_from THEN
            -- A correction that moves nothing else still writes its own fact; any other mismatch is illegal.
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_compensation_exact',
                MESSAGE = 'the active timeline must carry the value and start of the latest change';
        END IF;
        IF last_change.action = 'CORRECTED' THEN
            SELECT count(*) INTO remaining FROM payroll.compensation
                WHERE tenant_id = NEW.tenant_id AND employee_id = NEW.employee_id
                    AND origin_change_id = last_change.target_change_id AND superseded_seq IS NULL;
            IF remaining <> 0 THEN
                RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_compensation_exact',
                    MESSAGE = 'a corrected fact is superseded by its correction';
            END IF;
        END IF;
    ELSE
        -- SCHEDULED_CHANGE_CANCELLED: the cancelled fact is no longer active.
        SELECT count(*) INTO remaining FROM payroll.compensation
            WHERE tenant_id = NEW.tenant_id AND employee_id = NEW.employee_id
                AND origin_change_id = last_change.target_change_id AND superseded_seq IS NULL;
        IF remaining <> 0 THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_compensation_exact',
                MESSAGE = 'a cancelled scheduled change must no longer be active';
        END IF;
    END IF;
    -- Every superseded fact was superseded by a change that exists (foreign key) and the first fact
    -- always exists once any change does.
    IF active = 0 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_compensation_exact',
            MESSAGE = 'an employee with a compensation change always has an active fact';
    END IF;
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER payroll_compensation_exact AFTER INSERT OR UPDATE ON payroll.compensation
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION payroll.compensation_exact();

-- A change is judged with its facts at commit: it must have produced or retired facts.
CREATE FUNCTION payroll.compensation_change_exact() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    PERFORM 1 FROM payroll.compensation
        WHERE tenant_id = NEW.tenant_id AND employee_id = NEW.employee_id
            AND (created_seq = NEW.seq OR superseded_seq = NEW.seq);
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_compensation_change_exact',
            MESSAGE = 'a compensation change must be reflected by the timeline';
    END IF;
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER payroll_compensation_change_exact AFTER INSERT ON payroll.compensation_change
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION payroll.compensation_change_exact();
