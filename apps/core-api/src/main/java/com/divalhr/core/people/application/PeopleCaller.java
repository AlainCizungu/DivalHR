package com.divalhr.core.people.application;

import com.divalhr.core.platform.tenancy.TenantId;

/**
 * The verified caller of an MVP-021 operation: tenant and subject come only from the verified JWT.
 *
 * @param tenant verified tenant
 * @param subject verified subject
 * @param correlationId correlation ID
 */
public record PeopleCaller(TenantId tenant, String subject, String correlationId) {}
