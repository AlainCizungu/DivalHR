-- MVP-050A (Issue #99): a tenant administrator previews and publishes one immutable work schedule
-- for one active site and a bounded calendar period: 1 to 500 slots, each one required worker, with
-- English and French labels and an optional assigned employee. Unassigned slots are uncovered
-- positions. No draft, amendment, cancellation, recurrence, break, pay, note or notification.
--
-- Ownership: the people module owns both tables; only the bounded com.divalhr.core.people.schedule
-- package reads or writes them. Keys reference people's own employee rows and the tenant root, and
-- (a deliberate integrity exception, ADR 0014, D50A-6) the tenant module's site through the
-- composite key tenant.site (tenant_id, id), so a schedule can never name another tenant's site.
-- Application code still reads sites only through the OrganizationPlacementDirectory port.
--
-- Classification: schedules and assignments are Restricted HR/Operations; slot labels are internal
-- operational data. The publisher subject is evidence only and is never returned by the API.
--
-- Database authority (V25):
--   * schedules and slots are published evidence: never updated, deleted or truncated
--     (work_schedule_immutable, _no_truncate; work_schedule_slot_immutable, _no_truncate). There is
--     no mutable status column;
--   * a schedule names one site of its tenant (work_schedule_site), an inclusive period of 1 to 31
--     days (work_schedule_period_valid) and the organization's IANA time zone as a snapshot
--     (work_schedule_timezone_valid: a name PostgreSQL knows);
--   * one published schedule covers a site on any date (work_schedule_site_no_overlap, btree_gist);
--   * a slot belongs to exactly one schedule of its tenant (work_schedule_slot_schedule), has local
--     minute-precision boundaries, starts on a schedule date, ends after it and at most on the next
--     date, lasts at most 24 hours, and its instants are its local times in the schedule's time zone
--     where that local time exists exactly once (work_schedule_slot_consistent);
--   * an assigned employee is an employee of the tenant (work_schedule_slot_employee) and has no
--     overlapping assigned slot in any published schedule, any site
--     (work_schedule_slot_employee_no_overlap, btree_gist, half-open instants);
--   * labels follow the closed plain-text grammar people.work_schedule_label_valid in both
--     languages;
--   * a schedule commits only with exactly its stated slot and assigned counts, 1 to 500 slots
--     (work_schedule_complete, deferred, on both tables).
-- The exclusions sit on the evidence itself: in MVP-050A no published row is ever superseded, so no
-- historical row can block a later valid publication. A story that adds cancellation or
-- replacement (MVP-050B) moves them to guarded operational projections first (ADR 0014).
--
-- Indexes: work_schedule_site_no_overlap (tenant, site, period) is the overlap check;
-- work_schedule_list_order the administrators' keyset; work_schedule_slot_position the schedule's
-- slots in publication order; work_schedule_slot_employee_no_overlap the employee conflict check;
-- work_schedule_slot_employee_start the employee's own shifts in start order.
--
-- Rollback: db/rollback/V25__rollback.sql (manual, never run by Flyway). It restores V24 exactly
-- only while no schedule or slot exists; there is no override, and it never touches
-- flyway_schema_history.

-- ---------------------------------------------------------------------------------------------
-- Shared rules
-- ---------------------------------------------------------------------------------------------

-- Closed plain-text grammar version 1 of a slot label: NFC, 2 to 60 characters, Latin letters
-- (ASCII, Latin-1 and Latin Extended-A/B) with combining accents, digits, single spaces and
-- . , ' ’ - ( ) / & # °; starting and ending with a letter, digit or closing parenthesis (a final
-- period is allowed); no dot directly before a letter, so no host or link shape. No markup,
-- control character, link or e-mail address can be written ('<', '>', ':' and '@' are outside the
-- grammar).
CREATE FUNCTION people.work_schedule_label_valid(label text) RETURNS boolean
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $fn$
SELECT label IS NFC NORMALIZED
    AND char_length(label) BETWEEN 2 AND 60
    AND label ~ '^[A-Za-z0-9À-ÖØ-öø-ɏ]([A-Za-z0-9À-ÖØ-öø-ɏ̀-ͯ .,''’()/&#°-]*[A-Za-z0-9À-ÖØ-öø-ɏ̀-ͯ).])?$'
    AND label !~ '  '
    AND label !~ '\.[A-Za-zÀ-ÖØ-öø-ɏ]'
