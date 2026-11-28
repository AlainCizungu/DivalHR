# LAUNCH-002 synthetic acceptance tenant: bounded setup and cleanup

Scope: the second production browser journey (`apps/web/e2e/production/tenant.spec.ts`). It runs only after the first
production deployment, with the **launch lock CLOSED**, and uses operator-controlled synthetic data only. It never lifts
the lock, activates no statutory or retention pack, contacts no partner and deletes nothing by editing the database.

## Operator prerequisites (outside Git, never committed)

1. A platform administrator account in the production realm, provisioned by the operator, with its authenticator.
   Environment: `PROD_PLATFORM_ADMIN_USER`, `PROD_PLATFORM_ADMIN_PASSWORD`, `PROD_PLATFORM_ADMIN_TOTP_SECRET`.
2. A mailbox the operator owns that accepts plus-addresses, and a command that reads it:
   `PROD_ACCEPTANCE_MAILBOX` (address) and `PROD_ACCEPTANCE_MAIL_CMD` (an executable taking `<recipient>
   <subject-pattern>`, waiting for delivery and printing the newest matching message text). The journey holds no mailbox
   credential. If the e-mail path (SES identity, DNS, production access) is not ready the journey fails; it never skips.
3. `accept.sh` stops before Playwright when any value is missing.

## What the journey does (one test, bilingual)

1. The platform administrator (English) creates ONE organization named `ACCEPTANCE-SYNTHETIC <stamp>` and invites its first
   administrator (French invitation) through the production e-mail path to a plus-address of the operator's mailbox.
2. The first administrator accepts, sets a password and an authenticator, and signs in (French) on the new organization.
3. Employee import (French), leave (French request, English review, manager decision), a published shift and an
   administrator-created attendance record, timesheet (submit, approve, lock) and payroll preparation (English).
4. Cleanup with product paths only: both employees are separated with immediate access removal and the test waits until the
   product reports the access `COMPLETED` (sign-in removed in the identity provider).
5. A redacted evidence file (steps and statuses, no addresses or credentials) is written to the acceptance evidence directory.

## Cleanup the operator completes (recorded in the evidence directory)

The first administrator cannot be separated through the product by design, and the product has no organization deletion.
After the run:

1. In the production realm console, as the named realm administrator, **disable** the synthetic first-administrator user
   (the plus-address `acc-admin-<stamp>`). Record the date and the operator in the acceptance record.
2. The organization remains, with no active access, identified by its `ACCEPTANCE-SYNTHETIC` name. Removing it follows the
   approved retention and erasure path under a separate approval; it is never done by direct database editing.
3. Run `bash ops/production/accept.sh --evidence-dir <same dir> --finalize`. It refuses unless the run ended
   `PENDING_CLEANUP` with unchanged checksums, then verifies read-only in the production realm that exactly one user holds the
   synthetic administrator's address and is **disabled** (step 09), re-checks that the launch lock is `CLOSED` (step 10) and
   only then writes `result=PASS`. Until then the acceptance is not reported as passed, and the first administrator staying
   enabled keeps it failing.
