-- MVP-013 (Issue #43, architect decision D1-D11 and A13-1..A13-6): durable, append-only evidence
-- that a verified identity attempted a privileged operation and was denied.
--
-- A dedicated typed table: platform.audit_event keeps its invariants (tenant, resource and state
-- hash are required there), and no free-form metadata exists here. Every column is a server-owned
-- value whose allowed set is enforced below and mirrored by AuthorizationDenial in the application.
--
-- actor_subject is exactly the verified JWT "sub". It has no length limit because token
-- validation imposes none (A13-5); the application already rejects blank subjects before this
-- table is reached, so the database only refuses the empty string.
--
-- tenant_id is the effective tenant (verified token tenant confirmed by an active membership). It
-- is present exactly for tenant-scoped denials that happen after the membership gate passed
-- (rate_limit, method_security) and absent for every platform-scoped row (D8).
--
-- Rows are never updated or deleted (D7, A13-4: no retention schedule is approved yet). Rollback:
-- db/rollback/V12__rollback.sql (manual; never run by Flyway; refuses while rows exist).

CREATE TABLE platform.authorization_denial (
    id             uuid        PRIMARY KEY,
    occurred_at    timestamptz NOT NULL,
    actor_subject  text        NOT NULL,
    action         text        NOT NULL,
    operation      text        NOT NULL,
    scope          text        NOT NULL,
    stage          text        NOT NULL,
    tenant_id      uuid,
    correlation_id text        NOT NULL,
    CONSTRAINT authorization_denial_actor_present CHECK (actor_subject <> ''),
    CONSTRAINT authorization_denial_action_fixed CHECK (action = 'authorization.denied'),
    CONSTRAINT authorization_denial_operation_format
        CHECK (operation ~ '^[a-z]+(-[a-z]+)*(\.[a-z]+(-[a-z]+)*)+$'),
    CONSTRAINT authorization_denial_scope_valid CHECK (scope IN ('platform', 'tenant')),
    CONSTRAINT authorization_denial_stage_valid CHECK (
        stage IN ('role', 'tenant_context', 'mfa', 'membership', 'rate_limit', 'method_security')),
    CONSTRAINT authorization_denial_platform_stages CHECK (
        scope = 'tenant' OR stage IN ('role', 'mfa', 'method_security')),
    CONSTRAINT authorization_denial_effective_tenant_only CHECK (
        (tenant_id IS NOT NULL)
            = (scope = 'tenant' AND stage IN ('rate_limit', 'method_security'))),
    CONSTRAINT authorization_denial_correlation_format
        CHECK (correlation_id ~ '^[A-Za-z0-9._-]{8,64}$')
);

CREATE INDEX authorization_denial_time ON platform.authorization_denial (occurred_at);

CREATE INDEX authorization_denial_actor_time
    ON platform.authorization_denial (actor_subject, occurred_at);

CREATE INDEX authorization_denial_tenant_time
    ON platform.authorization_denial (tenant_id, occurred_at)
    WHERE tenant_id IS NOT NULL;

CREATE FUNCTION platform.authorization_denial_append_only() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'platform.authorization_denial is append-only (% rejected)', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER authorization_denial_no_update_delete
    BEFORE UPDATE OR DELETE ON platform.authorization_denial
    FOR EACH ROW EXECUTE FUNCTION platform.authorization_denial_append_only();

CREATE TRIGGER authorization_denial_no_truncate
    BEFORE TRUNCATE ON platform.authorization_denial
    FOR EACH STATEMENT EXECUTE FUNCTION platform.authorization_denial_append_only();