$fn$;

COMMENT ON FUNCTION people.work_schedule_label_valid(text) IS
    'MVP-050A slot label grammar version 1 (mirrored by WorkScheduleLabelGrammar).';

-- ---------------------------------------------------------------------------------------------
-- Schedules: published evidence, one per site and date
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION people.work_schedule_immutable() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'work_schedule_immutable',
        MESSAGE = 'published work schedules are never changed or deleted';
END
$fn$;

CREATE FUNCTION people.work_schedule_no_truncate() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'work_schedule_no_truncate',
        MESSAGE = 'published work schedules are never truncated';
END
$fn$;

CREATE TABLE people.work_schedule (
    id              uuid        PRIMARY KEY,
    tenant_id       uuid        NOT NULL,
    site_id         uuid        NOT NULL,
    first_date      date        NOT NULL,
    last_date       date        NOT NULL,
    timezone        text        NOT NULL,
    slot_count      integer     NOT NULL,
    assigned_count  integer     NOT NULL,
    published_at    timestamptz NOT NULL,
    published_by    text        NOT NULL,
    CONSTRAINT work_schedule_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenant.organization (id),
    CONSTRAINT work_schedule_tenant_id_unique UNIQUE (tenant_id, id),
    CONSTRAINT work_schedule_site FOREIGN KEY (tenant_id, site_id)
        REFERENCES tenant.site (tenant_id, id),
    CONSTRAINT work_schedule_period_valid CHECK (
        first_date BETWEEN DATE '2000-01-01' AND DATE '2999-12-31'
        AND last_date >= first_date AND last_date - first_date <= 30),
    CONSTRAINT work_schedule_timezone_format
        CHECK (timezone ~ '^[A-Za-z][A-Za-z0-9_+-]*(/[A-Za-z0-9_+-]+){0,2}$'
            AND char_length(timezone) BETWEEN 3 AND 64),
    CONSTRAINT work_schedule_counts_valid CHECK (
        slot_count BETWEEN 1 AND 500 AND assigned_count BETWEEN 0 AND slot_count),
    CONSTRAINT work_schedule_published_by_length
        CHECK (char_length(published_by) BETWEEN 1 AND 255),
    CONSTRAINT work_schedule_site_no_overlap EXCLUDE USING gist (
        tenant_id WITH =, site_id WITH =, daterange(first_date, last_date, '[]') WITH &&)
);

COMMENT ON TABLE people.work_schedule IS
    'MVP-050A published work schedule: immutable evidence, one per site and date.';

CREATE INDEX work_schedule_list_order
    ON people.work_schedule (tenant_id, first_date DESC, id DESC);

CREATE TRIGGER work_schedule_immutable
    BEFORE UPDATE OR DELETE ON people.work_schedule
    FOR EACH ROW EXECUTE FUNCTION people.work_schedule_immutable();
CREATE TRIGGER work_schedule_no_truncate
    BEFORE TRUNCATE ON people.work_schedule
    FOR EACH STATEMENT EXECUTE FUNCTION people.work_schedule_no_truncate();

-- The time zone snapshot is a name PostgreSQL's time zone database knows.
CREATE FUNCTION people.work_schedule_timezone_valid() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    -- A name outside the IANA shape is left to work_schedule_timezone_format.
    IF NEW.timezone ~ '^[A-Za-z][A-Za-z0-9_+-]*(/[A-Za-z0-9_+-]+){0,2}$'
        AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_timezone_names WHERE name = NEW.timezone) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'work_schedule_timezone_valid',
            MESSAGE = 'a work schedule names a known time zone';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER work_schedule_timezone_valid
    BEFORE INSERT ON people.work_schedule
    FOR EACH ROW EXECUTE FUNCTION people.work_schedule_timezone_valid();

