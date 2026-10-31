package com.divalhr.core.payroll.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-094C: the approval to preview. The body is empty: the legal entity, the payroll run and the
 * statutory calculation come from the path. Unknown properties are field errors.
 */
@Schema(name = "PayrollApprovalPreviewCommand")
public class PayrollApprovalPreviewRequest extends StrictRequest {}
