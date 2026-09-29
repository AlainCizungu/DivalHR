package com.divalhr.core.platform.idempotency;

import java.util.UUID;

/**
 * A committed successful response.
 *
 * @param status HTTP status
 * @param body JSON body
 * @param resourceId created resource
 */
public record StoredResponse(int status, String body, UUID resourceId) {}
