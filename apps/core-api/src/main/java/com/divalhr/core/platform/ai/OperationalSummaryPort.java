package com.divalhr.core.platform.ai;

import com.divalhr.core.platform.ai.OperationalSummaryContract.Request;
import com.divalhr.core.platform.ai.OperationalSummaryContract.Response;

/**
 * Core's only door to the AI service for the operational summary (MVP-101, D101-4). The adapter
 * authenticates as the dedicated Core AI client; the AI service has no database access, calls no
 * Core tool and cannot widen the scope it is given.
 */
public interface OperationalSummaryPort {

  /**
   * Generates one briefing from a governed snapshot.
   *
   * @param request the closed snapshot
   * @return the AI service's shape-checked answer
   * @throws AiServiceUnavailableException for any failure, with a bounded reason only
   */
  Response generate(Request request);
}
