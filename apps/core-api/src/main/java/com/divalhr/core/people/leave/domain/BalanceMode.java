package com.divalhr.core.people.leave.domain;

/** Whether a leave policy tracks an annual balance (MVP-040A). */
public enum BalanceMode {
  /** An annual entitlement is configured; balances come in a later story. */
  TRACKED,
  /** No entitlement and no balance. */
  UNTRACKED
}
