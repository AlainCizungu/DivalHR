# Third-party brand assets (UI-002)

The public landing page (Issue #63, UI2-2) shows the planned integration ecosystem with the official names and, where an asset is recorded below, the brand's mark. Every mark is a local file in `apps/web/public/brands/`, served from the application's own origin: the page makes no third-party request and uses no icon CDN or remote font.

**Trademarks belong to their respective owners.** The page shows planned compatibility only and states, in English and French, that no partnership or endorsement is implied. Use of each mark remains subject to business and legal review before a production launch (UI2-5).

## Rules

- Marks keep their aspect ratio and their brand colour. They are never recoloured for a theme; they sit on a light tile in both themes and stay smaller than the DivalHR brand.
- The visible name carries the meaning; the image has empty alternative text, so assistive technology announces each brand once.
- When permitted use of a mark cannot be established from an official source, the page shows the official name in a neutral tile instead. No substitute logo is drawn.
- Adding, replacing or removing a mark: update `apps/web/src/features/landing/catalogue.ts` and this file in the same change. A unit test fails if a brand in the catalogue is missing here or if its file is missing.

## Marks in use

Source of the vector paths: the Simple Icons collection, npm package `simple-icons@16.34.0` (tarball SHA-256 `25887ddf96a084a82f9181dc3a7e99750d6b5ac4339fe9321d0e2bf14669dc65`). The collection is released under CC0 and records, for each icon, the official source it was traced from and the brand guidelines where known. The files below contain only that path and the official brand colour from the same record. Retrieved 2026-10-05.

| Displayed name | Owner | File | Brand colour | Official source recorded | Brand guidelines / licence recorded |
| --- | --- | --- | --- | --- | --- |
| Airtel Money | Bharti Airtel / Airtel Africa | `public/brands/airtel.svg` | `#E40000` | https://www.airtel.in/logo-tune | None recorded: confirm before production |
| Orange Money | Orange S.A. | `public/brands/orange.svg` | `#FF7900` | https://brand.orange.com | https://system.design.orange.com/0c1af118d/p/494474-guidelines |
| Sage | The Sage Group plc | `public/brands/sage.svg` | `#00D639` | https://www.sage.com | None recorded: confirm before production |
| QuickBooks | Intuit Inc. | `public/brands/quickbooks.svg` | `#2CA01C` | https://design.intuit.com/quickbooks/brand | https://design.intuit.com/quickbooks/brand |
| Odoo | Odoo S.A. | `public/brands/odoo.svg` | `#714B67` | https://www.odoo.com/page/brand-assets | https://www.odoo.com/page/brand-assets |
| SAP | SAP SE | `public/brands/sap.svg` | `#0FAAFF` | https://www.sap.com | None recorded: confirm before production |
| WhatsApp Business | Meta Platforms, Inc. | `public/brands/whatsapp.svg` | `#25D366` | https://about.meta.com/brand/resources/whatsapp/whatsapp-brand | https://about.meta.com/brand/resources/whatsapp/whatsapp-brand |
| Keycloak | The Linux Foundation (Keycloak project) | `public/brands/keycloak.svg` | `#4D4D4D` | https://github.com/keycloak/keycloak-misc/blob/dee033f2d6d6b5c3a6ce8eb84e285f7e5626dbf6/logo/icon-black.svg | https://www.linuxfoundation.org/legal/trademark-usage |

Notes:

- Airtel Money and Orange Money are shown with the parent brand's mark, which is the mark those services use.
- WhatsApp Business is shown with the WhatsApp mark from Meta's WhatsApp brand resources.

## Exceptions: official name in a neutral tile

These brands are shown by name only, until an official asset with established permitted use is added (UI2-2).

| Displayed name | Owner | Reason |
| --- | --- | --- |
| M-PESA | Safaricom PLC / Vodacom Group | Not in the recorded source collection; no official asset with established permitted use. |
| Oracle | Oracle Corporation | Not in the recorded source collection; no official asset with established permitted use. |
| Microsoft Teams | Microsoft Corporation | Not in the recorded source collection; Microsoft's published trademark guidelines restrict logo use, so permitted use is not established. |
| Slack | Salesforce, Inc. | Not in the recorded source collection; no official asset with established permitted use. |
| Microsoft 365 | Microsoft Corporation | As Microsoft Teams. |
| Google Workspace | Google LLC | Only the general Google mark is available locally; the Google Workspace product mark is not. |
| Microsoft Entra ID | Microsoft Corporation | As Microsoft Teams. |

## Not brands

Bank transfer, ISO 20022 bank files, REST API, webhooks and SSO / SAML are methods or standards. They use DivalHR interface icons and localized labels.
