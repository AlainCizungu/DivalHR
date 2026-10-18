package com.divalhr.core.payroll.application;

import com.divalhr.core.platform.tenancy.TenantId;

/**
 * The verified caller of a payroll operation: the tenant from the verified JWT, the subject and the
 * request's correlation ID. Nothing of it comes from a request body.
 *
 * @param tenant verified tenant
 * @param subject verified subject
 * @param correlationId request correlation ID
 */
public record PayrollCaller(TenantId tenant, String subject, String correlationId) {}
