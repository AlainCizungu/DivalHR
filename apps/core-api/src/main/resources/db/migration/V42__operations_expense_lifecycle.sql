-- MVP-081 (Issue #169): a linked employee submits a business expense in CDF or USD with at most one
-- receipt per revision; the employee's effective manager approves it or returns it for correction;
-- tenant administrators read (ADR 0031, decisions D81-1 to D81-14). Additive to V41.
--
-- Ownership: the operations module; only the bounded com.divalhr.core.operations package reads or
-- writes these objects. The keys to tenant.organization and people.employee are the documented
-- modular-monolith integrity exception (constraints, never reads), like V16, V24, V25, V32 and V41.
--
-- Classification: Restricted financial/HR operations data. Merchant, description, return reason, the
-- receipt file name and the receipt bytes are entered by users and never leave this schema except in
-- the authorized API responses and the authorized receipt download. Audit metadata, outbox payloads,
-- idempotency records, metrics, logs and error responses never carry them.
--
-- Shape:
--   * expense: the current projection (state, cycle, current revision, optimistic version) and the
--     non-text facts of the current revision that lists filter on. Identity never changes; only a
--     legal transition changes it and every change moves the version by exactly one.
--   * expense_revision: the business evidence. A revision is editable until it is submitted; once
--     submitted it never changes. A returned expense is corrected by appending the next revision.
--   * expense_receipt: bounded receipt evidence (PDF, JPEG or PNG, at most 2 MiB), immutable; a
--     receipt that a submitted revision uses is never deleted. Pilot storage only (PostgreSQL on
--     the encrypted data volume); the production object-storage port is a documented follow-up.
--   * expense_action: append-only evidence with one increasing sequence per expense. The projection
--     version equals the last sequence and the projection matches its last action and its current
--     revision (a deferred exact-match check).
--   * APPROVED and WITHDRAWN are terminal; expenses are never deleted or truncated.
--   * CDF has exponent 0 and USD exponent 2; amounts are integer minor units; nothing converts or
--     combines currencies. Approval is not reimbursement or payment: no payment object exists.
--   * At most 100 open (DRAFT, SUBMITTED, RETURNED) expenses per employee and 5,000 per tenant,
--     enforced in the database under per-tenant and per-employee advisory locks.
--
-- Rollback: db/rollback/V42__rollback.sql (manual, never run by Flyway) restores V41 exactly only
-- while no expense, revision, receipt or action row exists; no override, no retention decision.

CREATE FUNCTION operations.expense_immutable() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'operations_expense_immutable',
        MESSAGE = 'expense evidence is never deleted, truncated or updated; expenses are never deleted';
END
$fn$;

-- ---------------------------------------------------------------------------------------------
-- Expense (current projection)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE operations.expense (
    tenant_id        uuid        NOT NULL,
    id               uuid        NOT NULL,
    employee_id      uuid        NOT NULL,
    state            text        NOT NULL,
    cycle            integer     NOT NULL,
    current_revision integer     NOT NULL,
    version          integer     NOT NULL,
    -- Non-text facts of the current revision (kept equal to it by the deferred exact check).
    business_date    date        NOT NULL,
    currency         text        NOT NULL,
    amount_minor     bigint      NOT NULL,
    category         text        NOT NULL,
    receipt_present  boolean     NOT NULL,
    created_at       timestamptz NOT NULL,
    created_by       text        NOT NULL,
    submitted_at     timestamptz,
    decided_at       timestamptz,
    last_action_at   timestamptz NOT NULL,
    routed_manager_employee_id uuid,
    CONSTRAINT operations_expense_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT operations_expense_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.organization (id),
    CONSTRAINT operations_expense_employee FOREIGN KEY (tenant_id, employee_id)
        REFERENCES people.employee (tenant_id, id),
    CONSTRAINT operations_expense_manager FOREIGN KEY (tenant_id, routed_manager_employee_id)
        REFERENCES people.employee (tenant_id, id),
    CONSTRAINT operations_expense_state
        CHECK (state IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'RETURNED', 'WITHDRAWN')),
    CONSTRAINT operations_expense_cycle CHECK (cycle >= 1 AND cycle = current_revision),
    CONSTRAINT operations_expense_version CHECK (version >= 1),
    CONSTRAINT operations_expense_currency CHECK (
        (currency = 'CDF' AND amount_minor BETWEEN 1 AND 100000000)
        OR (currency = 'USD' AND amount_minor BETWEEN 1 AND 10000000)),
    CONSTRAINT operations_expense_category CHECK (category IN
        ('TRANSPORT', 'MEALS', 'LODGING', 'SUPPLIES', 'COMMUNICATION', 'TRAINING',
         'MEDICAL_WORK_RELATED', 'OTHER')),
    CONSTRAINT operations_expense_actor CHECK (char_length(created_by) BETWEEN 1 AND 255),
    CONSTRAINT operations_expense_shape CHECK (
        (state = 'DRAFT' AND submitted_at IS NULL AND decided_at IS NULL
            AND routed_manager_employee_id IS NULL)
        OR (state = 'SUBMITTED' AND submitted_at IS NOT NULL AND decided_at IS NULL
            AND routed_manager_employee_id IS NOT NULL)
        OR (state IN ('APPROVED', 'WITHDRAWN', 'RETURNED') AND submitted_at IS NOT NULL
            AND decided_at IS NOT NULL AND routed_manager_employee_id IS NOT NULL)),
    CONSTRAINT operations_expense_not_self CHECK (routed_manager_employee_id IS DISTINCT FROM employee_id),
    CONSTRAINT operations_expense_times CHECK (
        last_action_at >= created_at
        AND (submitted_at IS NULL OR submitted_at >= created_at)
        AND (decided_at IS NULL OR decided_at >= COALESCE(submitted_at, created_at)))
);

