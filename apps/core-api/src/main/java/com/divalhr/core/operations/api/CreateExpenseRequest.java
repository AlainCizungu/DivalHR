package com.divalhr.core.operations.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The employee's new expense draft (MVP-081, D81-3).
 */
@Schema(name = "CreateExpenseRequest")
public class CreateExpenseRequest extends ExpenseDraftRequest {
}
