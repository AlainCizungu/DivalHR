package com.divalhr.core.platform.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a tenant-scoped operation: it must resolve the caller's verified tenant through {@code
 * TenantContextResolver} or {@code TenantAccessGuard} and must never trust a tenant supplied in
 * the request. Every mutating {@code /api/v1} handler carries exactly one of {@link
 * PlatformScoped} or this annotation (enforced by a test).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface TenantScoped {}