COMMENT ON TABLE operations.expense IS
    'MVP-081 current projection of one employee expense; never deleted; changed only by a legal transition';

-- Employee and administrator lists: newest creation first (immutable, so cursors stay stable).
CREATE INDEX operations_expense_employee_order ON operations.expense
    (tenant_id, employee_id, created_at DESC, id DESC);
CREATE INDEX operations_expense_tenant_order ON operations.expense
    (tenant_id, created_at DESC, id DESC);
-- Manager queue: submitted only, oldest submission first, driven by the manager's reports.
CREATE INDEX operations_expense_queue ON operations.expense
    (tenant_id, employee_id, submitted_at, id) WHERE state = 'SUBMITTED';
CREATE INDEX operations_expense_open ON operations.expense
    (tenant_id, employee_id) WHERE state IN ('DRAFT', 'SUBMITTED', 'RETURNED');
CREATE INDEX operations_expense_created_by ON operations.expense (tenant_id, created_by, created_at);

-- ---------------------------------------------------------------------------------------------
-- Receipt (bounded, immutable evidence)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE operations.expense_receipt (
    tenant_id   uuid        NOT NULL,
    expense_id  uuid        NOT NULL,
    id          uuid        NOT NULL,
    media_type  text        NOT NULL,
    size_bytes  integer     NOT NULL,
    sha256      bytea       NOT NULL,
    file_name   text        NOT NULL,
    content     bytea       NOT NULL,
    created_at  timestamptz NOT NULL,
    created_by  text        NOT NULL,
    CONSTRAINT operations_expense_receipt_pkey PRIMARY KEY (tenant_id, expense_id, id),
    CONSTRAINT operations_expense_receipt_expense FOREIGN KEY (tenant_id, expense_id)
        REFERENCES operations.expense (tenant_id, id),
    CONSTRAINT operations_expense_receipt_media CHECK (media_type IN
        ('application/pdf', 'image/jpeg', 'image/png')),
    CONSTRAINT operations_expense_receipt_size CHECK (
        size_bytes BETWEEN 1 AND 2097152 AND octet_length(content) = size_bytes),
    -- The digest is computed from the bytes by the server and verified here again.
    CONSTRAINT operations_expense_receipt_digest CHECK (sha256 = sha256(content)),
    -- Defense in depth: the verified media type matches the leading bytes.
    CONSTRAINT operations_expense_receipt_magic CHECK (
        (media_type = 'application/pdf' AND substring(content FROM 1 FOR 5) = '\x255044462d'::bytea)
        OR (media_type = 'image/jpeg' AND substring(content FROM 1 FOR 3) = '\xffd8ff'::bytea)
        OR (media_type = 'image/png'
            AND substring(content FROM 1 FOR 8) = '\x89504e470d0a1a0a'::bytea)),
    -- A display name only: no path separator, markup, control or format character.
    CONSTRAINT operations_expense_receipt_name CHECK (
        char_length(file_name) BETWEEN 1 AND 120
        AND file_name IS NFC NORMALIZED
        AND file_name !~ '^[[:space:]]' AND file_name !~ '[[:space:]]$'
        AND file_name !~ '[\u0001-\u001F\u007F-\u009F\u00AD\u200B-\u200F\u2028-\u202E\u2060-\u2064\u2066-\u206F\uFEFF\uE000-\uF8FF\uFFF9-\uFFFB]'
        AND file_name !~ '[<>\\/:"|?*]'),
    CONSTRAINT operations_expense_receipt_actor CHECK (char_length(created_by) BETWEEN 1 AND 255)
);

