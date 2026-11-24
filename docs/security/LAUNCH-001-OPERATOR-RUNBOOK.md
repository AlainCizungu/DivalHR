# LAUNCH-001 operator runbook: privacy governance

For platform administrators with exact MFA. Everything here happens in Platform → Privacy governance. This is a governance framework, not legal advice or a certification. Do not activate a policy you have not had reviewed.

## 1. Before anything is purged

1. Obtain the retention rule for the country from counsel or the privacy lead, in writing. Use the legal decision worksheet (`LAUNCH-001-LEGAL-DECISION-WORKSHEET.md`).
2. Create a policy version (a draft): country, employee wording version, the reviewed source, the effective date, and one rule for each of the four data classes. Use `HOLD` for any class you are not ready to delete.
3. Complete the governance checklist and enter the review reference. A draft cannot be approved without it.
4. Approve, then activate (or schedule a future-dated version). Only one active version can cover a country and a period.
5. Apply the active policy: enter the country, read the preview, confirm. Referrals that already carry a policy are never changed. Until you do this, existing referrals stay `POLICY_PENDING` and cannot be purged.

## 2. Employee requests

1. Open the request queue. The contact is masked and the employee's choices are not shown.
2. Move the request to `UNDER_REVIEW`. A refusal or partial approval needs a reason; the message to the employee is plain text and is shown to them. The internal note is sealed and never shown.
3. For `ACCESS`, approving makes the package available to the employee for seven days (three generations per day at most). Nothing is emailed.
4. For `ERASURE`, `RESTRICTION` and `OBJECTION`, approving records the decision; the data is changed only by a purge (section 4).
5. Move to `COMPLETED` when the work is done.

## 3. Legal hold

Place a hold with a reason, a review date and an optional external reference. A hold blocks every purge of that referral until you release it. Review holds by their review date.

## 4. Purge (minimize or erase)

1. Enter the referral reference, choose `MINIMIZE` (keeps a value-free trace) or `ERASE` (no trace), and preview. The preview shows, for each data class, whether it is eligible or why it is not (no reviewed policy, hold, still open, retention period not elapsed, action not permitted, already purged). It shows no personal value.
2. If nothing is eligible, stop. Do not work around a refusal.
3. Tick the confirmation and confirm. The server revalidates under locks; a stale or reused preview is refused and you must preview again.
4. The result lists the classes and row counts only. Keep the purge reference with the request record.

## 5. Limits and failures

Platform mutations 20 per administrator per hour, purge previews 10 per hour, purge confirmations 5 per hour. A `429` states how long to wait. Counters reset when Core restarts. A `409` stale error means something changed: reload and repeat.

## 6. Rollback

`db/rollback/V58__rollback.sql` refuses to run while any policy, hold, request, export or purge evidence exists, so governance evidence cannot be lost by a rollback. Roll back only on an environment with no governance data.

## 7. What this does not do

No automatic deletion, no notification, no country duration shipped with the product, no legal opinion. Lifecycle evidence is kept (only a residue count is recorded) in this increment.
