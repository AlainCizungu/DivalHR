package com.divalhr.core.operations.application;

import com.divalhr.core.platform.tenancy.TenantId;

/**
 * The verified caller of an operations command or read: the tenant from the verified JWT, the
 * subject and the request's correlation ID. Nothing of it comes from a request body.
 *
 * @param tenant verified tenant
 * @param subject verified subject
 * @param correlationId request correlation ID
 */
public record OperationsCaller(TenantId tenant, String subject, String correlationId) {}
