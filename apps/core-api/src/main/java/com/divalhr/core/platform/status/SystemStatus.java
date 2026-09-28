package com.divalhr.core.platform.status;

import java.time.Instant;

/**
 * Public, non-sensitive service status. Mirrors {@code SystemStatus} in {@code docs/API-SPEC.yaml}.
 *
 * @param service stable service name
 * @param status {@code UP} or {@code DOWN}
 * @param version build version
 * @param checkedAt time of the check (UTC)
 */
public record SystemStatus(String service, String status, String version, Instant checkedAt) {}
