package com.divalhr.core.people.leave.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Documentation of the {@code names} input (MVP-040A): both names are required. The request keeps
 * the raw value; this type only describes it.
 *
 * @param en English name, 2 to 100 characters after trimming
 * @param fr French name, 2 to 100 characters after trimming
 */
@Schema(name = "LeavePolicyNamesInput")
public record LeavePolicyNamesInput(String en, String fr) {}