-- ---------------------------------------------------------------------------------------------
-- Slots: one required worker each, published with their schedule
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION people.work_schedule_slot_immutable() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'work_schedule_slot_immutable',
        MESSAGE = 'published work schedule slots are never changed or deleted';
END
$fn$;

CREATE FUNCTION people.work_schedule_slot_no_truncate() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'work_schedule_slot_no_truncate',
        MESSAGE = 'published work schedule slots are never truncated';
END
$fn$;

CREATE TABLE people.work_schedule_slot (
    id                   uuid        PRIMARY KEY,
    tenant_id            uuid        NOT NULL,
    schedule_id          uuid        NOT NULL,
    position             integer     NOT NULL,
    start_local          timestamp   NOT NULL,
    end_local            timestamp   NOT NULL,
    start_at             timestamptz NOT NULL,
    end_at               timestamptz NOT NULL,
    label_en             text        NOT NULL,
    label_fr             text        NOT NULL,
    assigned_employee_id uuid,
    CONSTRAINT work_schedule_slot_tenant_fk FOREIGN KEY (tenant_id)
        REFERENCES tenant.organization (id),
    CONSTRAINT work_schedule_slot_schedule FOREIGN KEY (tenant_id, schedule_id)
        REFERENCES people.work_schedule (tenant_id, id),
    CONSTRAINT work_schedule_slot_employee FOREIGN KEY (tenant_id, assigned_employee_id)
        REFERENCES people.employee (tenant_id, id),
    CONSTRAINT work_schedule_slot_position_valid CHECK (position BETWEEN 1 AND 500),
    CONSTRAINT work_schedule_slot_minutes CHECK (
        date_trunc('minute', start_local) = start_local
        AND date_trunc('minute', end_local) = end_local),
    CONSTRAINT work_schedule_slot_interval_valid CHECK (
        end_local > start_local
        AND end_local::date <= start_local::date + 1
        AND end_at > start_at
        AND end_at - start_at <= interval '24 hours'),
    CONSTRAINT work_schedule_slot_label_en_valid CHECK (people.work_schedule_label_valid(label_en)),
    CONSTRAINT work_schedule_slot_label_fr_valid CHECK (people.work_schedule_label_valid(label_fr)),
    CONSTRAINT work_schedule_slot_employee_no_overlap EXCLUDE USING gist (
        tenant_id WITH =, assigned_employee_id WITH =, tstzrange(start_at, end_at, '[)') WITH &&)
        WHERE (assigned_employee_id IS NOT NULL)
);

COMMENT ON TABLE people.work_schedule_slot IS
    'MVP-050A published schedule slot: one required worker, immutable evidence; unassigned = uncovered.';

CREATE UNIQUE INDEX work_schedule_slot_position
    ON people.work_schedule_slot (tenant_id, schedule_id, position);
CREATE INDEX work_schedule_slot_employee_start
    ON people.work_schedule_slot (tenant_id, assigned_employee_id, start_at, id)
    WHERE assigned_employee_id IS NOT NULL;

CREATE TRIGGER work_schedule_slot_immutable
    BEFORE UPDATE OR DELETE ON people.work_schedule_slot
    FOR EACH ROW EXECUTE FUNCTION people.work_schedule_slot_immutable();
CREATE TRIGGER work_schedule_slot_no_truncate
    BEFORE TRUNCATE ON people.work_schedule_slot
    FOR EACH STATEMENT EXECUTE FUNCTION people.work_schedule_slot_no_truncate();

-- Whether a local time exists exactly once in a time zone, at the given instant: the instant maps
-- back to it, and no other offset in effect within three hours of it (time zone transitions are
-- never closer, and never more than two hours) maps another instant to it.
CREATE FUNCTION people.work_schedule_local_unique(p_local timestamp, p_zone text, p_at timestamptz)
    RETURNS boolean
    LANGUAGE plpgsql STABLE
