package com.divalhr.core.people.api;

import io.swagger.v3.oas.annotations.media.Schema;

/** Change command to preview (MVP-021). Mirrors {@code EmploymentChangeCommand}. */
@Schema(name = "EmploymentChangeCommand")
public class EmploymentChangeRequest extends EmploymentChangeFields {}
