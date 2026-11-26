-- =============================================================================================
-- V58 (LAUNCH-001, Issue #226): privacy governance, consent retention and employee data rights for MVP-120F
-- partner-referral data. A FRAMEWORK, not legal advice or certification: it ships NO retention duration. Every
-- country-specific value is entered by an operator and stays DRAFT until the governance checklist is complete.
--
-- Invariants enforced here, not only in the application:
--   * policy versions follow DRAFT -> APPROVED -> SCHEDULED/ACTIVE -> RETIRED, append-only history, an approved, active
--     or retired version is never edited, one ACTIVE version per country and effective period, exactly four rules (one
--     per closed data class) with no orphaning combination;
--   * every referral carries a stamp: POLICY_PENDING (no deadline invented) or STAMPED with the immutable rules of the
--     ACTIVE version that applied at creation; existing referrals are backfilled POLICY_PENDING; a stamp changes only
--     from POLICY_PENDING to STAMPED through an authorized policy application;
--   * holds and releases are append-only; while any hold is active nothing is minimized or erased;
--   * data-rights requests follow a closed lifecycle with append-only history, owned by the submitting employee;
--   * a purge item is accepted only when the stamped rule, the closed referral, the elapsed duration and the absence of
--     any hold allow it; the V57 evidence tables accept a DELETE only inside an executing purge that holds a matching
--     item; every purge leaves immutable, value-free evidence; no background process purges anything.
-- Lock order: partner_referral row first (as in V57), then privacy rows.
-- Rollback: db/rollback/V58__rollback.sql, which refuses once any LAUNCH-001 evidence exists and restores V57 exactly.
-- =============================================================================================

CREATE FUNCTION integrations.privacy_immutable() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_privacy_immutable',
        MESSAGE = 'privacy governance evidence is append-only: it is never updated, deleted or truncated';
END
$fn$;

-- ---------------------------------------------------------------------------------------------
-- Policy versions
-- ---------------------------------------------------------------------------------------------
CREATE TABLE integrations.privacy_policy (
    id               uuid        NOT NULL,
    reference        text        NOT NULL,
    country_code     char(2)     NOT NULL,
    state            text        NOT NULL,
    row_version      integer     NOT NULL,
    wording_version  text        NOT NULL,
    source_reference text        NOT NULL,
    review_date      date        NOT NULL,
    effective_from   date        NOT NULL,
    effective_to     date,
    created_by       text        NOT NULL,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    CONSTRAINT integrations_privacy_policy_pkey PRIMARY KEY (id),
    CONSTRAINT integrations_privacy_policy_reference_key UNIQUE (reference),
    CONSTRAINT integrations_privacy_policy_reference CHECK (reference ~ '^PL-[0-9A-HJKMNP-TV-Z]{16}$'),
    CONSTRAINT integrations_privacy_policy_country CHECK (country_code ~ '^[A-Z]{2}$'),
    CONSTRAINT integrations_privacy_policy_state CHECK (state IN ('DRAFT', 'APPROVED', 'SCHEDULED', 'ACTIVE', 'RETIRED')),
    CONSTRAINT integrations_privacy_policy_version CHECK (row_version >= 1),
    CONSTRAINT integrations_privacy_policy_wording CHECK (wording_version ~ '^[A-Za-z0-9._-]{1,40}$'),
    CONSTRAINT integrations_privacy_policy_source CHECK (char_length(source_reference) BETWEEN 3 AND 300),
    CONSTRAINT integrations_privacy_policy_period CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT integrations_privacy_policy_actor CHECK (char_length(created_by) BETWEEN 1 AND 255)
);
CREATE INDEX integrations_privacy_policy_page ON integrations.privacy_policy (created_at DESC, id DESC);
CREATE INDEX integrations_privacy_policy_active ON integrations.privacy_policy (country_code) WHERE state = 'ACTIVE';

CREATE TABLE integrations.privacy_policy_rule (
    policy_id     uuid    NOT NULL,
    data_class    text    NOT NULL,
    purpose       text    NOT NULL,
    action        text    NOT NULL,
    trigger_event text    NOT NULL,
    duration_days integer,
    CONSTRAINT integrations_privacy_policy_rule_pkey PRIMARY KEY (policy_id, data_class),
    CONSTRAINT integrations_privacy_policy_rule_policy FOREIGN KEY (policy_id) REFERENCES integrations.privacy_policy (id),
    CONSTRAINT integrations_privacy_policy_rule_pairing CHECK (
        (data_class, purpose) IN (
            ('PARTNER_REFERRAL_DISCLOSURE', 'CONSENTED_INTRODUCTION'),
            ('PARTNER_REFERRAL_CONSENT', 'CONSENT_EVIDENCE'),
            ('PARTNER_REFERRAL_DELIVERY_DIAGNOSTICS', 'DELIVERY_TRUTH'),
            ('PARTNER_REFERRAL_LIFECYCLE_EVIDENCE', 'LIFECYCLE_EVIDENCE'))),
    CONSTRAINT integrations_privacy_policy_rule_action CHECK (action IN ('HOLD', 'MINIMIZE', 'ERASE')),
    CONSTRAINT integrations_privacy_policy_rule_trigger CHECK (trigger_event IN ('REFERRAL_CREATED', 'REFERRAL_CLOSED')),
    CONSTRAINT integrations_privacy_policy_rule_duration CHECK (
        (action = 'HOLD') = (duration_days IS NULL) AND (duration_days IS NULL OR duration_days BETWEEN 1 AND 36500))
);

CREATE TABLE integrations.privacy_policy_checklist (
    policy_id                         uuid    NOT NULL,
    jurisdiction_identified           boolean NOT NULL,
    source_reference_recorded         boolean NOT NULL,
    counsel_review_recorded           boolean NOT NULL,
    data_classes_covered              boolean NOT NULL,
    lifecycle_trigger_defined         boolean NOT NULL,
    durations_or_hold_set             boolean NOT NULL,
    employee_wording_version_recorded boolean NOT NULL,
    rollback_review_complete          boolean NOT NULL,
    review_reference                  text,
    updated_at                        timestamptz NOT NULL,
    CONSTRAINT integrations_privacy_policy_checklist_pkey PRIMARY KEY (policy_id),
    CONSTRAINT integrations_privacy_policy_checklist_policy FOREIGN KEY (policy_id) REFERENCES integrations.privacy_policy (id),
    CONSTRAINT integrations_privacy_policy_checklist_reference CHECK (
        review_reference IS NULL OR char_length(review_reference) BETWEEN 1 AND 120)
);

CREATE TABLE integrations.privacy_policy_event (
    policy_id   uuid        NOT NULL,
    sequence    smallint    NOT NULL,
    from_state  text,
    to_state    text        NOT NULL,
    occurred_at timestamptz NOT NULL,
    CONSTRAINT integrations_privacy_policy_event_pkey PRIMARY KEY (policy_id, sequence),
    CONSTRAINT integrations_privacy_policy_event_policy FOREIGN KEY (policy_id) REFERENCES integrations.privacy_policy (id),
    CONSTRAINT integrations_privacy_policy_event_sequence CHECK (sequence BETWEEN 1 AND 64 AND (sequence = 1) = (from_state IS NULL)),
    CONSTRAINT integrations_privacy_policy_event_transition CHECK (
        (from_state IS NULL AND to_state = 'DRAFT')
        OR (from_state = 'DRAFT' AND to_state IN ('APPROVED', 'RETIRED'))
        OR (from_state = 'APPROVED' AND to_state IN ('SCHEDULED', 'ACTIVE', 'RETIRED'))
        OR (from_state = 'SCHEDULED' AND to_state IN ('ACTIVE', 'RETIRED'))
        OR (from_state = 'ACTIVE' AND to_state = 'RETIRED'))
);

-- Rules and events are insert-only; the checklist changes only while the version is DRAFT and is never deleted.
CREATE TRIGGER integrations_privacy_policy_rule_immutable BEFORE UPDATE OR DELETE ON integrations.privacy_policy_rule
    FOR EACH ROW EXECUTE FUNCTION integrations.privacy_immutable();
CREATE TRIGGER integrations_privacy_policy_rule_no_truncate BEFORE TRUNCATE ON integrations.privacy_policy_rule
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.privacy_immutable();
CREATE TRIGGER integrations_privacy_policy_event_immutable BEFORE UPDATE OR DELETE ON integrations.privacy_policy_event
    FOR EACH ROW EXECUTE FUNCTION integrations.privacy_immutable();
CREATE TRIGGER integrations_privacy_policy_event_no_truncate BEFORE TRUNCATE ON integrations.privacy_policy_event
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.privacy_immutable();
CREATE TRIGGER integrations_privacy_policy_no_delete BEFORE DELETE ON integrations.privacy_policy
    FOR EACH ROW EXECUTE FUNCTION integrations.privacy_immutable();
CREATE TRIGGER integrations_privacy_policy_no_truncate BEFORE TRUNCATE ON integrations.privacy_policy
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.privacy_immutable();
CREATE TRIGGER integrations_privacy_policy_checklist_no_delete BEFORE DELETE ON integrations.privacy_policy_checklist
    FOR EACH ROW EXECUTE FUNCTION integrations.privacy_immutable();
CREATE TRIGGER integrations_privacy_policy_checklist_no_truncate BEFORE TRUNCATE ON integrations.privacy_policy_checklist
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.privacy_immutable();

-- The checklist is editable only while its version is DRAFT: afterwards it is the immutable proof of activation.
CREATE FUNCTION integrations.privacy_policy_checklist_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    v_state text;
BEGIN
    SELECT p.state INTO v_state FROM integrations.privacy_policy p WHERE p.id = NEW.policy_id FOR UPDATE;
    IF TG_OP = 'INSERT' THEN
        IF v_state IS DISTINCT FROM 'DRAFT' THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_privacy_policy_checklist_draft',
                MESSAGE = 'the checklist is recorded with a DRAFT version';
        END IF;
        RETURN NEW;
    END IF;
    IF v_state IS DISTINCT FROM 'DRAFT' OR NEW.policy_id <> OLD.policy_id THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_privacy_policy_checklist_draft',
            MESSAGE = 'the checklist of a version that left DRAFT is immutable';
    END IF;
    RETURN NEW;
END
$fn$;
CREATE TRIGGER integrations_privacy_policy_checklist_guard BEFORE INSERT OR UPDATE ON integrations.privacy_policy_checklist
    FOR EACH ROW EXECUTE FUNCTION integrations.privacy_policy_checklist_guard();

-- Policy guard: closed transitions with their conditions; the period overlap check is serialised per country.
CREATE FUNCTION integrations.privacy_policy_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    v_today date;
    v_ok    boolean;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.state <> 'DRAFT' OR NEW.row_version <> 1 THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_privacy_policy_transition',
                MESSAGE = 'a policy version is recorded as DRAFT, version 1';
        END IF;
        RETURN NEW;
    END IF;
    IF (to_jsonb(NEW) - 'state' - 'row_version' - 'updated_at') IS DISTINCT FROM
       (to_jsonb(OLD) - 'state' - 'row_version' - 'updated_at') OR NEW.row_version <> OLD.row_version + 1 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_privacy_policy_immutable',
            MESSAGE = 'a policy version only moves its status, one version at a time';
    END IF;
    IF NEW.state = OLD.state THEN
        IF OLD.state <> 'DRAFT' THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_privacy_policy_immutable',
                MESSAGE = 'an approved, scheduled, active or retired version is never edited';
        END IF;
        RETURN NEW;
    END IF;
    v_today := (NEW.updated_at AT TIME ZONE 'UTC')::date;
    IF NEW.state = 'APPROVED' THEN
        SELECT c.jurisdiction_identified AND c.source_reference_recorded AND c.counsel_review_recorded
               AND c.data_classes_covered AND c.lifecycle_trigger_defined AND c.durations_or_hold_set
               AND c.employee_wording_version_recorded AND c.rollback_review_complete
               AND c.review_reference IS NOT NULL AND btrim(c.review_reference) <> ''
          INTO v_ok FROM integrations.privacy_policy_checklist c WHERE c.policy_id = NEW.id;
        IF v_ok IS DISTINCT FROM TRUE OR OLD.state <> 'DRAFT'
            OR (SELECT count(*) FROM integrations.privacy_policy_rule r WHERE r.policy_id = NEW.id) <> 4 THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_privacy_policy_checklist',
                MESSAGE = 'approval needs the complete governance checklist and the four rules';
        END IF;
    ELSIF NEW.state = 'SCHEDULED' THEN
        IF OLD.state <> 'APPROVED' OR NEW.effective_from <= v_today THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_privacy_policy_transition',
                MESSAGE = 'a version is scheduled only from APPROVED, for a future effective date';
        END IF;
    ELSIF NEW.state = 'ACTIVE' THEN
        PERFORM pg_advisory_xact_lock(hashtextextended('privacy_policy:' || NEW.country_code, 0));
        IF OLD.state NOT IN ('APPROVED', 'SCHEDULED') OR NEW.effective_from > v_today
            OR (NEW.effective_to IS NOT NULL AND NEW.effective_to < v_today) THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_privacy_policy_transition',
                MESSAGE = 'a version activates only from APPROVED or SCHEDULED, inside its effective period';
        END IF;
        IF EXISTS (SELECT 1 FROM integrations.privacy_policy o
                    WHERE o.state = 'ACTIVE' AND o.country_code = NEW.country_code AND o.id <> NEW.id
                      AND daterange(o.effective_from, coalesce(o.effective_to, 'infinity'::date), '[]')
                          && daterange(NEW.effective_from, coalesce(NEW.effective_to, 'infinity'::date), '[]')) THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_privacy_policy_overlap',
                MESSAGE = 'only one active version applies to a country and an effective period';
        END IF;
    ELSIF NEW.state = 'RETIRED' THEN
        NULL;
    ELSE
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_privacy_policy_transition',
            MESSAGE = 'a version never returns to DRAFT';
    END IF;
    RETURN NEW;
END
$fn$;
CREATE TRIGGER integrations_privacy_policy_guard BEFORE INSERT OR UPDATE ON integrations.privacy_policy
    FOR EACH ROW EXECUTE FUNCTION integrations.privacy_policy_guard();

CREATE FUNCTION integrations.privacy_policy_event_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    last_sequence integer;
    last_state    text;
    last_at       timestamptz;
BEGIN
    PERFORM 1 FROM integrations.privacy_policy p WHERE p.id = NEW.policy_id FOR UPDATE;
    SELECT e.sequence, e.to_state, e.occurred_at INTO last_sequence, last_state, last_at
      FROM integrations.privacy_policy_event e WHERE e.policy_id = NEW.policy_id ORDER BY e.sequence DESC LIMIT 1;
    IF NEW.sequence <> coalesce(last_sequence, 0) + 1 OR NEW.from_state IS DISTINCT FROM last_state
        OR (last_at IS NOT NULL AND NEW.occurred_at < last_at) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_privacy_policy_event_transition',
            MESSAGE = 'the policy event does not follow the policy history';
    END IF;
    RETURN NEW;
END
$fn$;
CREATE TRIGGER integrations_privacy_policy_event_guard BEFORE INSERT ON integrations.privacy_policy_event
    FOR EACH ROW EXECUTE FUNCTION integrations.privacy_policy_event_guard();

-- Completeness at commit: four rules (one per data class), a checklist, the current state equal to the last event,
-- and no orphaning rule combination (an ERASE of the consent needs the disclosure and the delivery diagnostics to be
-- minimized or erased no later than the consent, from the same lifecycle trigger).
CREATE FUNCTION integrations.privacy_policy_complete() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    p          integrations.privacy_policy%ROWTYPE;
    n_rules    integer;
    last_state text;
    d          integrations.privacy_policy_rule%ROWTYPE;
    c          integrations.privacy_policy_rule%ROWTYPE;
    g          integrations.privacy_policy_rule%ROWTYPE;
    l          integrations.privacy_policy_rule%ROWTYPE;
BEGIN
    IF TG_TABLE_NAME = 'privacy_policy' THEN
        p := NEW;
    ELSE
        SELECT * INTO p FROM integrations.privacy_policy x WHERE x.id = NEW.policy_id;
        IF NOT FOUND THEN
            RETURN NULL;
        END IF;
    END IF;
    SELECT count(*) INTO n_rules FROM integrations.privacy_policy_rule r WHERE r.policy_id = p.id;
    SELECT (array_agg(e.to_state ORDER BY e.sequence DESC))[1] INTO last_state
      FROM integrations.privacy_policy_event e WHERE e.policy_id = p.id;
    IF n_rules <> 4 OR last_state IS DISTINCT FROM p.state
        OR NOT EXISTS (SELECT 1 FROM integrations.privacy_policy_checklist k WHERE k.policy_id = p.id) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_privacy_policy_complete',
            MESSAGE = 'a policy version carries its four rules, its checklist and its history exactly';
    END IF;
    SELECT * INTO d FROM integrations.privacy_policy_rule r WHERE r.policy_id = p.id AND r.data_class = 'PARTNER_REFERRAL_DISCLOSURE';
    SELECT * INTO c FROM integrations.privacy_policy_rule r WHERE r.policy_id = p.id AND r.data_class = 'PARTNER_REFERRAL_CONSENT';
    SELECT * INTO g FROM integrations.privacy_policy_rule r WHERE r.policy_id = p.id AND r.data_class = 'PARTNER_REFERRAL_DELIVERY_DIAGNOSTICS';
    SELECT * INTO l FROM integrations.privacy_policy_rule r WHERE r.policy_id = p.id AND r.data_class = 'PARTNER_REFERRAL_LIFECYCLE_EVIDENCE';
    -- R227-1: the lifecycle evidence is minimized or erased last: every other class must be purged no later than it.
    IF l.action <> 'HOLD' AND (
           d.action = 'HOLD' OR c.action = 'HOLD' OR g.action = 'HOLD'
        OR d.trigger_event <> l.trigger_event OR c.trigger_event <> l.trigger_event OR g.trigger_event <> l.trigger_event
        OR d.duration_days > l.duration_days OR c.duration_days > l.duration_days OR g.duration_days > l.duration_days) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_privacy_policy_rules',
            MESSAGE = 'the rules would purge the lifecycle evidence while other referral data outlives it';
    END IF;
    IF c.action = 'ERASE' AND (
           d.action = 'HOLD' OR g.action = 'HOLD'
        OR d.trigger_event <> c.trigger_event OR g.trigger_event <> c.trigger_event
        OR d.duration_days > c.duration_days OR g.duration_days > c.duration_days) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_privacy_policy_rules',
            MESSAGE = 'the rules would erase the consent while its disclosure or delivery evidence outlives it';
    END IF;
    RETURN NULL;
END
$fn$;
CREATE CONSTRAINT TRIGGER integrations_privacy_policy_complete
    AFTER INSERT OR UPDATE ON integrations.privacy_policy
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION integrations.privacy_policy_complete();
CREATE CONSTRAINT TRIGGER integrations_privacy_policy_rule_complete
    AFTER INSERT ON integrations.privacy_policy_rule
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION integrations.privacy_policy_complete();
CREATE CONSTRAINT TRIGGER integrations_privacy_policy_event_complete
    AFTER INSERT ON integrations.privacy_policy_event
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION integrations.privacy_policy_complete();

-- ---------------------------------------------------------------------------------------------
-- Previews (single use, 15 minutes) and the two governed operations that consume them
-- ---------------------------------------------------------------------------------------------
CREATE TABLE integrations.privacy_preview (
    id          uuid        NOT NULL,
    reference   text        NOT NULL,
    kind        text        NOT NULL,
    actor       text        NOT NULL,
    fingerprint char(64)    NOT NULL,
    state       text        NOT NULL,
    created_at  timestamptz NOT NULL,
    expires_at  timestamptz NOT NULL,
    used_at     timestamptz,
    action      text,
    country_code char(2),
    CONSTRAINT integrations_privacy_preview_scope CHECK (
        (kind = 'PURGE') = (action IS NOT NULL)
        AND (action IS NULL OR action IN ('MINIMIZE', 'ERASE'))
        AND (country_code IS NULL OR country_code ~ '^[A-Z]{2}$')
        AND (kind = 'POLICY_APPLICATION' OR country_code IS NULL)),
    CONSTRAINT integrations_privacy_preview_pkey PRIMARY KEY (id),
    CONSTRAINT integrations_privacy_preview_reference_key UNIQUE (reference),
    CONSTRAINT integrations_privacy_preview_reference CHECK (reference ~ '^PV-[0-9A-HJKMNP-TV-Z]{16}$'),
    CONSTRAINT integrations_privacy_preview_kind CHECK (kind IN ('PURGE', 'POLICY_APPLICATION')),
    CONSTRAINT integrations_privacy_preview_actor CHECK (char_length(actor) BETWEEN 1 AND 255),
    CONSTRAINT integrations_privacy_preview_fingerprint CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT integrations_privacy_preview_state CHECK (state IN ('OPEN', 'USED')),
    CONSTRAINT integrations_privacy_preview_window CHECK (expires_at = created_at + interval '15 minutes'),
    CONSTRAINT integrations_privacy_preview_used CHECK ((state = 'USED') = (used_at IS NOT NULL))
);

CREATE FUNCTION integrations.privacy_preview_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.state <> 'OPEN' THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_privacy_preview_state',
                MESSAGE = 'a preview is recorded OPEN';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.state <> 'OPEN' OR NEW.state <> 'USED' OR NEW.used_at > OLD.expires_at
        OR (to_jsonb(NEW) - 'state' - 'used_at') IS DISTINCT FROM (to_jsonb(OLD) - 'state' - 'used_at') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_privacy_preview_used',
            MESSAGE = 'a preview is used once, before it expires, and never otherwise changes';
    END IF;
    RETURN NEW;
END
$fn$;
-- The referrals a purge preview covers: the commit revalidates exactly this scope, never a client-supplied one.
CREATE TABLE integrations.privacy_preview_item (
    preview_id  uuid NOT NULL,
    tenant_id   uuid NOT NULL,
    referral_id uuid NOT NULL,
    CONSTRAINT integrations_privacy_preview_item_pkey PRIMARY KEY (preview_id, tenant_id, referral_id),
    CONSTRAINT integrations_privacy_preview_item_preview FOREIGN KEY (preview_id) REFERENCES integrations.privacy_preview (id),
    CONSTRAINT integrations_privacy_preview_item_referral FOREIGN KEY (tenant_id, referral_id)
        REFERENCES integrations.partner_referral (tenant_id, id)
);
CREATE TRIGGER integrations_privacy_preview_item_immutable BEFORE UPDATE OR DELETE ON integrations.privacy_preview_item
    FOR EACH ROW EXECUTE FUNCTION integrations.privacy_immutable();
CREATE TRIGGER integrations_privacy_preview_item_no_truncate BEFORE TRUNCATE ON integrations.privacy_preview_item
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.privacy_immutable();

CREATE TRIGGER integrations_privacy_preview_guard BEFORE INSERT OR UPDATE ON integrations.privacy_preview
    FOR EACH ROW EXECUTE FUNCTION integrations.privacy_preview_guard();
CREATE TRIGGER integrations_privacy_preview_no_delete BEFORE DELETE ON integrations.privacy_preview
    FOR EACH ROW EXECUTE FUNCTION integrations.privacy_immutable();
CREATE TRIGGER integrations_privacy_preview_no_truncate BEFORE TRUNCATE ON integrations.privacy_preview
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.privacy_immutable();

CREATE TABLE integrations.privacy_policy_application (
    id            uuid        NOT NULL,
    reference     text        NOT NULL,
    policy_id     uuid        NOT NULL,
    preview_id    uuid        NOT NULL,
    actor         text        NOT NULL,
    stamped_count integer     NOT NULL,
    created_at    timestamptz NOT NULL,
    CONSTRAINT integrations_privacy_policy_application_pkey PRIMARY KEY (id),
    CONSTRAINT integrations_privacy_policy_application_reference_key UNIQUE (reference),
    CONSTRAINT integrations_privacy_policy_application_preview_key UNIQUE (preview_id, policy_id),
    CONSTRAINT integrations_privacy_policy_application_policy FOREIGN KEY (policy_id) REFERENCES integrations.privacy_policy (id),
    CONSTRAINT integrations_privacy_policy_application_preview FOREIGN KEY (preview_id) REFERENCES integrations.privacy_preview (id),
    CONSTRAINT integrations_privacy_policy_application_reference CHECK (reference ~ '^PM-[0-9A-HJKMNP-TV-Z]{16}$'),
    CONSTRAINT integrations_privacy_policy_application_actor CHECK (char_length(actor) BETWEEN 1 AND 255),
    CONSTRAINT integrations_privacy_policy_application_count CHECK (stamped_count >= 0)
);

CREATE TABLE integrations.privacy_policy_application_item (
    application_id uuid NOT NULL,
    tenant_id      uuid NOT NULL,
    referral_id    uuid NOT NULL,
    CONSTRAINT integrations_privacy_policy_application_item_pkey PRIMARY KEY (application_id, tenant_id, referral_id),
    CONSTRAINT integrations_privacy_policy_application_item_application FOREIGN KEY (application_id)
        REFERENCES integrations.privacy_policy_application (id),
    CONSTRAINT integrations_privacy_policy_application_item_referral FOREIGN KEY (tenant_id, referral_id)
        REFERENCES integrations.partner_referral (tenant_id, id)
);
CREATE UNIQUE INDEX integrations_privacy_policy_application_item_once
    ON integrations.privacy_policy_application_item (tenant_id, referral_id);

CREATE TABLE integrations.privacy_purge (
    id           uuid        NOT NULL,
    reference    text        NOT NULL,
    preview_id   uuid        NOT NULL,
    actor        text        NOT NULL,
    state        text        NOT NULL,
    item_count   integer,
    created_at   timestamptz NOT NULL,
    completed_at timestamptz,
    CONSTRAINT integrations_privacy_purge_pkey PRIMARY KEY (id),
    CONSTRAINT integrations_privacy_purge_reference_key UNIQUE (reference),
    CONSTRAINT integrations_privacy_purge_preview_key UNIQUE (preview_id),
    CONSTRAINT integrations_privacy_purge_preview FOREIGN KEY (preview_id) REFERENCES integrations.privacy_preview (id),
    CONSTRAINT integrations_privacy_purge_reference CHECK (reference ~ '^PG-[0-9A-HJKMNP-TV-Z]{16}$'),
    CONSTRAINT integrations_privacy_purge_actor CHECK (char_length(actor) BETWEEN 1 AND 255),
    CONSTRAINT integrations_privacy_purge_state CHECK (state IN ('EXECUTING', 'COMPLETED')),
    CONSTRAINT integrations_privacy_purge_done CHECK (
        (state = 'COMPLETED') = (completed_at IS NOT NULL AND item_count IS NOT NULL) AND (item_count IS NULL OR item_count >= 1))
);

CREATE FUNCTION integrations.privacy_purge_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.state <> 'EXECUTING' THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_privacy_purge_state',
                MESSAGE = 'a purge starts EXECUTING';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.state <> 'EXECUTING' OR NEW.state <> 'COMPLETED'
        OR (to_jsonb(NEW) - 'state' - 'item_count' - 'completed_at') IS DISTINCT FROM
           (to_jsonb(OLD) - 'state' - 'item_count' - 'completed_at') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_privacy_purge_state',
            MESSAGE = 'a purge completes once and never otherwise changes';
    END IF;
    RETURN NEW;
END
$fn$;
CREATE TRIGGER integrations_privacy_purge_guard BEFORE INSERT OR UPDATE ON integrations.privacy_purge
    FOR EACH ROW EXECUTE FUNCTION integrations.privacy_purge_guard();
CREATE TRIGGER integrations_privacy_purge_no_delete BEFORE DELETE ON integrations.privacy_purge
    FOR EACH ROW EXECUTE FUNCTION integrations.privacy_immutable();
CREATE TRIGGER integrations_privacy_purge_no_truncate BEFORE TRUNCATE ON integrations.privacy_purge
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.privacy_immutable();
CREATE TRIGGER integrations_privacy_policy_application_immutable
    BEFORE UPDATE OR DELETE ON integrations.privacy_policy_application
    FOR EACH ROW EXECUTE FUNCTION integrations.privacy_immutable();
CREATE TRIGGER integrations_privacy_policy_application_no_truncate BEFORE TRUNCATE ON integrations.privacy_policy_application
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.privacy_immutable();
CREATE TRIGGER integrations_privacy_policy_application_item_immutable
    BEFORE UPDATE OR DELETE ON integrations.privacy_policy_application_item
    FOR EACH ROW EXECUTE FUNCTION integrations.privacy_immutable();
CREATE TRIGGER integrations_privacy_policy_application_item_no_truncate
    BEFORE TRUNCATE ON integrations.privacy_policy_application_item
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.privacy_immutable();

-- ---------------------------------------------------------------------------------------------
-- Referral stamps: POLICY_PENDING (no deadline) or STAMPED with the immutable rules that applied
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION integrations.privacy_rules_digest(p_policy uuid) RETURNS char(64) LANGUAGE sql STABLE AS
$fn$
    SELECT encode(sha256(convert_to(coalesce(string_agg(
        r.data_class || '|' || r.action || '|' || r.trigger_event || '|' || coalesce(r.duration_days::text, '-'),
        ';' ORDER BY r.data_class), ''), 'UTF8')), 'hex')
      FROM integrations.privacy_policy_rule r WHERE r.policy_id = p_policy
$fn$;

CREATE TABLE integrations.privacy_referral_stamp (
    tenant_id    uuid        NOT NULL,
    referral_id  uuid        NOT NULL,
    status       text        NOT NULL,
    policy_id    uuid,
    rules_digest char(64),
    source       text        NOT NULL,
    stamped_at   timestamptz NOT NULL,
    country_code char(2)     NOT NULL,
    CONSTRAINT integrations_privacy_referral_stamp_country CHECK (country_code ~ '^[A-Z]{2}$'),
    CONSTRAINT integrations_privacy_referral_stamp_pkey PRIMARY KEY (tenant_id, referral_id),
    CONSTRAINT integrations_privacy_referral_stamp_referral FOREIGN KEY (tenant_id, referral_id)
        REFERENCES integrations.partner_referral (tenant_id, id),
    CONSTRAINT integrations_privacy_referral_stamp_policy FOREIGN KEY (policy_id) REFERENCES integrations.privacy_policy (id),
    CONSTRAINT integrations_privacy_referral_stamp_status CHECK (status IN ('POLICY_PENDING', 'STAMPED')),
    CONSTRAINT integrations_privacy_referral_stamp_source CHECK (source IN ('MIGRATION', 'CREATION', 'APPLICATION')),
    CONSTRAINT integrations_privacy_referral_stamp_shape CHECK (
        (status = 'STAMPED') = (policy_id IS NOT NULL AND rules_digest IS NOT NULL)
        AND (rules_digest IS NULL OR rules_digest ~ '^[0-9a-f]{64}$')),
    CONSTRAINT integrations_privacy_referral_stamp_source_status CHECK (
        (source = 'MIGRATION' AND status = 'POLICY_PENDING') OR source <> 'MIGRATION')
);

CREATE TABLE integrations.privacy_referral_stamp_rule (
    tenant_id     uuid    NOT NULL,
    referral_id   uuid    NOT NULL,
    data_class    text    NOT NULL,
    action        text    NOT NULL,
    trigger_event text    NOT NULL,
    duration_days integer,
    CONSTRAINT integrations_privacy_referral_stamp_rule_pkey PRIMARY KEY (tenant_id, referral_id, data_class),
    CONSTRAINT integrations_privacy_referral_stamp_rule_stamp FOREIGN KEY (tenant_id, referral_id)
        REFERENCES integrations.privacy_referral_stamp (tenant_id, referral_id),
    CONSTRAINT integrations_privacy_referral_stamp_rule_class CHECK (data_class IN (
        'PARTNER_REFERRAL_DISCLOSURE', 'PARTNER_REFERRAL_CONSENT', 'PARTNER_REFERRAL_DELIVERY_DIAGNOSTICS',
        'PARTNER_REFERRAL_LIFECYCLE_EVIDENCE')),
    CONSTRAINT integrations_privacy_referral_stamp_rule_action CHECK (action IN ('HOLD', 'MINIMIZE', 'ERASE')),
    CONSTRAINT integrations_privacy_referral_stamp_rule_trigger CHECK (trigger_event IN ('REFERRAL_CREATED', 'REFERRAL_CLOSED')),
    CONSTRAINT integrations_privacy_referral_stamp_rule_duration CHECK (
        (action = 'HOLD') = (duration_days IS NULL) AND (duration_days IS NULL OR duration_days BETWEEN 1 AND 36500))
);
CREATE TRIGGER integrations_privacy_referral_stamp_rule_immutable
    BEFORE UPDATE OR DELETE ON integrations.privacy_referral_stamp_rule
    FOR EACH ROW EXECUTE FUNCTION integrations.privacy_immutable();
CREATE TRIGGER integrations_privacy_referral_stamp_rule_no_truncate BEFORE TRUNCATE ON integrations.privacy_referral_stamp_rule
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.privacy_immutable();
CREATE TRIGGER integrations_privacy_referral_stamp_no_delete BEFORE DELETE ON integrations.privacy_referral_stamp
    FOR EACH ROW EXECUTE FUNCTION integrations.privacy_immutable();
CREATE TRIGGER integrations_privacy_referral_stamp_no_truncate BEFORE TRUNCATE ON integrations.privacy_referral_stamp
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.privacy_immutable();

-- Copies the four rules of a version onto a referral's stamp rows.
CREATE FUNCTION integrations.privacy_copy_rules(p_tenant uuid, p_referral uuid, p_policy uuid) RETURNS void
    LANGUAGE sql AS
$fn$
    INSERT INTO integrations.privacy_referral_stamp_rule
        (tenant_id, referral_id, data_class, action, trigger_event, duration_days)
    SELECT p_tenant, p_referral, r.data_class, r.action, r.trigger_event, r.duration_days
      FROM integrations.privacy_policy_rule r WHERE r.policy_id = p_policy
$fn$;

-- Existing referrals are backfilled POLICY_PENDING: no deadline is invented for them.
INSERT INTO integrations.privacy_referral_stamp (tenant_id, referral_id, status, source, stamped_at, country_code)
SELECT r.tenant_id, r.id, 'POLICY_PENDING', 'MIGRATION', now(), o.country_code
  FROM integrations.partner_referral r JOIN tenant.organization o ON o.id = r.tenant_id;

-- A new referral is stamped with the ACTIVE version for its organization's country at creation, or stays pending.
CREATE FUNCTION integrations.privacy_stamp_new_referral() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    v_country char(2);
    v_policy  uuid;
    v_day     date := (NEW.created_at AT TIME ZONE 'UTC')::date;
BEGIN
    SELECT o.country_code INTO v_country FROM tenant.organization o WHERE o.id = NEW.tenant_id;
    SELECT p.id INTO v_policy FROM integrations.privacy_policy p
     WHERE p.state = 'ACTIVE' AND p.country_code = v_country
       AND p.effective_from <= v_day AND (p.effective_to IS NULL OR p.effective_to >= v_day)
     ORDER BY p.effective_from DESC LIMIT 1;
    IF v_policy IS NULL THEN
        INSERT INTO integrations.privacy_referral_stamp (tenant_id, referral_id, status, source, stamped_at, country_code)
        VALUES (NEW.tenant_id, NEW.id, 'POLICY_PENDING', 'CREATION', NEW.created_at, v_country);
    ELSE
        INSERT INTO integrations.privacy_referral_stamp
            (tenant_id, referral_id, status, policy_id, rules_digest, source, stamped_at, country_code)
        VALUES (NEW.tenant_id, NEW.id, 'STAMPED', v_policy, integrations.privacy_rules_digest(v_policy),
                'CREATION', NEW.created_at, v_country);
        PERFORM integrations.privacy_copy_rules(NEW.tenant_id, NEW.id, v_policy);
    END IF;
    RETURN NULL;
END
$fn$;
CREATE TRIGGER integrations_privacy_stamp_new_referral AFTER INSERT ON integrations.partner_referral
    FOR EACH ROW EXECUTE FUNCTION integrations.privacy_stamp_new_referral();

-- Stamp guard: a pending stamp becomes STAMPED once, only through a policy application of an ACTIVE version.
CREATE FUNCTION integrations.privacy_referral_stamp_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    v_app text := current_setting('divalhr.privacy_application', true);
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.source = 'APPLICATION' THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_privacy_referral_stamp_source',
                MESSAGE = 'a stamp is created at referral creation or by the migration';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status <> 'POLICY_PENDING' OR NEW.status <> 'STAMPED' OR NEW.source <> 'APPLICATION'
        OR NEW.tenant_id <> OLD.tenant_id OR NEW.referral_id <> OLD.referral_id
        OR v_app IS NULL OR v_app = ''
        OR NOT EXISTS (SELECT 1 FROM integrations.privacy_policy_application a
                         JOIN integrations.privacy_policy_application_item i ON i.application_id = a.id
                        WHERE a.id = v_app::uuid AND a.policy_id = NEW.policy_id
                          AND i.tenant_id = NEW.tenant_id AND i.referral_id = NEW.referral_id)
        OR NOT EXISTS (SELECT 1 FROM integrations.privacy_policy p WHERE p.id = NEW.policy_id AND p.state = 'ACTIVE')
        OR NEW.rules_digest IS DISTINCT FROM integrations.privacy_rules_digest(NEW.policy_id) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_privacy_referral_stamp_transition',
            MESSAGE = 'a pending stamp changes once, through an application of an active policy version';
    END IF;
    RETURN NEW;
END
$fn$;
CREATE TRIGGER integrations_privacy_referral_stamp_guard BEFORE INSERT OR UPDATE ON integrations.privacy_referral_stamp
    FOR EACH ROW EXECUTE FUNCTION integrations.privacy_referral_stamp_guard();

CREATE FUNCTION integrations.privacy_referral_stamp_complete() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    s       integrations.privacy_referral_stamp%ROWTYPE;
    n_rules integer;
    v_copy  text;
BEGIN
    IF TG_TABLE_NAME = 'privacy_referral_stamp' THEN
        s := NEW;
    ELSE
        SELECT * INTO s FROM integrations.privacy_referral_stamp x WHERE x.tenant_id = NEW.tenant_id AND x.referral_id = NEW.referral_id;
    END IF;
    SELECT count(*), encode(sha256(convert_to(coalesce(string_agg(
            r.data_class || '|' || r.action || '|' || r.trigger_event || '|' || coalesce(r.duration_days::text, '-'),
            ';' ORDER BY r.data_class), ''), 'UTF8')), 'hex')
      INTO n_rules, v_copy
      FROM integrations.privacy_referral_stamp_rule r WHERE r.tenant_id = s.tenant_id AND r.referral_id = s.referral_id;
    IF (s.status = 'POLICY_PENDING' AND n_rules <> 0)
        OR (s.status = 'STAMPED' AND (n_rules <> 4 OR v_copy IS DISTINCT FROM s.rules_digest)) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_privacy_referral_stamp_complete',
            MESSAGE = 'a stamped referral carries exactly the rules of its policy version; a pending one carries none';
    END IF;
    RETURN NULL;
END
$fn$;
CREATE CONSTRAINT TRIGGER integrations_privacy_referral_stamp_complete
    AFTER INSERT OR UPDATE ON integrations.privacy_referral_stamp
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION integrations.privacy_referral_stamp_complete();
CREATE CONSTRAINT TRIGGER integrations_privacy_referral_stamp_rule_complete
    AFTER INSERT ON integrations.privacy_referral_stamp_rule
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION integrations.privacy_referral_stamp_complete();

-- ---------------------------------------------------------------------------------------------
-- Holds: append-only. While any hold is ACTIVE on a referral nothing is minimized or erased.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE integrations.privacy_hold (
    tenant_id       uuid        NOT NULL,
    referral_id     uuid        NOT NULL,
    id              uuid        NOT NULL,
    reference       text        NOT NULL,
    reason          text        NOT NULL,
    note_ciphertext bytea,
    state           text        NOT NULL,
    placed_by       text        NOT NULL,
    placed_at       timestamptz NOT NULL,
    released_by     text,
    released_at     timestamptz,
    review_date     date        NOT NULL,
    external_reference text,
    release_note_ciphertext bytea,
    CONSTRAINT integrations_privacy_hold_external CHECK (
        external_reference IS NULL OR external_reference ~ '^[A-Za-z0-9._/-]{1,80}$'),
    CONSTRAINT integrations_privacy_hold_release_note CHECK (
        release_note_ciphertext IS NULL OR octet_length(release_note_ciphertext) BETWEEN 40 AND 4096),
    CONSTRAINT integrations_privacy_hold_pkey PRIMARY KEY (id),
    CONSTRAINT integrations_privacy_hold_reference_key UNIQUE (reference),
    CONSTRAINT integrations_privacy_hold_referral FOREIGN KEY (tenant_id, referral_id)
        REFERENCES integrations.partner_referral (tenant_id, id),
    CONSTRAINT integrations_privacy_hold_reference CHECK (reference ~ '^PH-[0-9A-HJKMNP-TV-Z]{16}$'),
    CONSTRAINT integrations_privacy_hold_reason CHECK (
        reason IN ('LEGAL_PROCEEDING', 'REGULATORY_REQUEST', 'DISPUTE', 'INVESTIGATION', 'OTHER_DOCUMENTED')),
    CONSTRAINT integrations_privacy_hold_note CHECK (note_ciphertext IS NULL OR octet_length(note_ciphertext) BETWEEN 40 AND 4096),
    CONSTRAINT integrations_privacy_hold_state CHECK (state IN ('ACTIVE', 'RELEASED')),
    CONSTRAINT integrations_privacy_hold_actors CHECK (char_length(placed_by) BETWEEN 1 AND 255
        AND (released_by IS NULL OR char_length(released_by) BETWEEN 1 AND 255)),
    CONSTRAINT integrations_privacy_hold_released CHECK (
        (state = 'RELEASED') = (released_by IS NOT NULL AND released_at IS NOT NULL)
        AND (released_at IS NULL OR released_at >= placed_at)),
    -- OTHER_DOCUMENTED is never accepted without its documented note.
    CONSTRAINT integrations_privacy_hold_other CHECK (reason <> 'OTHER_DOCUMENTED' OR note_ciphertext IS NOT NULL)
);
CREATE INDEX integrations_privacy_hold_referral_idx ON integrations.privacy_hold (tenant_id, referral_id, state);

CREATE TABLE integrations.privacy_hold_event (
    hold_id     uuid        NOT NULL,
    sequence    smallint    NOT NULL,
    kind        text        NOT NULL,
    occurred_at timestamptz NOT NULL,
    CONSTRAINT integrations_privacy_hold_event_pkey PRIMARY KEY (hold_id, sequence),
    CONSTRAINT integrations_privacy_hold_event_hold FOREIGN KEY (hold_id) REFERENCES integrations.privacy_hold (id),
    CONSTRAINT integrations_privacy_hold_event_kind CHECK (
        (sequence = 1 AND kind = 'PLACED') OR (sequence = 2 AND kind = 'RELEASED'))
);
CREATE TRIGGER integrations_privacy_hold_event_immutable BEFORE UPDATE OR DELETE ON integrations.privacy_hold_event
    FOR EACH ROW EXECUTE FUNCTION integrations.privacy_immutable();
CREATE TRIGGER integrations_privacy_hold_event_no_truncate BEFORE TRUNCATE ON integrations.privacy_hold_event
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.privacy_immutable();
CREATE TRIGGER integrations_privacy_hold_no_delete BEFORE DELETE ON integrations.privacy_hold
    FOR EACH ROW EXECUTE FUNCTION integrations.privacy_immutable();
CREATE TRIGGER integrations_privacy_hold_no_truncate BEFORE TRUNCATE ON integrations.privacy_hold
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.privacy_immutable();

CREATE FUNCTION integrations.privacy_hold_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    -- The referral row is locked first, as in V57, so a hold and a purge item serialise on the same referral.
    PERFORM 1 FROM integrations.partner_referral r WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.referral_id FOR UPDATE;
    IF TG_OP = 'INSERT' THEN
        IF NEW.state <> 'ACTIVE' THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_privacy_hold_state',
                MESSAGE = 'a hold is placed ACTIVE';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.state <> 'ACTIVE' OR NEW.state <> 'RELEASED'
        OR (to_jsonb(NEW) - 'state' - 'released_by' - 'released_at' - 'release_note_ciphertext') IS DISTINCT FROM
           (to_jsonb(OLD) - 'state' - 'released_by' - 'released_at' - 'release_note_ciphertext') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_privacy_hold_state',
            MESSAGE = 'a hold is released once and never otherwise changes';
    END IF;
    RETURN NEW;
END
$fn$;
CREATE TRIGGER integrations_privacy_hold_guard BEFORE INSERT OR UPDATE ON integrations.privacy_hold
    FOR EACH ROW EXECUTE FUNCTION integrations.privacy_hold_guard();

CREATE FUNCTION integrations.privacy_hold_complete() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    h        integrations.privacy_hold%ROWTYPE;
    n_events integer;
BEGIN
    IF TG_TABLE_NAME = 'privacy_hold' THEN
        h := NEW;
    ELSE
        SELECT * INTO h FROM integrations.privacy_hold x WHERE x.id = NEW.hold_id;
    END IF;
    SELECT count(*) INTO n_events FROM integrations.privacy_hold_event e WHERE e.hold_id = h.id;
    IF n_events <> (CASE WHEN h.state = 'RELEASED' THEN 2 ELSE 1 END) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_privacy_hold_complete',
            MESSAGE = 'a hold carries its placement and, once released, its release exactly';
    END IF;
    RETURN NULL;
END
$fn$;
CREATE CONSTRAINT TRIGGER integrations_privacy_hold_complete AFTER INSERT OR UPDATE ON integrations.privacy_hold
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION integrations.privacy_hold_complete();
CREATE CONSTRAINT TRIGGER integrations_privacy_hold_event_complete AFTER INSERT ON integrations.privacy_hold_event
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION integrations.privacy_hold_complete();

-- ---------------------------------------------------------------------------------------------
-- Employee data-rights requests: closed lifecycle, append-only history, owned by the submitting employee
-- ---------------------------------------------------------------------------------------------
CREATE TABLE integrations.privacy_request (
    tenant_id       uuid        NOT NULL,
    id              uuid        NOT NULL,
    reference       text        NOT NULL,
    employee_id     uuid        NOT NULL,
    request_type    text        NOT NULL,
    referral_id     uuid,
    state           text        NOT NULL,
    row_version     integer     NOT NULL,
    decision_reason text,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    CONSTRAINT integrations_privacy_request_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT integrations_privacy_request_reference_key UNIQUE (reference),
    CONSTRAINT integrations_privacy_request_employee FOREIGN KEY (tenant_id, employee_id)
        REFERENCES people.employee (tenant_id, id),
    CONSTRAINT integrations_privacy_request_referral FOREIGN KEY (tenant_id, referral_id)
        REFERENCES integrations.partner_referral (tenant_id, id),
    CONSTRAINT integrations_privacy_request_reference CHECK (reference ~ '^PQ-[0-9A-HJKMNP-TV-Z]{16}$'),
    CONSTRAINT integrations_privacy_request_type CHECK (request_type IN ('ACCESS', 'ERASURE', 'RESTRICTION', 'OBJECTION')),
    CONSTRAINT integrations_privacy_request_state CHECK (state IN ('SUBMITTED', 'UNDER_REVIEW',
        'MORE_INFORMATION_REQUIRED', 'APPROVED', 'PARTIALLY_APPROVED', 'REFUSED', 'COMPLETED', 'CANCELLED_BY_EMPLOYEE')),
    CONSTRAINT integrations_privacy_request_version CHECK (row_version BETWEEN 1 AND 64),
    CONSTRAINT integrations_privacy_request_reason CHECK (decision_reason IS NULL OR decision_reason IN (
        'IDENTITY_NOT_VERIFIED', 'LEGAL_HOLD_ACTIVE', 'RETENTION_REQUIRED', 'POLICY_PENDING', 'REQUEST_OUT_OF_SCOPE',
        'NO_MATCHING_DATA')),
    CONSTRAINT integrations_privacy_request_times CHECK (updated_at >= created_at)
);
-- One open request per employee, type and scope (the whole subject, or one referral).
CREATE UNIQUE INDEX integrations_privacy_request_open ON integrations.privacy_request
    (tenant_id, employee_id, request_type, coalesce(referral_id, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE state IN ('SUBMITTED', 'UNDER_REVIEW', 'MORE_INFORMATION_REQUIRED', 'APPROVED', 'PARTIALLY_APPROVED');
CREATE INDEX integrations_privacy_request_page ON integrations.privacy_request (created_at DESC, id DESC);
CREATE INDEX integrations_privacy_request_employee_idx ON integrations.privacy_request (tenant_id, employee_id, created_at DESC);

CREATE TABLE integrations.privacy_request_event (
    tenant_id       uuid        NOT NULL,
    request_id      uuid        NOT NULL,
    sequence        smallint    NOT NULL,
    from_state      text,
    to_state        text        NOT NULL,
    actor_role      text        NOT NULL,
    decision_reason text,
    occurred_at     timestamptz NOT NULL,
    employee_message text,
    note_ciphertext  bytea,
    CONSTRAINT integrations_privacy_request_event_message CHECK (
        employee_message IS NULL OR char_length(employee_message) BETWEEN 1 AND 500),
    CONSTRAINT integrations_privacy_request_event_note CHECK (
        note_ciphertext IS NULL OR octet_length(note_ciphertext) BETWEEN 40 AND 4096),
    CONSTRAINT integrations_privacy_request_event_pkey PRIMARY KEY (tenant_id, request_id, sequence),
    CONSTRAINT integrations_privacy_request_event_request FOREIGN KEY (tenant_id, request_id)
        REFERENCES integrations.privacy_request (tenant_id, id),
    CONSTRAINT integrations_privacy_request_event_sequence CHECK (sequence BETWEEN 1 AND 64 AND (sequence = 1) = (from_state IS NULL)),
    CONSTRAINT integrations_privacy_request_event_actor CHECK (actor_role IN ('EMPLOYEE', 'PLATFORM')),
    CONSTRAINT integrations_privacy_request_event_reason CHECK (decision_reason IS NULL OR decision_reason IN (
        'IDENTITY_NOT_VERIFIED', 'LEGAL_HOLD_ACTIVE', 'RETENTION_REQUIRED', 'POLICY_PENDING', 'REQUEST_OUT_OF_SCOPE',
        'NO_MATCHING_DATA')),
    CONSTRAINT integrations_privacy_request_event_transition CHECK (
        (from_state IS NULL AND to_state = 'SUBMITTED' AND actor_role = 'EMPLOYEE' AND decision_reason IS NULL)
        OR (from_state = 'SUBMITTED' AND to_state = 'UNDER_REVIEW' AND actor_role = 'PLATFORM')
        OR (from_state = 'UNDER_REVIEW' AND to_state = 'MORE_INFORMATION_REQUIRED' AND actor_role = 'PLATFORM')
        OR (from_state = 'MORE_INFORMATION_REQUIRED' AND to_state = 'UNDER_REVIEW' AND actor_role = 'PLATFORM')
        OR (from_state = 'UNDER_REVIEW' AND to_state = 'APPROVED' AND actor_role = 'PLATFORM')
        OR (from_state = 'UNDER_REVIEW' AND to_state IN ('PARTIALLY_APPROVED', 'REFUSED') AND actor_role = 'PLATFORM'
            AND decision_reason IS NOT NULL)
        OR (from_state IN ('APPROVED', 'PARTIALLY_APPROVED') AND to_state = 'COMPLETED' AND actor_role = 'PLATFORM')
        OR (from_state IN ('SUBMITTED', 'UNDER_REVIEW', 'MORE_INFORMATION_REQUIRED') AND to_state = 'CANCELLED_BY_EMPLOYEE'
            AND actor_role = 'EMPLOYEE'))
);
CREATE TRIGGER integrations_privacy_request_event_immutable BEFORE UPDATE OR DELETE ON integrations.privacy_request_event
    FOR EACH ROW EXECUTE FUNCTION integrations.privacy_immutable();
CREATE TRIGGER integrations_privacy_request_event_no_truncate BEFORE TRUNCATE ON integrations.privacy_request_event
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.privacy_immutable();
CREATE TRIGGER integrations_privacy_request_no_delete BEFORE DELETE ON integrations.privacy_request
    FOR EACH ROW EXECUTE FUNCTION integrations.privacy_immutable();
CREATE TRIGGER integrations_privacy_request_no_truncate BEFORE TRUNCATE ON integrations.privacy_request
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.privacy_immutable();

CREATE FUNCTION integrations.privacy_request_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.state <> 'SUBMITTED' OR NEW.row_version <> 1 OR NEW.decision_reason IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_privacy_request_state',
                MESSAGE = 'a request is recorded SUBMITTED, version 1';
        END IF;
        IF NEW.referral_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM integrations.partner_referral r
             WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.referral_id AND r.employee_id = NEW.employee_id) THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_privacy_request_referral_owner',
                MESSAGE = 'a request concerns only the submitting employee''s own referral';
        END IF;
        RETURN NEW;
    END IF;
    IF (to_jsonb(NEW) - 'state' - 'row_version' - 'decision_reason' - 'updated_at') IS DISTINCT FROM
       (to_jsonb(OLD) - 'state' - 'row_version' - 'decision_reason' - 'updated_at')
        OR NEW.row_version <> OLD.row_version + 1 OR NEW.state = OLD.state THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_privacy_request_immutable',
            MESSAGE = 'a request only moves its state, one version at a time';
    END IF;
    RETURN NEW;
END
$fn$;
CREATE TRIGGER integrations_privacy_request_guard BEFORE INSERT OR UPDATE ON integrations.privacy_request
    FOR EACH ROW EXECUTE FUNCTION integrations.privacy_request_guard();

CREATE FUNCTION integrations.privacy_request_event_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    last_sequence integer;
    last_state    text;
    last_at       timestamptz;
BEGIN
    PERFORM 1 FROM integrations.privacy_request q WHERE q.tenant_id = NEW.tenant_id AND q.id = NEW.request_id FOR UPDATE;
    SELECT e.sequence, e.to_state, e.occurred_at INTO last_sequence, last_state, last_at
      FROM integrations.privacy_request_event e
     WHERE e.tenant_id = NEW.tenant_id AND e.request_id = NEW.request_id ORDER BY e.sequence DESC LIMIT 1;
    IF NEW.sequence <> coalesce(last_sequence, 0) + 1 OR NEW.from_state IS DISTINCT FROM last_state
        OR (last_at IS NOT NULL AND NEW.occurred_at < last_at) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_privacy_request_event_transition',
            MESSAGE = 'the request event does not follow the request history';
    END IF;
    RETURN NEW;
END
$fn$;
CREATE TRIGGER integrations_privacy_request_event_guard BEFORE INSERT ON integrations.privacy_request_event
    FOR EACH ROW EXECUTE FUNCTION integrations.privacy_request_event_guard();

CREATE FUNCTION integrations.privacy_request_complete() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    q          integrations.privacy_request%ROWTYPE;
    n_events   integer;
    last_state text;
    last_reason text;
BEGIN
    IF TG_TABLE_NAME = 'privacy_request' THEN
        q := NEW;
    ELSE
        SELECT * INTO q FROM integrations.privacy_request x WHERE x.tenant_id = NEW.tenant_id AND x.id = NEW.request_id;
    END IF;
    SELECT count(*), (array_agg(e.to_state ORDER BY e.sequence DESC))[1], (array_agg(e.decision_reason ORDER BY e.sequence DESC))[1]
      INTO n_events, last_state, last_reason
      FROM integrations.privacy_request_event e WHERE e.tenant_id = q.tenant_id AND e.request_id = q.id;
    IF n_events <> q.row_version OR last_state IS DISTINCT FROM q.state
        OR (q.decision_reason IS NOT NULL AND q.decision_reason IS DISTINCT FROM last_reason AND q.state NOT IN ('APPROVED', 'COMPLETED')) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_privacy_request_complete',
            MESSAGE = 'a request carries its history exactly';
    END IF;
    RETURN NULL;
END
$fn$;
CREATE CONSTRAINT TRIGGER integrations_privacy_request_complete AFTER INSERT OR UPDATE ON integrations.privacy_request
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION integrations.privacy_request_complete();
CREATE CONSTRAINT TRIGGER integrations_privacy_request_event_complete AFTER INSERT ON integrations.privacy_request_event
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION integrations.privacy_request_complete();

-- ---------------------------------------------------------------------------------------------
-- Access package: deterministic, encrypted, time-limited. Only the package columns of an approved ACCESS request change.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE integrations.privacy_export (
    tenant_id          uuid        NOT NULL,
    request_id         uuid        NOT NULL,
    package_ciphertext bytea       NOT NULL,
    manifest_sha256    char(64)    NOT NULL,
    key_version        text        NOT NULL,
    generation_count   smallint    NOT NULL,
    generated_at       timestamptz NOT NULL,
    expires_at         timestamptz NOT NULL,
    CONSTRAINT integrations_privacy_export_pkey PRIMARY KEY (tenant_id, request_id),
    CONSTRAINT integrations_privacy_export_request FOREIGN KEY (tenant_id, request_id)
        REFERENCES integrations.privacy_request (tenant_id, id),
    CONSTRAINT integrations_privacy_export_cipher CHECK (octet_length(package_ciphertext) BETWEEN 40 AND 1048576),
    CONSTRAINT integrations_privacy_export_digest CHECK (manifest_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT integrations_privacy_export_key CHECK (key_version ~ '^[A-Za-z0-9._-]{1,32}$'),
    CONSTRAINT integrations_privacy_export_count CHECK (generation_count BETWEEN 1 AND 3),
    CONSTRAINT integrations_privacy_export_window CHECK (expires_at > generated_at)
);

CREATE TABLE integrations.privacy_export_event (
    tenant_id   uuid        NOT NULL,
    request_id  uuid        NOT NULL,
    sequence    smallint    NOT NULL,
    kind        text        NOT NULL,
    occurred_at timestamptz NOT NULL,
    CONSTRAINT integrations_privacy_export_event_pkey PRIMARY KEY (tenant_id, request_id, sequence),
    CONSTRAINT integrations_privacy_export_event_export FOREIGN KEY (tenant_id, request_id)
        REFERENCES integrations.privacy_export (tenant_id, request_id),
    CONSTRAINT integrations_privacy_export_event_kind CHECK (kind IN ('GENERATED', 'DOWNLOADED')),
    CONSTRAINT integrations_privacy_export_event_sequence CHECK (sequence BETWEEN 1 AND 256)
);
CREATE TRIGGER integrations_privacy_export_event_immutable BEFORE UPDATE OR DELETE ON integrations.privacy_export_event
    FOR EACH ROW EXECUTE FUNCTION integrations.privacy_immutable();
CREATE TRIGGER integrations_privacy_export_event_no_truncate BEFORE TRUNCATE ON integrations.privacy_export_event
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.privacy_immutable();
CREATE TRIGGER integrations_privacy_export_no_delete BEFORE DELETE ON integrations.privacy_export
    FOR EACH ROW EXECUTE FUNCTION integrations.privacy_immutable();
CREATE TRIGGER integrations_privacy_export_no_truncate BEFORE TRUNCATE ON integrations.privacy_export
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.privacy_immutable();

CREATE FUNCTION integrations.privacy_export_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    q integrations.privacy_request%ROWTYPE;
BEGIN
    SELECT * INTO q FROM integrations.privacy_request x WHERE x.tenant_id = NEW.tenant_id AND x.id = NEW.request_id FOR SHARE;
    IF q.request_type IS DISTINCT FROM 'ACCESS' OR q.state NOT IN ('APPROVED', 'PARTIALLY_APPROVED') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_privacy_export_request',
            MESSAGE = 'an access package exists only for an approved ACCESS request';
    END IF;
    IF TG_OP = 'UPDATE' AND ((to_jsonb(NEW) - 'package_ciphertext' - 'manifest_sha256' - 'key_version'
            - 'generation_count' - 'generated_at' - 'expires_at') IS DISTINCT FROM
        (to_jsonb(OLD) - 'package_ciphertext' - 'manifest_sha256' - 'key_version' - 'generation_count'
            - 'generated_at' - 'expires_at') OR NEW.generation_count <> OLD.generation_count + 1) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_privacy_export_regenerate',
            MESSAGE = 'regeneration replaces only the package, once more each time';
    END IF;
    IF TG_OP = 'INSERT' AND NEW.generation_count <> 1 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_privacy_export_regenerate',
            MESSAGE = 'the first package is generation 1';
    END IF;
    RETURN NEW;
END
$fn$;
CREATE TRIGGER integrations_privacy_export_guard BEFORE INSERT OR UPDATE ON integrations.privacy_export
    FOR EACH ROW EXECUTE FUNCTION integrations.privacy_export_guard();

-- ---------------------------------------------------------------------------------------------
-- Purge items and value-free residue
-- ---------------------------------------------------------------------------------------------
CREATE TABLE integrations.privacy_purge_item (
    purge_id    uuid        NOT NULL,
    tenant_id   uuid        NOT NULL,
    referral_id uuid        NOT NULL,
    data_class  text        NOT NULL,
    action      text        NOT NULL,
    created_at  timestamptz NOT NULL,
    CONSTRAINT integrations_privacy_purge_item_pkey PRIMARY KEY (purge_id, tenant_id, referral_id, data_class),
    CONSTRAINT integrations_privacy_purge_item_purge FOREIGN KEY (purge_id) REFERENCES integrations.privacy_purge (id),
    CONSTRAINT integrations_privacy_purge_item_referral FOREIGN KEY (tenant_id, referral_id)
        REFERENCES integrations.partner_referral (tenant_id, id),
    CONSTRAINT integrations_privacy_purge_item_class CHECK (data_class IN (
        'PARTNER_REFERRAL_DISCLOSURE', 'PARTNER_REFERRAL_CONSENT', 'PARTNER_REFERRAL_DELIVERY_DIAGNOSTICS',
        'PARTNER_REFERRAL_LIFECYCLE_EVIDENCE')),
    CONSTRAINT integrations_privacy_purge_item_action CHECK (action IN ('MINIMIZE', 'ERASE'))
);
-- A class of a referral is purged once.
CREATE UNIQUE INDEX integrations_privacy_purge_item_once ON integrations.privacy_purge_item (tenant_id, referral_id, data_class);

CREATE TRIGGER integrations_privacy_purge_item_immutable BEFORE UPDATE OR DELETE ON integrations.privacy_purge_item
    FOR EACH ROW EXECUTE FUNCTION integrations.privacy_immutable();
CREATE TRIGGER integrations_privacy_purge_item_no_truncate BEFORE TRUNCATE ON integrations.privacy_purge_item
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.privacy_immutable();

CREATE FUNCTION integrations.privacy_purge_item_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    v_state   text;
    v_created timestamptz;
    v_rule    integrations.privacy_referral_stamp_rule%ROWTYPE;
    v_base    timestamptz;
    v_purge_at timestamptz;
BEGIN
    SELECT r.state, r.created_at INTO v_state, v_created FROM integrations.partner_referral r
     WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.referral_id FOR UPDATE;
    SELECT p.created_at INTO v_purge_at FROM integrations.privacy_purge p WHERE p.id = NEW.purge_id AND p.state = 'EXECUTING' FOR SHARE;
    IF v_purge_at IS NULL OR v_state NOT IN ('DELIVERED', 'DELIVERY_UNKNOWN', 'WITHDRAWN') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_privacy_purge_item_closed',
            MESSAGE = 'only a closed referral is purged, inside an executing purge';
    END IF;
    SELECT r.* INTO v_rule FROM integrations.privacy_referral_stamp s
      JOIN integrations.privacy_referral_stamp_rule r ON r.tenant_id = s.tenant_id AND r.referral_id = s.referral_id
     WHERE s.tenant_id = NEW.tenant_id AND s.referral_id = NEW.referral_id AND s.status = 'STAMPED'
       AND r.data_class = NEW.data_class;
    IF NOT FOUND OR v_rule.action <> NEW.action OR v_rule.action = 'HOLD' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_privacy_purge_item_rule',
            MESSAGE = 'a purge item applies exactly the action of the stamped rule; pending or hold rules never purge';
    END IF;
    IF EXISTS (SELECT 1 FROM integrations.privacy_hold h
                WHERE h.tenant_id = NEW.tenant_id AND h.referral_id = NEW.referral_id AND h.state = 'ACTIVE') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_privacy_purge_item_hold',
            MESSAGE = 'an active hold prevents every purge of the referral';
    END IF;
    IF v_rule.trigger_event = 'REFERRAL_CREATED' THEN
        v_base := v_created;
    ELSE
        SELECT max(e.occurred_at) INTO v_base FROM integrations.partner_referral_event e
         WHERE e.tenant_id = NEW.tenant_id AND e.referral_id = NEW.referral_id
           AND e.to_state IN ('DELIVERED', 'DELIVERY_UNKNOWN', 'WITHDRAWN');
    END IF;
    IF v_base IS NULL OR v_base + (v_rule.duration_days * interval '1 day') > v_purge_at THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_privacy_purge_item_elapsed',
            MESSAGE = 'the stamped duration has not elapsed';
    END IF;
    RETURN NEW;
END
$fn$;
CREATE TRIGGER integrations_privacy_purge_item_guard BEFORE INSERT ON integrations.privacy_purge_item
    FOR EACH ROW EXECUTE FUNCTION integrations.privacy_purge_item_guard();

-- Residue holds only allow-listed, non-reversible facts about what was minimized; an ERASE leaves none.
CREATE FUNCTION integrations.privacy_residue_valid(p_class text, p_details jsonb) RETURNS boolean
    LANGUAGE sql IMMUTABLE AS
$fn$
    SELECT jsonb_typeof(p_details) = 'object' AND NOT EXISTS (
        SELECT 1 FROM jsonb_each(p_details) kv
         WHERE kv.key <> ALL (CASE p_class
                 WHEN 'PARTNER_REFERRAL_DISCLOSURE' THEN ARRAY['field_count']
                 WHEN 'PARTNER_REFERRAL_CONSENT' THEN ARRAY['consent_version', 'language']
                 WHEN 'PARTNER_REFERRAL_DELIVERY_DIAGNOSTICS' THEN ARRAY['attempt_count', 'acknowledged']
                 ELSE ARRAY['event_count', 'opened_at', 'closed_at'] END)
            OR jsonb_typeof(kv.value) NOT IN ('number', 'boolean', 'string')
            OR (jsonb_typeof(kv.value) = 'string' AND char_length(kv.value #>> '{}') > 20))
$fn$;

CREATE TABLE integrations.privacy_referral_residue (
    purge_id    uuid        NOT NULL,
    tenant_id   uuid        NOT NULL,
    referral_id uuid        NOT NULL,
    data_class  text        NOT NULL,
    details     jsonb       NOT NULL,
    created_at  timestamptz NOT NULL,
    CONSTRAINT integrations_privacy_referral_residue_pkey PRIMARY KEY (purge_id, tenant_id, referral_id, data_class),
    CONSTRAINT integrations_privacy_referral_residue_item FOREIGN KEY (purge_id, tenant_id, referral_id, data_class)
        REFERENCES integrations.privacy_purge_item (purge_id, tenant_id, referral_id, data_class),
    CONSTRAINT integrations_privacy_referral_residue_valid CHECK (integrations.privacy_residue_valid(data_class, details))
);
CREATE FUNCTION integrations.privacy_residue_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM integrations.privacy_purge_item i
                    WHERE i.purge_id = NEW.purge_id AND i.tenant_id = NEW.tenant_id AND i.referral_id = NEW.referral_id
                      AND i.data_class = NEW.data_class AND i.action = 'MINIMIZE') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_privacy_referral_residue_item',
            MESSAGE = 'residue is recorded only for a minimized item';
    END IF;
    RETURN NEW;
END
$fn$;
CREATE TRIGGER integrations_privacy_referral_residue_guard BEFORE INSERT ON integrations.privacy_referral_residue
    FOR EACH ROW EXECUTE FUNCTION integrations.privacy_residue_guard();
CREATE TRIGGER integrations_privacy_referral_residue_immutable BEFORE UPDATE OR DELETE ON integrations.privacy_referral_residue
    FOR EACH ROW EXECUTE FUNCTION integrations.privacy_immutable();
CREATE TRIGGER integrations_privacy_referral_residue_no_truncate BEFORE TRUNCATE ON integrations.privacy_referral_residue
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.privacy_immutable();

-- ---------------------------------------------------------------------------------------------
-- The V57 evidence tables stay immutable except for a DELETE inside an executing purge that holds a matching item.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION integrations.partner_referral_evidence_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    v_purge text := current_setting('divalhr.privacy_purge', true);
    v_class text;
BEGIN
    IF TG_OP <> 'DELETE' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = TG_NAME,
            MESSAGE = 'partner referral evidence is append-only: it is never updated';
    END IF;
    v_class := CASE TG_TABLE_NAME
        WHEN 'partner_referral_disclosure' THEN 'PARTNER_REFERRAL_DISCLOSURE'
        WHEN 'partner_referral_consent' THEN 'PARTNER_REFERRAL_CONSENT'
        WHEN 'partner_referral_event' THEN 'PARTNER_REFERRAL_LIFECYCLE_EVIDENCE'
        ELSE 'PARTNER_REFERRAL_DELIVERY_DIAGNOSTICS' END;
    IF v_purge IS NULL OR v_purge = ''
        OR NOT EXISTS (SELECT 1 FROM integrations.privacy_purge p
                         JOIN integrations.privacy_purge_item i ON i.purge_id = p.id
                        WHERE p.id = v_purge::uuid AND p.state = 'EXECUTING'
                          AND i.tenant_id = OLD.tenant_id AND i.referral_id = OLD.referral_id AND i.data_class = v_class) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = TG_NAME,
            MESSAGE = 'partner referral evidence is deleted only by an authorized purge item';
    END IF;
    -- The consent is the last evidence to go: its disclosure and delivery diagnostics must already be gone.
    IF v_class = 'PARTNER_REFERRAL_CONSENT' AND (
        EXISTS (SELECT 1 FROM integrations.partner_referral_disclosure d WHERE d.tenant_id = OLD.tenant_id AND d.referral_id = OLD.referral_id)
        OR EXISTS (SELECT 1 FROM integrations.partner_referral_attempt a WHERE a.tenant_id = OLD.tenant_id AND a.referral_id = OLD.referral_id)
        OR EXISTS (SELECT 1 FROM integrations.partner_referral_ack k WHERE k.tenant_id = OLD.tenant_id AND k.referral_id = OLD.referral_id)) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = TG_NAME,
            MESSAGE = 'the consent is purged only after its disclosure and delivery evidence';
    END IF;
    -- R227-1: the lifecycle history is the last to go: every other class of the referral must already be gone.
    IF v_class = 'PARTNER_REFERRAL_LIFECYCLE_EVIDENCE' AND (
        EXISTS (SELECT 1 FROM integrations.partner_referral_disclosure d WHERE d.tenant_id = OLD.tenant_id AND d.referral_id = OLD.referral_id)
        OR EXISTS (SELECT 1 FROM integrations.partner_referral_consent c WHERE c.tenant_id = OLD.tenant_id AND c.referral_id = OLD.referral_id)
        OR EXISTS (SELECT 1 FROM integrations.partner_referral_attempt a WHERE a.tenant_id = OLD.tenant_id AND a.referral_id = OLD.referral_id)
        OR EXISTS (SELECT 1 FROM integrations.partner_referral_ack k WHERE k.tenant_id = OLD.tenant_id AND k.referral_id = OLD.referral_id)) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = TG_NAME,
            MESSAGE = 'the lifecycle evidence is purged only after every other class of the referral';
    END IF;
    RETURN OLD;
END
$fn$;

DROP TRIGGER integrations_partner_referral_disclosure_immutable ON integrations.partner_referral_disclosure;
DROP TRIGGER integrations_partner_referral_consent_immutable ON integrations.partner_referral_consent;
DROP TRIGGER integrations_partner_referral_attempt_immutable ON integrations.partner_referral_attempt;
DROP TRIGGER integrations_partner_referral_ack_immutable ON integrations.partner_referral_ack;
DROP TRIGGER integrations_partner_referral_event_immutable ON integrations.partner_referral_event;
CREATE TRIGGER integrations_partner_referral_event_immutable BEFORE UPDATE OR DELETE ON integrations.partner_referral_event
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_referral_evidence_guard();
CREATE TRIGGER integrations_partner_referral_disclosure_immutable BEFORE UPDATE OR DELETE ON integrations.partner_referral_disclosure
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_referral_evidence_guard();
CREATE TRIGGER integrations_partner_referral_consent_immutable BEFORE UPDATE OR DELETE ON integrations.partner_referral_consent
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_referral_evidence_guard();
CREATE TRIGGER integrations_partner_referral_attempt_immutable BEFORE UPDATE OR DELETE ON integrations.partner_referral_attempt
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_referral_evidence_guard();
CREATE TRIGGER integrations_partner_referral_ack_immutable BEFORE UPDATE OR DELETE ON integrations.partner_referral_ack
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_referral_evidence_guard();

-- ---------------------------------------------------------------------------------------------
-- R227-1: the lifecycle tombstone. Minimizing or erasing the LIFECYCLE_EVIDENCE class genuinely removes the identifying
-- and product-choice detail: the detailed events are deleted and the header loses its link to the employee, the partner,
-- the product and its version (and the language, the submission subject and the preview digest). What remains is an
-- opaque row: its random reference, the tenant, the closed state, the counters and the two lifecycle timestamps. The row
-- is either whole or a tombstone: every identifying column is null together or none is.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE integrations.partner_referral
    ALTER COLUMN employee_id DROP NOT NULL,
    ALTER COLUMN partner_id DROP NOT NULL,
    ALTER COLUMN product_id DROP NOT NULL,
    ALTER COLUMN version_id DROP NOT NULL,
    ALTER COLUMN partner_code DROP NOT NULL,
    ALTER COLUMN language DROP NOT NULL,
    ALTER COLUMN preview_sha256 DROP NOT NULL,
    ALTER COLUMN submitted_by DROP NOT NULL;
ALTER TABLE integrations.partner_referral
    ADD CONSTRAINT integrations_partner_referral_tombstone CHECK (
        (employee_id IS NULL) = (partner_id IS NULL) AND (employee_id IS NULL) = (product_id IS NULL)
        AND (employee_id IS NULL) = (version_id IS NULL) AND (employee_id IS NULL) = (partner_code IS NULL)
        AND (employee_id IS NULL) = (language IS NULL) AND (employee_id IS NULL) = (preview_sha256 IS NULL)
        AND (employee_id IS NULL) = (submitted_by IS NULL));

-- The V57 guard keeps governing every ordinary update; a scrub is governed by the guard below, and a tombstone is final.
DROP TRIGGER integrations_partner_referral_guard ON integrations.partner_referral;
CREATE TRIGGER integrations_partner_referral_guard_insert BEFORE INSERT ON integrations.partner_referral
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_referral_guard();
CREATE TRIGGER integrations_partner_referral_guard BEFORE UPDATE ON integrations.partner_referral
    FOR EACH ROW WHEN (NEW.employee_id IS NOT NULL) EXECUTE FUNCTION integrations.partner_referral_guard();

CREATE FUNCTION integrations.partner_referral_scrub_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    v_purge text := current_setting('divalhr.privacy_purge', true);
BEGIN
    IF OLD.employee_id IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_referral_tombstone',
            MESSAGE = 'a lifecycle tombstone never changes';
    END IF;
    IF (to_jsonb(NEW) - 'employee_id' - 'partner_id' - 'product_id' - 'version_id' - 'partner_code' - 'language'
            - 'preview_sha256' - 'submitted_by')
       IS DISTINCT FROM
       (to_jsonb(OLD) - 'employee_id' - 'partner_id' - 'product_id' - 'version_id' - 'partner_code' - 'language'
            - 'preview_sha256' - 'submitted_by') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_referral_tombstone',
            MESSAGE = 'a lifecycle scrub removes only the identifying columns';
    END IF;
    IF OLD.state NOT IN ('DELIVERED', 'DELIVERY_UNKNOWN', 'WITHDRAWN')
        OR v_purge IS NULL OR v_purge = ''
        OR NOT EXISTS (SELECT 1 FROM integrations.privacy_purge p
                         JOIN integrations.privacy_purge_item i ON i.purge_id = p.id
                        WHERE p.id = v_purge::uuid AND p.state = 'EXECUTING'
                          AND i.tenant_id = OLD.tenant_id AND i.referral_id = OLD.id
                          AND i.data_class = 'PARTNER_REFERRAL_LIFECYCLE_EVIDENCE')
        OR EXISTS (SELECT 1 FROM integrations.partner_referral_event e WHERE e.tenant_id = OLD.tenant_id AND e.referral_id = OLD.id)
        OR EXISTS (SELECT 1 FROM integrations.partner_referral_disclosure d WHERE d.tenant_id = OLD.tenant_id AND d.referral_id = OLD.id)
        OR EXISTS (SELECT 1 FROM integrations.partner_referral_consent c WHERE c.tenant_id = OLD.tenant_id AND c.referral_id = OLD.id)
        OR EXISTS (SELECT 1 FROM integrations.partner_referral_attempt a WHERE a.tenant_id = OLD.tenant_id AND a.referral_id = OLD.id)
        OR EXISTS (SELECT 1 FROM integrations.partner_referral_ack k WHERE k.tenant_id = OLD.tenant_id AND k.referral_id = OLD.id) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_referral_tombstone',
            MESSAGE = 'a closed referral is scrubbed only by an authorized lifecycle purge item, after its events and every other class';
    END IF;
    RETURN NEW;
END
$fn$;
CREATE TRIGGER integrations_partner_referral_scrub BEFORE UPDATE ON integrations.partner_referral
    FOR EACH ROW WHEN (NEW.employee_id IS NULL) EXECUTE FUNCTION integrations.partner_referral_scrub_guard();

-- The V57 completeness rule tolerates only what an authorized purge item removed.
CREATE OR REPLACE FUNCTION integrations.partner_referral_complete() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    r          integrations.partner_referral%ROWTYPE;
    n_events   integer;
    last_state text;
    n_attempts integer;
BEGIN
    IF TG_TABLE_NAME = 'partner_referral' THEN
        r := NEW;
    ELSE
        SELECT * INTO r FROM integrations.partner_referral x
         WHERE x.tenant_id = NEW.tenant_id AND x.id = NEW.referral_id;
        IF NOT FOUND THEN
            RETURN NULL;
        END IF;
    END IF;
    -- R227-1: a lifecycle tombstone keeps no history, disclosure, consent or attempts to be complete about.
    IF r.employee_id IS NULL THEN
        RETURN NULL;
    END IF;
    SELECT count(*), (array_agg(e.to_state ORDER BY e.sequence DESC))[1] INTO n_events, last_state
      FROM integrations.partner_referral_event e WHERE e.tenant_id = r.tenant_id AND e.referral_id = r.id;
    SELECT count(*) INTO n_attempts
      FROM integrations.partner_referral_attempt a WHERE a.tenant_id = r.tenant_id AND a.referral_id = r.id;
    IF (NOT EXISTS (SELECT 1 FROM integrations.partner_referral_disclosure d
                     WHERE d.tenant_id = r.tenant_id AND d.referral_id = r.id)
            AND NOT EXISTS (SELECT 1 FROM integrations.privacy_purge_item i WHERE i.tenant_id = r.tenant_id
                             AND i.referral_id = r.id AND i.data_class = 'PARTNER_REFERRAL_DISCLOSURE'))
        OR (NOT EXISTS (SELECT 1 FROM integrations.partner_referral_consent c
                         WHERE c.tenant_id = r.tenant_id AND c.referral_id = r.id)
            AND NOT EXISTS (SELECT 1 FROM integrations.privacy_purge_item i WHERE i.tenant_id = r.tenant_id
                             AND i.referral_id = r.id AND i.data_class = 'PARTNER_REFERRAL_CONSENT'))
        OR n_events <> r.row_version OR last_state IS DISTINCT FROM r.state
        OR (n_attempts <> r.attempt_count - (CASE WHEN r.lease_until IS NOT NULL THEN 1 ELSE 0 END)
            AND NOT EXISTS (SELECT 1 FROM integrations.privacy_purge_item i WHERE i.tenant_id = r.tenant_id
                             AND i.referral_id = r.id AND i.data_class = 'PARTNER_REFERRAL_DELIVERY_DIAGNOSTICS')) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_referral_complete',
            MESSAGE = 'a referral carries its disclosure, consent, history and attempts exactly';
    END IF;
    RETURN NULL;
END
$fn$;

-- A completed purge has really removed what each of its items says, and left residue for every minimized item.
CREATE FUNCTION integrations.privacy_purge_complete() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    n_items integer;
BEGIN
    IF NEW.state <> 'COMPLETED' THEN
        RETURN NULL;
    END IF;
    SELECT count(*) INTO n_items FROM integrations.privacy_purge_item i WHERE i.purge_id = NEW.id;
    IF n_items <> NEW.item_count
        OR EXISTS (
            SELECT 1 FROM integrations.privacy_purge_item i
             WHERE i.purge_id = NEW.id AND (
                (i.data_class = 'PARTNER_REFERRAL_DISCLOSURE' AND EXISTS (SELECT 1 FROM integrations.partner_referral_disclosure d
                    WHERE d.tenant_id = i.tenant_id AND d.referral_id = i.referral_id))
                OR (i.data_class = 'PARTNER_REFERRAL_CONSENT' AND EXISTS (SELECT 1 FROM integrations.partner_referral_consent c
                    WHERE c.tenant_id = i.tenant_id AND c.referral_id = i.referral_id))
                OR (i.data_class = 'PARTNER_REFERRAL_DELIVERY_DIAGNOSTICS' AND (
                    EXISTS (SELECT 1 FROM integrations.partner_referral_attempt a WHERE a.tenant_id = i.tenant_id AND a.referral_id = i.referral_id)
                    OR EXISTS (SELECT 1 FROM integrations.partner_referral_ack k WHERE k.tenant_id = i.tenant_id AND k.referral_id = i.referral_id)))
                OR (i.data_class = 'PARTNER_REFERRAL_LIFECYCLE_EVIDENCE' AND (
                    EXISTS (SELECT 1 FROM integrations.partner_referral_event e WHERE e.tenant_id = i.tenant_id AND e.referral_id = i.referral_id)
                    OR EXISTS (SELECT 1 FROM integrations.partner_referral r WHERE r.tenant_id = i.tenant_id AND r.id = i.referral_id
                                AND r.employee_id IS NOT NULL)))
                OR (i.action = 'MINIMIZE' AND NOT EXISTS (SELECT 1 FROM integrations.privacy_referral_residue x
                    WHERE x.purge_id = i.purge_id AND x.tenant_id = i.tenant_id AND x.referral_id = i.referral_id
                      AND x.data_class = i.data_class))
                OR (i.action = 'ERASE' AND EXISTS (SELECT 1 FROM integrations.privacy_referral_residue x
                    WHERE x.purge_id = i.purge_id AND x.tenant_id = i.tenant_id AND x.referral_id = i.referral_id
                      AND x.data_class = i.data_class)))) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_privacy_purge_complete',
            MESSAGE = 'a completed purge removed exactly what its items authorize and left only allowed residue';
    END IF;
    RETURN NULL;
END
$fn$;
CREATE CONSTRAINT TRIGGER integrations_privacy_purge_complete AFTER UPDATE ON integrations.privacy_purge
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION integrations.privacy_purge_complete();

COMMENT ON TABLE integrations.privacy_referral_residue IS
    'LAUNCH-001 (L9): value-free residue of a minimized class. Allow-listed counts and fixed labels only: never a name, an '
    'email, a phone, a free text or a digest of personal data.';
COMMENT ON TABLE integrations.privacy_policy IS
    'LAUNCH-001 (L3): a governed policy version. The platform ships no retention duration and no legal claim; a version '
    'is activated only with a complete checklist recording the reviewed source.';
