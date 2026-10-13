-- MVP-061A (Issue #122): an employee who was offline submits one scheduled check-in or check-out
-- later, exactly once. Backend synchronization only; the browser-held queue is MVP-061B.
--
-- Ownership: the people module; only the bounded com.divalhr.core.people.schedule package reads
-- or writes these objects (ADR 0016, amended for MVP-061A).
--
-- Classification: Restricted HR/Operations. The claimed device time is untrusted evidence supplied
-- by the client, never proof of presence; the receipt instant is the server's.
--
-- Shape (A61A-5): the smallest append-only extension of people.attendance_event; no new table.
--   * recording_method gains EMPLOYEE_OFFLINE_SYNC. For such an event recorded_at is the claimed
--     device occurrence instant (the instant the existing guards, windows and projection already
--     check), and three new columns hold the offline provenance: client_event_id (the immutable
--     client identity of the event), device_offset_minutes (the device's UTC offset at capture,
--     -840 to +840) and received_at (the server receipt instant);
--   * the three columns are all present exactly for EMPLOYEE_OFFLINE_SYNC and all absent for SELF
--     and SUPERVISOR events, so online and supervisor evidence keeps its exact V29 shape
--     (attendance_event_offline_shape). Existing rows are SELF or SUPERVISOR: no backfill;
--   * the synchronization safeguards (fixed pilot constants of Attendance, mirrored here; not
--     tenant-configurable): the claimed time is at most 5 minutes after the receipt and the
--     receipt at most 72 hours after the claimed time (attendance_event_offline_receipt); the
--     receipt is at most 72 hours after the applicable window closed, the scheduled end for a
--     check-in and the check-out deadline (scheduled end plus 24 hours) for a check-out
--     (attendance_event_offline_guard). With the existing windows the last bound is implied by
--     the first two; it is kept as an explicit database rule;
--   * one event per tenant, employee and client event (attendance_event_client_event_unique; the
--     index leads with the tenant and the client event, so a replay lookup is one index probe);
--   * every V28 and V29 rule applies unchanged to the claimed instant: the check-in guard
--     (operational schedule, current coverage at receipt, window, qualifying employment), the
--     check-out guard (own open record, strictly after the accepted check-in, window), one check-in
--     and one check-out per record, the one-open exclusion, the projection's own constraints and
--     the deferred agreement; the event, its provenance and both instants are immutable
--     (attendance_event_immutable) and never truncated.
--
-- Rollback: db/rollback/V30__rollback.sql (manual, never run by Flyway). It restores V29 exactly
-- only while no EMPLOYEE_OFFLINE_SYNC event exists; there is no override, and it never touches
-- flyway_schema_history.

ALTER TABLE people.attendance_event
    ADD COLUMN client_event_id       uuid,
    ADD COLUMN device_offset_minutes integer,
    ADD COLUMN received_at           timestamptz;

ALTER TABLE people.attendance_event DROP CONSTRAINT attendance_event_recording_method;
ALTER TABLE people.attendance_event
    ADD CONSTRAINT attendance_event_recording_method
        CHECK (recording_method IN ('SELF', 'SUPERVISOR', 'EMPLOYEE_OFFLINE_SYNC'));
ALTER TABLE people.attendance_event
    ADD CONSTRAINT attendance_event_offline_shape
        CHECK (CASE WHEN recording_method = 'EMPLOYEE_OFFLINE_SYNC'
            THEN client_event_id IS NOT NULL AND device_offset_minutes IS NOT NULL
                AND received_at IS NOT NULL
            ELSE client_event_id IS NULL AND device_offset_minutes IS NULL
                AND received_at IS NULL END);
ALTER TABLE people.attendance_event
    ADD CONSTRAINT attendance_event_offline_offset
        CHECK (device_offset_minutes BETWEEN -840 AND 840);
ALTER TABLE people.attendance_event
    ADD CONSTRAINT attendance_event_offline_receipt
        CHECK (received_at IS NULL OR (recorded_at <= received_at + INTERVAL '5 minutes'
            AND received_at <= recorded_at + INTERVAL '72 hours'));
ALTER TABLE people.attendance_event
    ADD CONSTRAINT attendance_event_client_event_unique
        UNIQUE (tenant_id, client_event_id, employee_id);

-- The receipt is at most 72 hours after the applicable window closed. Runs after
-- attendance_event_guard (trigger names order), which has already proved the slot and record.
CREATE FUNCTION people.attendance_event_offline_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_closes_at timestamptz;
BEGIN
    IF NEW.received_at IS NULL THEN
        RETURN NEW;
    END IF;
    IF NEW.event_type = 'CHECKED_IN' THEN
        SELECT l.end_at INTO v_closes_at FROM people.work_schedule_slot l
            WHERE l.tenant_id = NEW.tenant_id AND l.id = NEW.slot_id
                AND l.schedule_id = NEW.schedule_id;
    ELSE
        SELECT r.check_out_until INTO v_closes_at FROM people.attendance_record r
            WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.record_id;
    END IF;
    IF v_closes_at IS NULL OR NEW.received_at > v_closes_at + INTERVAL '72 hours' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'attendance_event_offline_guard',
            MESSAGE = 'an offline attendance event is received within 72 hours of its window';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER attendance_event_offline_guard
    BEFORE INSERT ON people.attendance_event
    FOR EACH ROW EXECUTE FUNCTION people.attendance_event_offline_guard();
