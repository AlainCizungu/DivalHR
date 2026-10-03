import { expect, test } from '@playwright/test';
import { CORE_API, TENANT_A, USERS, signIn, sql } from './support';

// MVP-013 (Issue #43): with the real identity provider, privileged denials of verified identities
// leave one append-only row each, with the effective-tenant rule, while the public responses stay
// unchanged. Rows are found by the correlation ID each request sends (a join key only).

function correlation(label: string): string {
  return `e2e-denial-${label}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}

/** The verified `sub` of the bearer the app sent (read in memory, never logged). */
function subjectOf(bearer: string): string {
  const payload = bearer.replace(/^Bearer /, '').split('.')[1] ?? '';
  return (JSON.parse(Buffer.from(payload, 'base64url').toString('utf8')) as { sub: string }).sub;
}

function rowFor(correlationId: string): string {
  return sql(
    `SELECT actor_subject || '|' || operation || '|' || scope || '|' || stage || '|' ||
            coalesce(tenant_id::text, 'none')
       FROM platform.authorization_denial WHERE correlation_id = '${correlationId}'`,
  );
}

test.describe.serial('MVP-013: privileged denial evidence', () => {
  test('an employee probing administrator operations leaves attributed evidence', async ({
    page,
  }) => {
    const bearer = await signIn(page, USERS.employeeA, 'en', undefined);
    const subject = subjectOf(bearer());

    // Tenant operation: role denial, unchanged body, no tenant recorded before the membership gate.
    const tenantCorrelation = correlation('tenant');
    const tenantDenied = await page.request.get(`${CORE_API}/legal-entities`, {
      headers: { Authorization: bearer(), 'X-Correlation-Id': tenantCorrelation },
    });
    expect(tenantDenied.status()).toBe(403);
    expect(((await tenantDenied.json()) as { code: string }).code).toBe('ACCESS_DENIED');
    expect(rowFor(tenantCorrelation)).toBe(`${subject}|legal-entity.list|tenant|role|none`);

    // Platform operation: the token's tenant is never used as the audit tenant.
    const platformCorrelation = correlation('platform');
    const platformDenied = await page.request.post(`${CORE_API}/organizations`, {
      headers: {
        Authorization: bearer(),
        'Idempotency-Key': `e2e-denial-${Date.now().toString(36)}-0001`,
        'X-Correlation-Id': platformCorrelation,
      },
      data: { not: 'read' },
    });
    expect(platformDenied.status()).toBe(403);
    expect(rowFor(platformCorrelation)).toBe(`${subject}|organization.create|platform|role|none`);

    // An invalid token is never attributed.
    const anonymousCorrelation = correlation('anonymous');
    const anonymous = await page.request.get(`${CORE_API}/legal-entities`, {
      headers: { Authorization: 'Bearer invalid', 'X-Correlation-Id': anonymousCorrelation },
    });
    expect(anonymous.status()).toBe(401);
    expect(rowFor(anonymousCorrelation)).toBe('');
  });

  test('a rate-limited administrator leaves one row per window with the effective tenant', async ({
    page,
  }) => {
    const bearer = await signIn(page, USERS.adminA, 'en');
    const subject = subjectOf(bearer());
    const sent: string[] = [];
    let limited = 0;
    for (let i = 0; i < 40 && limited < 4; i++) {
      const id = correlation(`limit-${i}`);
      sent.push(id);
      const response = await page.request.get(`${CORE_API}/access-review/summary`, {
        headers: { Authorization: bearer(), 'X-Correlation-Id': id },
      });
      if (response.status() === 429) {
        limited++;
        expect(response.headers()['retry-after']).toMatch(/^[0-9]+$/);
      } else {
        expect(response.status()).toBe(200);
      }
    }
    expect(limited).toBe(4);
    const rows = sql(
      `SELECT count(*) || '|' || string_agg(DISTINCT coalesce(tenant_id::text, 'none'), ',')
         FROM platform.authorization_denial
        WHERE stage = 'rate_limit' AND actor_subject = '${subject}'
          AND correlation_id IN (${sent.map((id) => `'${id}'`).join(',')})`,
    );
    const [count, tenants] = rows.split('|');
    // One first refusal per window: two only if the burst crossed a minute boundary.
    expect(Number(count)).toBeGreaterThanOrEqual(1);
    expect(Number(count)).toBeLessThanOrEqual(2);
    expect(tenants).toBe(TENANT_A);
  });
});
