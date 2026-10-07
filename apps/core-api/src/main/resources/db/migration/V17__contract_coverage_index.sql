-- MVP-031A (Issue #73, amendments A31A-1 and A31A-5): the coverage-head index of the contract
-- expiration queue. Additive: one partial index on documents.contract over the non-void contracts
-- of each employment in start order, covering every contract field the queue and its cursor read
-- (end date, employee and the contract UUID), so the coverage-head walk reads the index only. No
-- column, constraint, row or development data changes. Rollback: db/rollback/V17__rollback.sql
-- (manual; never run by Flyway).

CREATE INDEX contract_coverage_order
    ON documents.contract (tenant_id, employment_id, start_date)
    INCLUDE (end_date, employee_id, id)
    WHERE state <> 'VOID';
