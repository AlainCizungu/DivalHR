package com.divalhr.core.people.schedule.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Documentation of one proposed slot (the request binds slots raw).
 *
 * @param clientRef the client's correlation reference, unique in the request ({@code
 *     [A-Za-z0-9_-]{1,40}}; never stored)
 * @param start local start {@code YYYY-MM-DDTHH:MM} on a schedule date
 * @param end local end {@code YYYY-MM-DDTHH:MM}, after the start, at most the next date and 24
 *     hours later
 * @param labels English and French labels
 * @param employeeId the assigned employee, or absent/{@code null} for an uncovered slot
 */
@Schema(name = "WorkScheduleSlotInput")
public record WorkScheduleSlotInput(
    String clientRef,
    String start,
    String end,
    WorkScheduleResponses.Labels labels,
    @Schema(nullable = true) UUID employeeId) {}
