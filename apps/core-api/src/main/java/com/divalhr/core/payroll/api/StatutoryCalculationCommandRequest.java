package com.divalhr.core.payroll.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-094B: the statutory calculation to preview. The body is empty: the payroll run comes from
 * the path. Unknown properties are field errors.
 */
@Schema(name = "StatutoryCalculationCommand")
public class StatutoryCalculationCommandRequest extends StrictRequest {}
