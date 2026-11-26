# Commercial screenshot manifest (LAUNCH-004, Issue #228)

Every screenshot on the commercial page is a real capture of the deployed DivalHR application. Nothing is a mock-up. This file records how each one was made, so a reviewer can reproduce or challenge it. The same facts are in `apps/web/src/features/commercial/media.json`, which the page reads.

## Capture record

| Item | Value |
| --- | --- |
| Release captured | `225da6cb13c5cc6e1b12c11ff4b5ce03134aa2f7` (the release deployed on hr-dev when LAUNCH-001 closed) |
| Environment | `https://hr-dev.dival.ai`, a test environment. The "Test environment" badge stays visible in every capture. |
| Tool | Playwright (Chromium) run on the hr-dev instance, read-only: it signs in, opens pages and takes viewport screenshots. It creates, changes and submits nothing. |
| Viewports | Desktop 1440 × 900 and mobile 390 × 844, both at device scale factor 2. Light theme. |
| Language | Switched with the product's own language control after sign-in (`<html lang>` is asserted for each capture). |
| Accounts (fixture) | The two existing synthetic hr-dev acceptance accounts: a tenant administrator (with MFA) and an employee. Credentials stay in root-only files on the instance and are never printed or stored in the repository. |
| Capture script | `commercial-capture.spec.ts`, `capture-runner.sh`, `playwright.hr-dev-commercial.config.ts` and `capture-228.sh`, kept with the other operations evidence in the maintainer's working folder (not in the application and not run by CI) and attached to the pull request, with their checksums in `commercial-evidence/capture-helper-checksums.txt` |
| Originals | Kept outside the production bundle and outside version control, in `commercial-evidence/raw/` (not under `apps/web/public`), with `commercial-evidence/raw-SHA256SUMS`. The saved page-text files there contain the unmasked fixture names and are never committed. The first 12 hex characters of each original's SHA-256 are in the table. |

## Shots

| Shot | Locale | Viewport | Route | Role | Identifiers masked | Widths (px) | Size in KB at each width: AVIF · WebP · JPEG | Original SHA-256 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `admin-home` | EN | desktop (1440×900) | `/` | admin | no | 640, 960, 1440 | 14 / 25 / 43 · 19 / 35 / 60 · 25 / 45 / 82 | `7271bb51f37d` |
| `directory` | EN | desktop (1440×900) | `/admin/people` | admin | yes | 640, 960, 1440 | 14 / 25 / 41 · 22 / 39 / 66 · 29 / 54 / 94 | `c1a5c0b72cf2` |
| `payroll` | EN | desktop (1440×900) | `/admin/payroll-preparation` | admin | no | 640, 960, 1440 | 12 / 22 / 38 · 17 / 31 / 54 · 23 / 44 / 77 | `7a2333ff732b` |
| `employee-mobile` | EN | mobile (390×844) | `/` | employee | no | 390, 780 | 14 / 32 · 20 / 48 · 27 / 68 | `13d9092a7ab2` |
| `admin-home` | FR | desktop (1440×900) | `/` | admin | no | 640, 960, 1440 | 15 / 28 / 46 · 21 / 39 / 67 · 27 / 49 / 90 | `74c36a6fb94e` |
| `directory` | FR | desktop (1440×900) | `/admin/people` | admin | yes | 640, 960, 1440 | 15 / 26 / 41 · 21 / 39 / 66 · 28 / 55 / 95 | `db8adea45b3b` |
| `payroll` | FR | desktop (1440×900) | `/admin/payroll-preparation` | admin | no | 640, 960, 1440 | 13 / 25 / 42 · 18 / 34 / 61 · 24 / 47 / 84 | `5f321acd233b` |
| `employee-mobile` | FR | mobile (390×844) | `/` | employee | no | 390, 780 | 17 / 38 · 25 / 57 · 32 / 80 | `edfe3c1de876` |

Responsive delivery uses `<picture>` with AVIF and WebP sources and a JPEG fallback. The hero image is the English or French administrator home; every other image loads lazily. 66 files, 2.6 MB in total, the largest 95 KB (budget: 300 KB each). `scripts/ops/commercial-truth.test.mjs` fails if any listed file is missing or over budget.

## Personal-data review

1. No real person, e-mail address, telephone number or account number appears. For every capture the script saved the page text and counted e-mail-like, phone-like and long-number strings. E-mail-like: 0 and long numbers: 0 in all eight. The one phone-like match per payroll capture is a timestamp (`2026112630`), not a telephone number.
2. **The employee directory is the one capture whose page content was altered before the screenshot.** On hr-dev the directory lists acceptance-test fixtures (generated employee numbers such as `ACC-…` and generated surnames). Before the screenshot the script replaces employee numbers with `EMP-1001…` and names with neutral placeholder names, then fails the run if any original identifier is still visible in the page text. The layout, styles, columns and statuses are the real application. The gallery text says so in both languages, and the manifest marks these shots as masked.
3. Admin home, payroll preparation and the phone home show no personal data and are unaltered.

## Views captured and left out

Leave approvals, attendance and timesheets, partner catalogue and privacy requests were captured during the first pass and are **not used**. On hr-dev their pages show acceptance-test records with generated identifiers (request references, entity and policy codes) that would read as clutter or as real data. They were excluded rather than masked. If a cleaner demonstration tenant is created later, they can be added through the same script and manifest.

## Limits stated plainly

1. These are above-the-fold captures of a test environment with sparse synthetic data, so lists are short and some forms are empty. They show the product's real interface, not a customer's operations.
2. The capture does not cover statutory payroll, payslips or payments, which the page presents as available after configuration or dependent on approved partners.
3. Alternative text describes only what is visible in each image, in English and French, and is tested for presence and length.

## Reproduce

```bash
bash capture-228.sh        # on the maintainer's Mac: checks the deployed release, runs the capture, copies the originals back
```

The optimization step (AVIF, WebP and JPEG at the widths above) used sharp: AVIF quality 52, WebP quality 80, JPEG (mozjpeg) quality 78, each lowered only if a file exceeded 300 KB (none did).
