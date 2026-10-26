/**
 * Operations domain module (MVP-080, ADR 0030): operational tasks. An administrator assigns a
 * bounded task to one active employee, who starts and completes it from self-service; both roles
 * read an immutable history. The module owns the {@code operations} database schema (V41) and does
 * not access other modules' schemas or internal packages: the assignee is checked through the
 * people and identity ports.
 */
package com.divalhr.core.operations;
