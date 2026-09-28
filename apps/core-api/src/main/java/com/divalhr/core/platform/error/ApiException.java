package com.divalhr.core.platform.error;

import java.io.Serial;
import java.util.Map;

/** Base exception that maps directly onto the API error contract. */
public class ApiException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final ErrorCode code;
  private final transient Map<String, Object> params;

  /**
   * Creates an API exception.
   *
   * @param code stable error code
   * @param params safe interpolation parameters; never personal data
   */
  public ApiException(ErrorCode code, Map<String, Object> params) {
    super(code.name());
    this.code = code;
    this.params = Map.copyOf(params);
  }

  /**
   * Returns the stable error code.
   *
   * @return the error code
   */
  public ErrorCode code() {
    return code;
  }

  /**
   * Returns safe interpolation parameters.
   *
   * @return immutable parameters
   */
  public Map<String, Object> params() {
    return params;
  }
}