AS $fn$
DECLARE
    v_probe  timestamptz;
    v_offset interval;
    v_other  timestamptz;
BEGIN
    IF (p_at AT TIME ZONE p_zone) IS DISTINCT FROM p_local THEN
        RETURN false;
    END IF;
    FOREACH v_probe IN ARRAY ARRAY[p_at - interval '3 hours', p_at + interval '3 hours'] LOOP
        v_offset := (v_probe AT TIME ZONE p_zone) - (v_probe AT TIME ZONE 'UTC');
        v_other := (p_local - v_offset) AT TIME ZONE 'UTC';
        IF v_other <> p_at AND (v_other AT TIME ZONE p_zone) = p_local THEN
            RETURN false;
        END IF;
    END LOOP;
    RETURN true;
END
$fn$;

-- A slot starts on a date of its schedule, and its instants are its local times in the schedule's
-- time zone, each local time existing exactly once there (no gap, no repeated hour).
CREATE FUNCTION people.work_schedule_slot_consistent() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_first date;
    v_last  date;
    v_zone  text;
BEGIN
    SELECT s.first_date, s.last_date, s.timezone INTO v_first, v_last, v_zone
        FROM people.work_schedule s
        WHERE s.tenant_id = NEW.tenant_id AND s.id = NEW.schedule_id;
    IF NOT FOUND THEN
        -- No schedule of this tenant: left to the work_schedule_slot_schedule foreign key.
        RETURN NEW;
    END IF;
    IF NEW.start_local::date NOT BETWEEN v_first AND v_last
        OR NOT people.work_schedule_local_unique(NEW.start_local, v_zone, NEW.start_at)
        OR NOT people.work_schedule_local_unique(NEW.end_local, v_zone, NEW.end_at) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'work_schedule_slot_consistent',
            MESSAGE = 'a slot starts on a schedule date at existing, unambiguous local times';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER work_schedule_slot_consistent
    BEFORE INSERT ON people.work_schedule_slot
    FOR EACH ROW EXECUTE FUNCTION people.work_schedule_slot_consistent();

-- ---------------------------------------------------------------------------------------------
-- Completeness: a schedule commits with exactly its stated slots (deferred, both tables)
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION people.work_schedule_complete() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_tenant   uuid;
    v_schedule uuid;
    v_slots    integer;
    v_assigned integer;
BEGIN
    IF TG_TABLE_NAME = 'work_schedule' THEN
        v_tenant := NEW.tenant_id;
        v_schedule := NEW.id;
    ELSE
        v_tenant := NEW.tenant_id;
        v_schedule := NEW.schedule_id;
    END IF;
    SELECT count(*), count(*) FILTER (WHERE l.assigned_employee_id IS NOT NULL)
        INTO v_slots, v_assigned
        FROM people.work_schedule_slot l
        WHERE l.tenant_id = v_tenant AND l.schedule_id = v_schedule;
    IF NOT EXISTS (SELECT 1 FROM people.work_schedule s
            WHERE s.tenant_id = v_tenant AND s.id = v_schedule
                AND s.slot_count = v_slots AND s.assigned_count = v_assigned
                AND v_slots BETWEEN 1 AND 500)
        OR EXISTS (SELECT 1 FROM people.work_schedule_slot l
            WHERE l.tenant_id = v_tenant AND l.schedule_id = v_schedule
                AND l.position > v_slots) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'work_schedule_complete',
            MESSAGE = 'a work schedule commits with exactly its stated slots';
    END IF;
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER work_schedule_complete
    AFTER INSERT ON people.work_schedule
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION people.work_schedule_complete();
CREATE CONSTRAINT TRIGGER work_schedule_slot_complete
    AFTER INSERT ON people.work_schedule_slot
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION people.work_schedule_complete();
