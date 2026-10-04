package com.divalhr.core.people.domain.history;

/** How pay is expressed (H13). Never an amount, rate, currency, bonus or allowance. */
public enum CompensationBasis {
  /** Monthly salary. */
  MONTHLY,
  /** Hourly pay. */
  HOURLY,
  /** Daily pay. */
  DAILY,
  /** Piece-rate pay. */
  PIECE_RATE
}