COMMENT ON TABLE operations.expense_receipt IS
    'MVP-081 pilot receipt bytes (at most 2 MiB each) on the encrypted data volume; immutable; production moves behind an object-storage port';

-- ---------------------------------------------------------------------------------------------
-- Revision (business evidence)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE operations.expense_revision (
    tenant_id     uuid        NOT NULL,
    expense_id    uuid        NOT NULL,
    revision      integer     NOT NULL,
    business_date date        NOT NULL,
    currency      text        NOT NULL,
    exponent      smallint    NOT NULL,
    amount_minor  bigint      NOT NULL,
    category      text        NOT NULL,
    merchant      text        NOT NULL,
    description   text        NOT NULL,
    receipt_id    uuid,
    created_at    timestamptz NOT NULL,
    submitted_at  timestamptz,
    CONSTRAINT operations_expense_revision_pkey PRIMARY KEY (tenant_id, expense_id, revision),
    CONSTRAINT operations_expense_revision_expense FOREIGN KEY (tenant_id, expense_id)
        REFERENCES operations.expense (tenant_id, id),
    CONSTRAINT operations_expense_revision_receipt FOREIGN KEY (tenant_id, expense_id, receipt_id)
        REFERENCES operations.expense_receipt (tenant_id, expense_id, id),
    CONSTRAINT operations_expense_revision_number CHECK (revision >= 1),
    -- Canonical money: the exponent is the currency's ISO exponent and the amount is in minor units.
    CONSTRAINT operations_expense_revision_money CHECK (
        (currency = 'CDF' AND exponent = 0 AND amount_minor BETWEEN 1 AND 100000000)
        OR (currency = 'USD' AND exponent = 2 AND amount_minor BETWEEN 1 AND 10000000)),
    CONSTRAINT operations_expense_revision_category CHECK (category IN
        ('TRANSPORT', 'MEALS', 'LODGING', 'SUPPLIES', 'COMMUNICATION', 'TRAINING',
         'MEDICAL_WORK_RELATED', 'OTHER')),
    CONSTRAINT operations_expense_revision_merchant CHECK (operations.task_text_valid(merchant, 1, 120)),
    CONSTRAINT operations_expense_revision_description
        CHECK (operations.task_text_valid(description, 10, 1000)),
    CONSTRAINT operations_expense_revision_times CHECK (submitted_at IS NULL OR submitted_at >= created_at)
);

COMMENT ON TABLE operations.expense_revision IS
    'MVP-081 expense business evidence; editable only until submitted, immutable afterwards';

