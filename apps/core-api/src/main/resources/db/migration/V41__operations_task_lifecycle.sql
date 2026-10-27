-- MVP-080 (Issue #167): a tenant administrator assigns a bounded operational task to one active
-- employee, who starts and completes it from self-service; the administrator can cancel an
-- unfinished one (ADR 0030, decisions D80-1 to D80-12). Additive to V40.
--
-- Ownership: the operations module; only the bounded com.divalhr.core.operations package reads or
-- writes these objects. The keys to tenant.organization and people.employee are the documented
-- modular-monolith integrity exception (constraints, never reads), like V16, V24, V25 and V32.
--
-- Classification: Confidential. Title, instructions, completion note and cancellation explanation
-- are restricted plain text entered by users; they never leave this schema except in the
-- authorized API responses (audit metadata, outbox payloads, metrics, logs and error responses never
-- carry them).
--
-- Shape:
--   * task: the current projection. Identity and content are immutable; only a legal transition
--     changes it, and every transition moves the version by exactly one.
--   * task_action: append-only evidence (CREATED, STARTED, COMPLETED, CANCELLED) with one
--     increasing sequence per task. The projection's version always equals the last sequence and
--     its state, timestamps and note always match the last action (a deferred exact-match check).
--   * COMPLETED and CANCELLED are terminal; tasks are never deleted or truncated.
--   * At most 10,000 non-terminal tasks per tenant, enforced in the database under a per-tenant
--     advisory lock.
--
-- Rollback: db/rollback/V41__rollback.sql (manual, never run by Flyway) restores V40 exactly only
-- while no task or task-action row exists; there is no override and no retention decision.

-- ---------------------------------------------------------------------------------------------
-- Restricted plain text (the database mirror of TaskText; the application is at least as strict:
-- a value the database refuses is always refused by the application first).
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION operations.task_text_valid(value text, min_length integer, max_length integer)
    RETURNS boolean LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE AS
$fn$
SELECT value IS NFC NORMALIZED
    AND char_length(value) BETWEEN min_length AND max_length
    AND value !~ '^[[:space:]]' AND value !~ '[[:space:]]$'
    AND value !~ '[\u0001-\u001F\u007F-\u009F\u00AD\u200B-\u200F\u2028-\u202E\u2060-\u2064\u2066-\u206F\uFEFF\uE000-\uF8FF\uFFF9-\uFFFB]'
    AND value !~ '[<>\\]'
    AND value !~* '(https?:|www\.|://|%[0-9a-f]{2}|&#|&[a-z][a-z0-9]{1,31};)'
$fn$;

COMMENT ON FUNCTION operations.task_text_valid(text, integer, integer) IS
    'MVP-080: NFC, bounded length, no edge whitespace, control or format characters, markup, links or encoded content';

CREATE FUNCTION operations.task_append_only() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'operations_task_append_only',
        MESSAGE = 'task evidence is never deleted, truncated or updated; tasks are never deleted';
END
$fn$;

-- ---------------------------------------------------------------------------------------------
-- Task (current projection)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE operations.task (
    tenant_id            uuid        NOT NULL,
    id                   uuid        NOT NULL,
    assignee_employee_id uuid        NOT NULL,
    title                text        NOT NULL,
    instructions         text,
    priority             text        NOT NULL,
    due_at               timestamptz,
    state                text        NOT NULL,
    version              integer     NOT NULL,
    created_at           timestamptz NOT NULL,
    created_by           text        NOT NULL,
    started_at           timestamptz,
    completed_at         timestamptz,
    cancelled_at         timestamptz,
    completion_note      text,
    cancel_reason        text,
    cancel_explanation   text,
    -- Stable keyset ordering (D80-8): unfinished first by due instant (null last), then newest
    -- creation, then id; terminal tasks newest terminal action first, then id. Ascending order of
    -- (sort_group, sort_key_1, sort_key_2, id) is exactly that order.
    sort_group           smallint    GENERATED ALWAYS AS (
        CASE WHEN state IN ('OPEN', 'IN_PROGRESS') THEN 0 ELSE 1 END) STORED,
    sort_key_1           bigint      GENERATED ALWAYS AS (
        CASE WHEN state IN ('OPEN', 'IN_PROGRESS')
            THEN COALESCE((EXTRACT(EPOCH FROM (due_at AT TIME ZONE 'UTC')) * 1000000)::bigint, 9223372036854775807)
            ELSE -((EXTRACT(EPOCH FROM (COALESCE(completed_at, cancelled_at) AT TIME ZONE 'UTC')) * 1000000)::bigint)
        END) STORED,
    sort_key_2           bigint      GENERATED ALWAYS AS (
        CASE WHEN state IN ('OPEN', 'IN_PROGRESS')
            THEN -((EXTRACT(EPOCH FROM (created_at AT TIME ZONE 'UTC')) * 1000000)::bigint)
            ELSE 0
        END) STORED,
    CONSTRAINT operations_task_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT operations_task_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.organization (id),
    CONSTRAINT operations_task_assignee FOREIGN KEY (tenant_id, assignee_employee_id)
        REFERENCES people.employee (tenant_id, id),
    CONSTRAINT operations_task_state CHECK (state IN ('OPEN', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT operations_task_priority CHECK (priority IN ('NORMAL', 'HIGH', 'URGENT')),
    CONSTRAINT operations_task_version CHECK (version >= 1),
    CONSTRAINT operations_task_actor CHECK (char_length(created_by) BETWEEN 1 AND 255),
    CONSTRAINT operations_task_title CHECK (operations.task_text_valid(title, 1, 120)),
    CONSTRAINT operations_task_instructions
        CHECK (instructions IS NULL OR operations.task_text_valid(instructions, 1, 1000)),
    -- 366 days is 8,784 hours, so the limit does not depend on the session time zone.
    CONSTRAINT operations_task_due
        CHECK (due_at IS NULL OR (due_at > created_at AND due_at <= created_at + INTERVAL '8784 hours')),
    CONSTRAINT operations_task_completion_note
        CHECK (completion_note IS NULL OR operations.task_text_valid(completion_note, 1, 500)),
    CONSTRAINT operations_task_cancel_reason CHECK (
        cancel_reason IS NULL
        OR cancel_reason IN ('NO_LONGER_REQUIRED', 'CREATED_IN_ERROR', 'WORK_MOVED_ELSEWHERE', 'OTHER')),
    CONSTRAINT operations_task_cancel_explanation CHECK (
        (cancel_reason = 'OTHER' AND cancel_explanation IS NOT NULL
            AND operations.task_text_valid(cancel_explanation, 10, 500))
        OR (cancel_reason IS DISTINCT FROM 'OTHER' AND cancel_explanation IS NULL)),
    -- The shape of every state: which timestamps and fields exist.
    CONSTRAINT operations_task_shape CHECK (
        (state = 'OPEN' AND started_at IS NULL AND completed_at IS NULL AND cancelled_at IS NULL
            AND completion_note IS NULL AND cancel_reason IS NULL)
        OR (state = 'IN_PROGRESS' AND started_at IS NOT NULL AND completed_at IS NULL
            AND cancelled_at IS NULL AND completion_note IS NULL AND cancel_reason IS NULL)
        OR (state = 'COMPLETED' AND completed_at IS NOT NULL AND cancelled_at IS NULL
            AND cancel_reason IS NULL)
        OR (state = 'CANCELLED' AND cancelled_at IS NOT NULL AND completed_at IS NULL
            AND completion_note IS NULL AND cancel_reason IS NOT NULL)),
    CONSTRAINT operations_task_times CHECK (
        (started_at IS NULL OR started_at >= created_at)
        AND (completed_at IS NULL OR completed_at >= COALESCE(started_at, created_at))
        AND (cancelled_at IS NULL OR cancelled_at >= COALESCE(started_at, created_at)))
);

COMMENT ON TABLE operations.task IS
    'MVP-080 current projection of one operational task; never deleted; changed only by a legal transition';

CREATE INDEX operations_task_tenant_order ON operations.task
    (tenant_id, sort_group, sort_key_1, sort_key_2, id);
CREATE INDEX operations_task_assignee_order ON operations.task
    (tenant_id, assignee_employee_id, sort_group, sort_key_1, sort_key_2, id);
CREATE INDEX operations_task_open ON operations.task (tenant_id) WHERE state IN ('OPEN', 'IN_PROGRESS');
-- The per-subject hourly creation quota (D80-11) counts the subject's own tasks in the last hour.
CREATE INDEX operations_task_created_by ON operations.task (tenant_id, created_by, created_at);

-- ---------------------------------------------------------------------------------------------
-- Task action (append-only evidence)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE operations.task_action (
    tenant_id           uuid        NOT NULL,
    task_id             uuid        NOT NULL,
    seq                 integer     NOT NULL,
    action              text        NOT NULL,
    occurred_at         timestamptz NOT NULL,
    actor_subject       text        NOT NULL,
    actor_role          text        NOT NULL,
    -- The assigned employee for an employee action; null for an administrator action.
    actor_employee_id   uuid,
    from_state          text,
    to_state            text        NOT NULL,
    completion_note     text,
    reason_code         text,
    explanation         text,
    CONSTRAINT operations_task_action_pkey PRIMARY KEY (tenant_id, task_id, seq),
    CONSTRAINT operations_task_action_task FOREIGN KEY (tenant_id, task_id)
        REFERENCES operations.task (tenant_id, id),
    CONSTRAINT operations_task_action_seq CHECK (seq >= 1),
    CONSTRAINT operations_task_action_kind CHECK (action IN ('CREATED', 'STARTED', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT operations_task_action_actor CHECK (
        char_length(actor_subject) BETWEEN 1 AND 255 AND actor_role IN ('tenant-admin', 'employee')
        AND ((actor_role = 'employee') = (actor_employee_id IS NOT NULL))),
    CONSTRAINT operations_task_action_states CHECK (
        (action = 'CREATED' AND from_state IS NULL AND to_state = 'OPEN' AND actor_role = 'tenant-admin')
        OR (action = 'STARTED' AND from_state = 'OPEN' AND to_state = 'IN_PROGRESS' AND actor_role = 'employee')
        OR (action = 'COMPLETED' AND from_state IN ('OPEN', 'IN_PROGRESS') AND to_state = 'COMPLETED'
            AND actor_role = 'employee')
        OR (action = 'CANCELLED' AND from_state IN ('OPEN', 'IN_PROGRESS') AND to_state = 'CANCELLED'
            AND actor_role = 'tenant-admin')),
    CONSTRAINT operations_task_action_completion_note CHECK (
        (action = 'COMPLETED' OR completion_note IS NULL)
        AND (completion_note IS NULL OR operations.task_text_valid(completion_note, 1, 500))),
    CONSTRAINT operations_task_action_cancellation CHECK (
        (action = 'CANCELLED' AND reason_code IN
            ('NO_LONGER_REQUIRED', 'CREATED_IN_ERROR', 'WORK_MOVED_ELSEWHERE', 'OTHER')
            AND ((reason_code = 'OTHER' AND explanation IS NOT NULL
                AND operations.task_text_valid(explanation, 10, 500))
                OR (reason_code <> 'OTHER' AND explanation IS NULL)))
        OR (action <> 'CANCELLED' AND reason_code IS NULL AND explanation IS NULL))
);

-- The per-subject hourly transition quota (D80-11) counts the subject's own start, complete and
-- cancel actions in the last hour.
CREATE INDEX operations_task_action_actor ON operations.task_action
    (tenant_id, actor_subject, occurred_at) WHERE action <> 'CREATED';

COMMENT ON TABLE operations.task_action IS
    'MVP-080 append-only task actions: one increasing sequence per task, never updated or deleted';

-- ---------------------------------------------------------------------------------------------
-- Guards
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION operations.task_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    open_count integer;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.state <> 'OPEN' OR NEW.version <> 1 THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'operations_task_initial',
                MESSAGE = 'a task starts open at version 1';
        END IF;
        -- One tenant creates (and enforces the limit) at a time.
        PERFORM pg_advisory_xact_lock(hashtextextended('operations.task.open:' || NEW.tenant_id::text, 0));
        SELECT count(*) INTO open_count FROM operations.task
            WHERE tenant_id = NEW.tenant_id AND state IN ('OPEN', 'IN_PROGRESS');
        IF open_count >= 10000 THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'operations_task_open_limit',
                MESSAGE = 'at most 10000 non-terminal tasks per tenant';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.state IN ('COMPLETED', 'CANCELLED') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'operations_task_terminal',
            MESSAGE = 'a completed or cancelled task never changes';
    END IF;
    IF NEW.tenant_id <> OLD.tenant_id OR NEW.id <> OLD.id
        OR NEW.assignee_employee_id <> OLD.assignee_employee_id
        OR NEW.title <> OLD.title OR NEW.instructions IS DISTINCT FROM OLD.instructions
        OR NEW.priority <> OLD.priority OR NEW.due_at IS DISTINCT FROM OLD.due_at
        OR NEW.created_at <> OLD.created_at OR NEW.created_by <> OLD.created_by THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'operations_task_identity',
            MESSAGE = 'task identity and content are immutable';
    END IF;
    IF NEW.version <> OLD.version + 1 OR NEW.state = OLD.state
        OR NOT ((OLD.state = 'OPEN' AND NEW.state IN ('IN_PROGRESS', 'COMPLETED', 'CANCELLED'))
            OR (OLD.state = 'IN_PROGRESS' AND NEW.state IN ('COMPLETED', 'CANCELLED'))) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'operations_task_transition',
            MESSAGE = 'illegal task transition or version';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER operations_task_guard BEFORE INSERT OR UPDATE ON operations.task
    FOR EACH ROW EXECUTE FUNCTION operations.task_guard();
CREATE TRIGGER operations_task_no_delete BEFORE DELETE ON operations.task
    FOR EACH ROW EXECUTE FUNCTION operations.task_append_only();
CREATE TRIGGER operations_task_no_truncate BEFORE TRUNCATE ON operations.task
    FOR EACH STATEMENT EXECUTE FUNCTION operations.task_append_only();

CREATE FUNCTION operations.task_action_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    previous operations.task_action%ROWTYPE;
    assignee uuid;
BEGIN
    SELECT assignee_employee_id INTO assignee FROM operations.task
        WHERE tenant_id = NEW.tenant_id AND id = NEW.task_id;
    SELECT * INTO previous FROM operations.task_action
        WHERE tenant_id = NEW.tenant_id AND task_id = NEW.task_id
        ORDER BY seq DESC LIMIT 1;
    IF NEW.seq <> COALESCE(previous.seq, 0) + 1 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'operations_task_action_sequence',
            MESSAGE = 'task actions follow one increasing sequence';
    END IF;
    IF previous.seq IS NOT NULL AND (NEW.from_state IS DISTINCT FROM previous.to_state
        OR previous.to_state IN ('COMPLETED', 'CANCELLED') OR NEW.occurred_at < previous.occurred_at) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'operations_task_action_transition',
            MESSAGE = 'a task action starts from the previous resulting state; terminal states are final';
    END IF;
    IF NEW.actor_role = 'employee' AND NEW.actor_employee_id IS DISTINCT FROM assignee THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'operations_task_action_assignee',
            MESSAGE = 'only the assigned employee acts as the employee';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER operations_task_action_guard BEFORE INSERT ON operations.task_action
    FOR EACH ROW EXECUTE FUNCTION operations.task_action_guard();
CREATE TRIGGER operations_task_action_immutable BEFORE UPDATE OR DELETE ON operations.task_action
    FOR EACH ROW EXECUTE FUNCTION operations.task_append_only();
CREATE TRIGGER operations_task_action_no_truncate BEFORE TRUNCATE ON operations.task_action
    FOR EACH STATEMENT EXECUTE FUNCTION operations.task_append_only();

-- Deferred exact match: the projection equals the action trail, whatever the statement order.
CREATE FUNCTION operations.task_exact() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    current_task operations.task%ROWTYPE;
    last_action operations.task_action%ROWTYPE;
    trail integer;
BEGIN
    -- The check runs at commit and reads the row as it stands then: several statements of one
    -- transaction are judged by their final result.
    SELECT * INTO current_task FROM operations.task
        WHERE tenant_id = NEW.tenant_id AND id = NEW.id;
    SELECT * INTO last_action FROM operations.task_action
        WHERE tenant_id = NEW.tenant_id AND task_id = NEW.id AND seq = current_task.version;
    SELECT count(*) INTO trail FROM operations.task_action
        WHERE tenant_id = NEW.tenant_id AND task_id = NEW.id;
    IF last_action.seq IS NULL OR trail <> current_task.version
        OR last_action.to_state <> current_task.state
        OR (last_action.action = 'CREATED' AND NOT (last_action.occurred_at = current_task.created_at
            AND last_action.actor_subject = current_task.created_by))
        OR (last_action.action = 'STARTED'
            AND current_task.started_at IS DISTINCT FROM last_action.occurred_at)
        OR (last_action.action = 'COMPLETED'
            AND (current_task.completed_at IS DISTINCT FROM last_action.occurred_at
            OR current_task.completion_note IS DISTINCT FROM last_action.completion_note))
        OR (last_action.action = 'CANCELLED'
            AND (current_task.cancelled_at IS DISTINCT FROM last_action.occurred_at
            OR current_task.cancel_reason IS DISTINCT FROM last_action.reason_code
            OR current_task.cancel_explanation IS DISTINCT FROM last_action.explanation)) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'operations_task_exact',
            MESSAGE = 'the task must match its last action, version and action trail';
    END IF;
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER operations_task_exact AFTER INSERT OR UPDATE ON operations.task
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION operations.task_exact();

-- An action never runs ahead of the projection.
CREATE FUNCTION operations.task_action_exact() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    projected integer;
BEGIN
    SELECT version INTO projected FROM operations.task
        WHERE tenant_id = NEW.tenant_id AND id = NEW.task_id;
    IF projected IS NULL OR projected < NEW.seq THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'operations_task_action_exact',
            MESSAGE = 'a task action must be reflected by the task version';
    END IF;
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER operations_task_action_exact AFTER INSERT ON operations.task_action
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION operations.task_action_exact();
