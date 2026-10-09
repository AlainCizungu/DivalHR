/**
 * Leave policy configuration (MVP-040A): the bounded leave package of the people module.
 *
 * <p>It owns {@code people.leave_policy} and {@code people.leave_policy_version} and depends only
 * on the platform and on the people module's {@code BusinessCalendar}; nothing else in the people
 * module reads its tables. Configuration only: requests, balances and approvals come later.
 */
package com.divalhr.core.people.leave;