-- ---------------------------------------------------------------------------------------------
-- Action (append-only evidence)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE operations.expense_action (
    tenant_id           uuid        NOT NULL,
    expense_id          uuid        NOT NULL,
    seq                 integer     NOT NULL,
    action              text        NOT NULL,
    occurred_at         timestamptz NOT NULL,
    actor_subject       text        NOT NULL,
    actor_role          text        NOT NULL,
    actor_employee_id   uuid        NOT NULL,
    from_state          text,
    to_state            text        NOT NULL,
    revision            integer     NOT NULL,
    cycle               integer     NOT NULL,
    -- The manager routed at submission, or the deciding manager.
    manager_employee_id uuid,
    receipt_present     boolean     NOT NULL,
    return_reason       text,
    CONSTRAINT operations_expense_action_pkey PRIMARY KEY (tenant_id, expense_id, seq),
    CONSTRAINT operations_expense_action_expense FOREIGN KEY (tenant_id, expense_id)
        REFERENCES operations.expense (tenant_id, id),
    CONSTRAINT operations_expense_action_actor_employee FOREIGN KEY (tenant_id, actor_employee_id)
        REFERENCES people.employee (tenant_id, id),
    CONSTRAINT operations_expense_action_manager FOREIGN KEY (tenant_id, manager_employee_id)
        REFERENCES people.employee (tenant_id, id),
    CONSTRAINT operations_expense_action_seq CHECK (seq >= 1 AND revision >= 1 AND cycle = revision),
    CONSTRAINT operations_expense_action_kind CHECK (action IN
        ('CREATED', 'DRAFT_UPDATED', 'RECEIPT_SET', 'RECEIPT_REMOVED', 'SUBMITTED', 'WITHDRAWN',
         'APPROVED', 'RETURNED')),
    CONSTRAINT operations_expense_action_actor CHECK (
        char_length(actor_subject) BETWEEN 1 AND 255 AND actor_role IN ('employee', 'manager')),
    CONSTRAINT operations_expense_action_states CHECK (
        (action = 'CREATED' AND from_state IS NULL AND to_state = 'DRAFT' AND actor_role = 'employee')
        OR (action IN ('DRAFT_UPDATED', 'RECEIPT_SET', 'RECEIPT_REMOVED')
            AND from_state = to_state AND to_state IN ('DRAFT', 'RETURNED') AND actor_role = 'employee')
        OR (action = 'SUBMITTED' AND from_state IN ('DRAFT', 'RETURNED') AND to_state = 'SUBMITTED'
            AND actor_role = 'employee')
        OR (action = 'WITHDRAWN' AND from_state = 'SUBMITTED' AND to_state = 'WITHDRAWN'
            AND actor_role = 'employee')
        OR (action = 'APPROVED' AND from_state = 'SUBMITTED' AND to_state = 'APPROVED'
            AND actor_role = 'manager')
        OR (action = 'RETURNED' AND from_state = 'SUBMITTED' AND to_state = 'RETURNED'
            AND actor_role = 'manager')),
    CONSTRAINT operations_expense_action_manager_shape CHECK (
        (action = 'SUBMITTED' AND manager_employee_id IS NOT NULL
            AND manager_employee_id <> actor_employee_id)
        OR (action IN ('APPROVED', 'RETURNED') AND manager_employee_id = actor_employee_id)
        OR (action NOT IN ('SUBMITTED', 'APPROVED', 'RETURNED') AND manager_employee_id IS NULL)),
    CONSTRAINT operations_expense_action_reason CHECK (
        (action = 'RETURNED' AND return_reason IS NOT NULL
            AND operations.task_text_valid(return_reason, 10, 500))
        OR (action <> 'RETURNED' AND return_reason IS NULL))
);

-- Hourly quotas (D81-13) count the subject's own committed actions in the last hour.
CREATE INDEX operations_expense_action_actor ON operations.expense_action
    (tenant_id, actor_subject, occurred_at);
CREATE INDEX operations_expense_action_tenant ON operations.expense_action
    (tenant_id, occurred_at);

COMMENT ON TABLE operations.expense_action IS
    'MVP-081 append-only expense actions: one increasing sequence per expense, never updated or deleted';

