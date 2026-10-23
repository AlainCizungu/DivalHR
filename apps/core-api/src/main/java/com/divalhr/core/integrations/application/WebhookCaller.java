package com.divalhr.core.integrations.application;

import com.divalhr.core.platform.tenancy.TenantId;

/**
 * The verified caller of a webhook operation: the tenant from the verified JWT, the subject and the
 * request's correlation ID. Nothing of it comes from a request body.
 *
 * @param tenant verified tenant
 * @param subject verified subject
 * @param correlationId request correlation ID
 */
public record WebhookCaller(TenantId tenant, String subject, String correlationId) {}
