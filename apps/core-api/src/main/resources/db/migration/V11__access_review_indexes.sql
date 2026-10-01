-- MVP-012B (Issue #37, architect decision R6): keyset indexes for the read-only access review.
-- Additive: two indexes on identity.tenant_membership, matching the review order (grantedAt then
-- id, both descending), with and without the role filter. No column, row or development data.
-- Address lookups keep using UNIQUE (tenant_id, email_lookup). Rollback:
-- db/rollback/V11__rollback.sql (manual; never run by Flyway).

CREATE INDEX tenant_membership_review_order
    ON identity.tenant_membership (tenant_id, created_at DESC, id DESC);

CREATE INDEX tenant_membership_review_role
    ON identity.tenant_membership (tenant_id, role, created_at DESC, id DESC);