-- ---------------------------------------------------------------------------------------------
-- Guards
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION operations.expense_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    open_tenant integer;
    open_employee integer;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.state <> 'DRAFT' OR NEW.version <> 1 OR NEW.current_revision <> 1 THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'operations_expense_initial',
                MESSAGE = 'an expense starts as a draft, revision 1, version 1';
        END IF;
        -- One creation per tenant at a time keeps both limits exact.
        PERFORM pg_advisory_xact_lock(hashtextextended('operations.expense.open:' || NEW.tenant_id::text, 0));
        SELECT count(*) INTO open_tenant FROM operations.expense
            WHERE tenant_id = NEW.tenant_id AND state IN ('DRAFT', 'SUBMITTED', 'RETURNED');
        IF open_tenant >= 5000 THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'operations_expense_tenant_limit',
                MESSAGE = 'at most 5000 open expenses per tenant';
        END IF;
        SELECT count(*) INTO open_employee FROM operations.expense
            WHERE tenant_id = NEW.tenant_id AND employee_id = NEW.employee_id
                AND state IN ('DRAFT', 'SUBMITTED', 'RETURNED');
        IF open_employee >= 100 THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'operations_expense_employee_limit',
                MESSAGE = 'at most 100 open expenses per employee';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.state IN ('APPROVED', 'WITHDRAWN') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'operations_expense_terminal',
            MESSAGE = 'an approved or withdrawn expense never changes';
    END IF;
    IF NEW.tenant_id <> OLD.tenant_id OR NEW.id <> OLD.id OR NEW.employee_id <> OLD.employee_id
        OR NEW.created_at <> OLD.created_at OR NEW.created_by <> OLD.created_by THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'operations_expense_identity',
            MESSAGE = 'expense identity is immutable';
    END IF;
    IF NEW.version <> OLD.version + 1
        OR NOT ((OLD.state = 'DRAFT' AND NEW.state IN ('DRAFT', 'SUBMITTED'))
            OR (OLD.state = 'RETURNED' AND NEW.state IN ('RETURNED', 'SUBMITTED'))
            OR (OLD.state = 'SUBMITTED' AND NEW.state IN ('APPROVED', 'RETURNED', 'WITHDRAWN'))) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'operations_expense_transition',
            MESSAGE = 'illegal expense transition or version';
    END IF;
    -- The revision moves only when a returned expense is corrected, one revision at a time.
    IF NEW.current_revision <> OLD.current_revision
        AND NOT (OLD.state = 'RETURNED' AND NEW.state = 'RETURNED'
            AND NEW.current_revision = OLD.current_revision + 1) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'operations_expense_revision_move',
            MESSAGE = 'a new revision is appended only to correct a returned expense';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER operations_expense_guard BEFORE INSERT OR UPDATE ON operations.expense
    FOR EACH ROW EXECUTE FUNCTION operations.expense_guard();
CREATE TRIGGER operations_expense_no_delete BEFORE DELETE ON operations.expense
    FOR EACH ROW EXECUTE FUNCTION operations.expense_immutable();
CREATE TRIGGER operations_expense_no_truncate BEFORE TRUNCATE ON operations.expense
    FOR EACH STATEMENT EXECUTE FUNCTION operations.expense_immutable();

CREATE FUNCTION operations.expense_revision_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    parent operations.expense%ROWTYPE;
    previous operations.expense_revision%ROWTYPE;
BEGIN
    SELECT * INTO parent FROM operations.expense
        WHERE tenant_id = NEW.tenant_id AND id = NEW.expense_id;
    IF TG_OP = 'INSERT' THEN
        SELECT * INTO previous FROM operations.expense_revision
            WHERE tenant_id = NEW.tenant_id AND expense_id = NEW.expense_id
            ORDER BY revision DESC LIMIT 1;
        IF NEW.revision <> COALESCE(previous.revision, 0) + 1 OR NEW.submitted_at IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'operations_expense_revision_sequence',
                MESSAGE = 'revisions follow one increasing sequence and start unsubmitted';
        END IF;
        -- Revision 1 belongs to the new draft; a later one only corrects a returned, decided revision.
        IF previous.revision IS NOT NULL AND (previous.submitted_at IS NULL OR parent.state <> 'RETURNED') THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'operations_expense_revision_append',
                MESSAGE = 'a revision is appended only after a returned, submitted revision';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.submitted_at IS NOT NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'operations_expense_revision_immutable',
            MESSAGE = 'a submitted revision never changes';
    END IF;
    IF NEW.tenant_id <> OLD.tenant_id OR NEW.expense_id <> OLD.expense_id
        OR NEW.revision <> OLD.revision OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'operations_expense_revision_identity',
            MESSAGE = 'revision identity is immutable';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER operations_expense_revision_guard BEFORE INSERT OR UPDATE ON operations.expense_revision
    FOR EACH ROW EXECUTE FUNCTION operations.expense_revision_guard();
