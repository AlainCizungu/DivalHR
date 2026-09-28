package com.divalhr.core.identity.api;

import java.util.List;

/**
 * Minimal, non-personal view of the caller's session. Mirrors {@code CurrentSession} in {@code
 * docs/API-SPEC.yaml}.
 *
 * @param tenantId verified tenant
 * @param roles recognised roles
 */
public record CurrentSession(String tenantId, List<String> roles) {}
