package com.divalhr.core.people.api;

import io.swagger.v3.oas.annotations.media.Schema;

/** Separation command to preview (MVP-022). Mirrors {@code SeparationCommand}. */
@Schema(name = "SeparationCommand")
public class SeparationRequest extends SeparationFields {}