CREATE TRIGGER operations_expense_revision_no_delete BEFORE DELETE ON operations.expense_revision
    FOR EACH ROW EXECUTE FUNCTION operations.expense_immutable();
CREATE TRIGGER operations_expense_revision_no_truncate BEFORE TRUNCATE ON operations.expense_revision
    FOR EACH STATEMENT EXECUTE FUNCTION operations.expense_immutable();

CREATE FUNCTION operations.expense_receipt_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'operations_expense_receipt_immutable',
            MESSAGE = 'receipt evidence is never updated';
    END IF;
    -- DELETE: only an unused or draft-only receipt may go (a replaced draft attachment).
    IF EXISTS (SELECT 1 FROM operations.expense_revision r
            WHERE r.tenant_id = OLD.tenant_id AND r.expense_id = OLD.expense_id
                AND r.receipt_id = OLD.id AND r.submitted_at IS NOT NULL) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'operations_expense_receipt_submitted',
            MESSAGE = 'a receipt that a submitted revision uses is never deleted';
    END IF;
    RETURN OLD;
END
$fn$;

CREATE TRIGGER operations_expense_receipt_guard BEFORE UPDATE OR DELETE ON operations.expense_receipt
    FOR EACH ROW EXECUTE FUNCTION operations.expense_receipt_guard();
CREATE TRIGGER operations_expense_receipt_no_truncate BEFORE TRUNCATE ON operations.expense_receipt
    FOR EACH STATEMENT EXECUTE FUNCTION operations.expense_immutable();

CREATE FUNCTION operations.expense_action_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    previous operations.expense_action%ROWTYPE;
    owner uuid;
BEGIN
    SELECT employee_id INTO owner FROM operations.expense
        WHERE tenant_id = NEW.tenant_id AND id = NEW.expense_id;
    SELECT * INTO previous FROM operations.expense_action
        WHERE tenant_id = NEW.tenant_id AND expense_id = NEW.expense_id
        ORDER BY seq DESC LIMIT 1;
    IF NEW.seq <> COALESCE(previous.seq, 0) + 1 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'operations_expense_action_sequence',
            MESSAGE = 'expense actions follow one increasing sequence';
    END IF;
    IF previous.seq IS NOT NULL AND (NEW.from_state IS DISTINCT FROM previous.to_state
        OR previous.to_state IN ('APPROVED', 'WITHDRAWN') OR NEW.occurred_at < previous.occurred_at
        OR NEW.revision < previous.revision OR NEW.revision > previous.revision + 1) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'operations_expense_action_transition',
            MESSAGE = 'an expense action starts from the previous resulting state; terminal states are final';
    END IF;
    IF NEW.actor_role = 'employee' AND NEW.actor_employee_id IS DISTINCT FROM owner THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'operations_expense_action_owner',
            MESSAGE = 'only the owning employee acts as the employee';
    END IF;
    IF NEW.actor_role = 'manager' AND NEW.actor_employee_id IS NOT DISTINCT FROM owner THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'operations_expense_action_self_decision',
            MESSAGE = 'nobody decides their own expense';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER operations_expense_action_guard BEFORE INSERT ON operations.expense_action
    FOR EACH ROW EXECUTE FUNCTION operations.expense_action_guard();
CREATE TRIGGER operations_expense_action_immutable BEFORE UPDATE OR DELETE ON operations.expense_action
    FOR EACH ROW EXECUTE FUNCTION operations.expense_immutable();
CREATE TRIGGER operations_expense_action_no_truncate BEFORE TRUNCATE ON operations.expense_action
    FOR EACH STATEMENT EXECUTE FUNCTION operations.expense_immutable();

-- Deferred exact match: the projection equals the action trail and the current revision, whatever
-- the statement order.
CREATE FUNCTION operations.expense_exact() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    cur operations.expense%ROWTYPE;
    last_action operations.expense_action%ROWTYPE;
    rev operations.expense_revision%ROWTYPE;
    trail integer;
    revisions integer;
    submitted_revisions integer;
    submitted_actions integer;
    decided boolean;
