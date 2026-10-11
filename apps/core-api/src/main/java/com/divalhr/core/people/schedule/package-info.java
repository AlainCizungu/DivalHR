/**
 * Site work schedules (MVP-050A, Issue #99; ADR 0014): the bounded scheduling package of the people
 * module.
 *
 * <p>It owns {@code people.work_schedule} and {@code people.work_schedule_slot} and depends only on
 * the platform and on the people module's business calendar, verified caller and strict-request
 * base; nothing else in the application depends on it. It reads the people module's own employee,
 * employment, placement and separation rows for eligibility, and sites only through the platform
 * {@code OrganizationPlacementDirectory} port.
 */
package com.divalhr.core.people.schedule;