BEGIN
    SELECT * INTO cur FROM operations.expense WHERE tenant_id = NEW.tenant_id AND id = NEW.id;
    SELECT * INTO last_action FROM operations.expense_action
        WHERE tenant_id = NEW.tenant_id AND expense_id = NEW.id AND seq = cur.version;
    SELECT count(*) INTO trail FROM operations.expense_action
        WHERE tenant_id = NEW.tenant_id AND expense_id = NEW.id;
    SELECT * INTO rev FROM operations.expense_revision
        WHERE tenant_id = NEW.tenant_id AND expense_id = NEW.id AND revision = cur.current_revision;
    SELECT count(*), count(*) FILTER (WHERE submitted_at IS NOT NULL) INTO revisions, submitted_revisions
        FROM operations.expense_revision WHERE tenant_id = NEW.tenant_id AND expense_id = NEW.id;
    SELECT count(*) INTO submitted_actions FROM operations.expense_action
        WHERE tenant_id = NEW.tenant_id AND expense_id = NEW.id AND action = 'SUBMITTED';
    decided := last_action.action IN ('APPROVED', 'RETURNED', 'WITHDRAWN');
    IF last_action.seq IS NULL OR trail <> cur.version OR rev.revision IS NULL
        OR revisions <> cur.current_revision OR submitted_revisions <> submitted_actions
        OR last_action.to_state <> cur.state OR last_action.revision <> cur.current_revision
        OR last_action.cycle <> cur.cycle
        OR last_action.receipt_present <> (rev.receipt_id IS NOT NULL)
        OR cur.receipt_present <> (rev.receipt_id IS NOT NULL)
        OR cur.business_date <> rev.business_date OR cur.currency <> rev.currency
        OR cur.amount_minor <> rev.amount_minor OR cur.category <> rev.category
        -- A revision is submitted exactly when the last action is a submission or a decision on it.
        OR (last_action.action IN ('CREATED', 'DRAFT_UPDATED', 'RECEIPT_SET', 'RECEIPT_REMOVED')
            AND rev.submitted_at IS NOT NULL)
        OR (last_action.action NOT IN ('CREATED', 'DRAFT_UPDATED', 'RECEIPT_SET', 'RECEIPT_REMOVED')
            AND rev.submitted_at IS NULL)
        OR (last_action.action = 'CREATED' AND NOT (last_action.occurred_at = cur.created_at
            AND last_action.actor_subject = cur.created_by AND cur.current_revision = 1))
        OR (last_action.action = 'SUBMITTED'
            AND (rev.submitted_at IS DISTINCT FROM last_action.occurred_at
                OR cur.submitted_at IS DISTINCT FROM last_action.occurred_at
                OR cur.routed_manager_employee_id IS DISTINCT FROM last_action.manager_employee_id))
        OR (decided AND cur.decided_at IS DISTINCT FROM last_action.occurred_at)
        OR cur.last_action_at <> last_action.occurred_at THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'operations_expense_exact',
            MESSAGE = 'the expense must match its last action, version, action trail and current revision';
    END IF;
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER operations_expense_exact AFTER INSERT OR UPDATE ON operations.expense
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION operations.expense_exact();

-- The revision and receipt of an expense are judged with the expense itself at commit.
CREATE FUNCTION operations.expense_children_exact() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    projected integer;
BEGIN
    IF TG_TABLE_NAME = 'expense_action' THEN
        SELECT version INTO projected FROM operations.expense
            WHERE tenant_id = NEW.tenant_id AND id = NEW.expense_id;
        IF projected IS NULL OR projected < NEW.seq THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'operations_expense_action_exact',
                MESSAGE = 'an expense action must be reflected by the expense version';
        END IF;
    ELSE
        -- A revision change must be matched by the expense it belongs to in the same transaction.
        PERFORM 1 FROM operations.expense
            WHERE tenant_id = NEW.tenant_id AND id = NEW.expense_id
                AND current_revision >= NEW.revision;
        IF NOT FOUND THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'operations_expense_revision_exact',
                MESSAGE = 'a revision must be reflected by the expense';
        END IF;
    END IF;
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER operations_expense_action_exact AFTER INSERT ON operations.expense_action
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION operations.expense_children_exact();
CREATE CONSTRAINT TRIGGER operations_expense_revision_exact AFTER INSERT ON operations.expense_revision
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION operations.expense_children_exact();
