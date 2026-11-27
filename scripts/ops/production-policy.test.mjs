// LAUNCH-002 (Issue #230, ADR 0048): static policy tests of the production controlled-pilot
// environment. They read the Terraform, the Compose project, the Caddyfile, the secret manifest and the
// production scripts as TEXT and fail closed on any weakening. They never call AWS, never need
// Terraform, Docker or a secret, and run in `pnpm ops:test`.
//   node --test scripts/ops/production-policy.test.mjs
import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const tfDir = join(root, 'infrastructure/production/terraform');
const prodOps = join(root, 'ops/production');
const read = (path) => readFileSync(join(root, path), 'utf8');
const tfFiles = readdirSync(tfDir).filter((name) => name.endsWith('.tf'));
const tf = Object.fromEntries(
  tfFiles.map((name) => [name, readFileSync(join(tfDir, name), 'utf8')]),
);
const allTf = Object.values(tf).join('\n');
const compose = read('infrastructure/production/compose.yaml');
const caddyfile = read('infrastructure/production/caddy/Caddyfile');
const manifest = read('ops/production/secrets.manifest');
const scriptsText = readdirSync(prodOps)
  .filter((name) => /\.(sh|py)$/.test(name))
  .map((name) => readFileSync(join(prodOps, name), 'utf8'))
  .join('\n');

/** The text of every top-level block `kind "type" "name" { ... }` by brace matching. */
export function blocks(text, kind, type) {
  const out = [];
  const pattern = new RegExp(`^${kind} "${type}" "([^"]+)" \\{`, 'gm');
  for (let match = pattern.exec(text); match; match = pattern.exec(text)) {
    let depth = 0;
    let end = match.index + match[0].length - 1;
    for (; end < text.length; end += 1) {
      if (text[end] === '{') depth += 1;
      if (text[end] === '}') {
        depth -= 1;
        if (depth === 0) break;
      }
    }
    out.push({ name: match[1], body: text.slice(match.index, end + 1) });
  }
  return out;
}
const variable = (name) => {
  const found = new RegExp(`^variable "${name}" \\{[^]*?^\\}`, 'm').exec(tf['variables.tf']);
  assert.ok(found, `variable ${name}`);
  return found[0];
};
const resources = (type) => blocks(allTf, 'resource', type);
const one = (type, name) => {
  const found = resources(type).find((block) => block.name === name);
  assert.ok(found, `${type}.${name} is missing`);
  return found.body;
};
const doc = (name) => {
  const found = blocks(tf['iam.tf'], 'data', 'aws_iam_policy_document').find(
    (b) => b.name === name,
  );
  assert.ok(found, `policy document ${name}`);
  return found.body;
};

describe('network exposure (L2-3)', () => {
  it('only the load balancer is public: 443 and the 80 redirect', () => {
    const rules = resources('aws_vpc_security_group_ingress_rule');
    const internet = rules.filter((rule) => /cidr_ipv4\s*=\s*"0\.0\.0\.0\/0"/.test(rule.body));
    assert.deepEqual(internet.map((rule) => rule.name).sort(), ['alb_http_redirect', 'alb_https']);
    for (const rule of internet) assert.match(rule.body, /from_port\s*=\s*(80|443)\b/);
    assert.equal(resources('aws_security_group_rule').length, 0, 'legacy rules are not used');
    assert.doesNotMatch(allTf, /::\/0/, 'no IPv6 world rule');
  });

  it('the host accepts exactly the listener port from the load balancer and nothing else', () => {
    const hostRules = resources('aws_vpc_security_group_ingress_rule').filter((rule) =>
      /^\s*security_group_id\s*=\s*aws_security_group\.host\.id/m.test(rule.body),
    );
    assert.equal(hostRules.length, 1);
    assert.match(hostRules[0].body, /from_port\s*=\s*8443/);
    assert.match(hostRules[0].body, /to_port\s*=\s*8443/);
    assert.match(
      hostRules[0].body,
      /referenced_security_group_id\s*=\s*aws_security_group\.alb\.id/,
    );
    for (const port of [22, 25, 3389, 5432, 8080, 8081, 8090, 18180]) {
      assert.doesNotMatch(
        allTf,
        new RegExp(`(from_port|to_port)\\s*=\\s*${port}\\b`),
        `port ${port}`,
      );
    }
  });

  it('the host has no public address, no key pair, IMDSv2 only and no internet route', () => {
    const host = one('aws_instance', 'host');
    assert.match(host, /associate_public_ip_address\s*=\s*false/);
    assert.doesNotMatch(host, /key_name/);
    assert.match(host, /http_tokens\s*=\s*"required"/);
    assert.equal(resources('aws_nat_gateway').length, 0, 'no NAT gateway: deny-by-default egress');
    assert.doesNotMatch(allTf, /map_public_ip_on_launch\s*=\s*true/);
    for (const route of resources('aws_route')) {
      assert.doesNotMatch(
        route.body,
        /0\.0\.0\.0\/0/,
        `route ${route.name} must not be a default route`,
      );
    }
  });

  it('egress of the host is endpoints, S3 and the approved e-mail submission port only', () => {
    const egress = resources('aws_vpc_security_group_egress_rule').filter((rule) =>
      /^\s*security_group_id\s*=\s*aws_security_group\.host\.id/m.test(rule.body),
    );
    assert.deepEqual(egress.map((rule) => rule.name).sort(), [
      'host_to_email_provider',
      'host_to_endpoints',
      'host_to_s3',
    ]);
    for (const rule of egress) assert.doesNotMatch(rule.body, /0\.0\.0\.0\/0/);
    assert.match(
      one('aws_vpc_security_group_egress_rule', 'host_to_email_provider'),
      /for_each\s*=\s*toset\(var\.email_provider_cidrs\)/,
    );
  });

  it('the e-mail provider egress is empty until the operator names the provider', () => {
    assert.match(variable('email_provider_cidrs'), /default\s*=\s*\[\]/);
  });
});

describe('encryption and storage (L2-4, L2-9)', () => {
  it('every volume is encrypted with the environment key', () => {
    assert.match(one('aws_instance', 'host'), /encrypted\s*=\s*true/);
    assert.match(one('aws_ebs_volume', 'data'), /encrypted\s*=\s*true/);
    assert.match(one('aws_ebs_volume', 'data'), /kms_key_id\s*=\s*aws_kms_key\.main\.arn/);
    assert.match(one('aws_kms_key', 'main'), /enable_key_rotation\s*=\s*true/);
  });

  for (const bucket of ['backups', 'evidence']) {
    it(`bucket ${bucket}: public access blocked, versioned, KMS-encrypted, TLS-only`, () => {
      const block = one('aws_s3_bucket_public_access_block', bucket);
      for (const key of [
        'block_public_acls',
        'block_public_policy',
        'ignore_public_acls',
        'restrict_public_buckets',
      ]) {
        assert.match(block, new RegExp(`${key}\\s*=\\s*true`));
      }
      assert.match(one('aws_s3_bucket_versioning', bucket), /status\s*=\s*"Enabled"/);
      assert.match(
        one('aws_s3_bucket_server_side_encryption_configuration', bucket),
        /sse_algorithm\s*=\s*"aws:kms"/,
      );
      const policyDoc = blocks(allTf, 'data', 'aws_iam_policy_document').find(
        (b) => b.name === `${bucket}_bucket`,
      );
      assert.ok(policyDoc, `${bucket} bucket policy document`);
      assert.match(policyDoc.body, /aws:SecureTransport/);
      assert.match(policyDoc.body, /effect\s*=\s*"Deny"/);
    });
  }

  it('the evidence bucket is write-once (Object Lock); backups keep overwritten versions', () => {
    assert.match(one('aws_s3_bucket', 'evidence'), /object_lock_enabled\s*=\s*true/);
    assert.match(allTf, /noncurrent_version_expiration/);
    assert.ok(resources('aws_s3_bucket_object_lock_configuration').length >= 1);
  });

  it('EBS snapshots run nightly under the lifecycle manager', () => {
    assert.ok(resources('aws_dlm_lifecycle_policy').length >= 1);
    assert.match(allTf, /interval\s*=\s*24/);
  });

  it('container registries are immutable, scanned and KMS-encrypted', () => {
    for (const repo of ['service', 'mirror']) {
      const body = one('aws_ecr_repository', repo);
      assert.match(body, /image_tag_mutability\s*=\s*"IMMUTABLE"/);
      assert.match(body, /scan_on_push\s*=\s*true/);
      assert.match(body, /encryption_type\s*=\s*"KMS"/);
    }
  });
});

describe('least-privilege IAM (L2-5)', () => {
  it('no policy allows every action, and the host cannot manage IAM, change the lock or delete backups', () => {
    assert.doesNotMatch(tf['iam.tf'], /actions\s*=\s*\[\s*"\*"/);
    assert.doesNotMatch(tf['iam.tf'], /"iam:/);
    const host = doc('host');
    assert.doesNotMatch(host, /ssm:PutParameter/);
    assert.doesNotMatch(host, /s3:DeleteObject/);
    assert.doesNotMatch(host, /secretsmanager:(Put|Create|Delete|Update)/);
    assert.match(host, /secretsmanager:GetSecretValue/);
    assert.match(host, /local\.secret_prefix_arn/);
    assert.match(tf['iam.tf'], /secret:divalhr\/production\/\*"/);
    assert.match(host, /cloudwatch:namespace/);
  });

  it('the operator can read the lock and create secrets but never read a secret or change the lock', () => {
    const operator = doc('operator');
    assert.doesNotMatch(operator, /ssm:PutParameter/);
    assert.doesNotMatch(operator, /secretsmanager:GetSecretValue/);
    assert.match(operator, /ssm:SendCommand/);
  });

  it('the launch-lock-lift policy exists, changes only the lock parameter and is attached to nobody', () => {
    const lift = doc('launch_lock_lift');
    assert.match(lift, /ssm:PutParameter/);
    assert.match(lift, /local\.lock_param_arn/);
    assert.equal((lift.match(/actions\s*=/g) ?? []).length, 1);
    assert.doesNotMatch(
      allTf,
      /aws_iam_(user|role|group)_policy_attachment|aws_iam_policy_attachment/,
    );
    assert.doesNotMatch(allTf, /policy_arn\s*=\s*aws_iam_policy\.launch_lock_lift/);
  });

  it('the S3 prefixes the tooling uses are exactly the ones the policies allow', () => {
    assert.match(read('ops/production/backup.sh'), /prefix="backups\/\$REASON\//);
    assert.match(doc('host'), /backups\/\*/);
    for (const prefix of ['releases', 'verification', 'evidence']) {
      assert.match(read('ops/production/deploy.sh'), new RegExp(`\\$S3/${prefix}/`));
      assert.match(doc('operator'), new RegExp(`/${prefix}/\\*`));
    }
    assert.match(doc('host'), /releases\/\*/);
    assert.match(doc('host'), /verification\/\*/);
  });
});

describe('web application firewall (L2-4)', () => {
  const waf = tf['waf.tf'];

  it('is attached to the load balancer with the managed baseline, body limit and rate rules', () => {
    assert.equal(resources('aws_wafv2_web_acl_association').length, 1);
    assert.match(waf, /AWSManagedRulesCommonRuleSet/);
    assert.match(waf, /AWSManagedRulesKnownBadInputsRuleSet/);
    assert.match(waf, /size_constraint_statement/);
    assert.match(waf, /var\.max_body_bytes/);
    assert.match(waf, /\/api\/v1\/public\//);
    assert.match(waf, /var\.public_rate_limit/);
    assert.match(waf, /var\.abuse_rate_limit/);
    assert.ok((waf.match(/rate_based_statement/g) ?? []).length >= 2);
  });

  it('the emergency allow-list is empty by default', () => {
    assert.match(variable('emergency_allow_cidrs'), /default\s*=\s*\[\]/);
    assert.match(waf, /emergency_allow_cidrs/);
  });

  it('redacts the query string and credential headers in its logs (WAF logs hold no request body)', () => {
    const configs = resources('aws_wafv2_web_acl_logging_configuration');
    assert.equal(configs.length, 1);
    assert.match(configs[0].body, /query_string\s*\{\s*\}/);
    for (const header of ['authorization', 'cookie', 'set-cookie'])
      assert.match(configs[0].body, new RegExp(`name\\s*=\\s*"${header}"`));
  });
});

describe('no development material in production (L2-2, L2-5)', () => {
  const code = (text) =>
    text
      .split('\n')
      .filter((line) => !/^\s*(#|\/\/)/.test(line))
      .join('\n');

  it('configuration names no test host, test realm, synthetic provider, receiver or mail catcher', () => {
    const forbidden = [
      /mailpit/i,
      /hr-dev\.dival\.ai/,
      /divalhr-test/,
      /meta-provider/,
      /payment-provider/,
      /partner-receiver/,
      /webhook-receiver/,
      /dev-only/i,
    ];
    for (const [name, text] of Object.entries({ terraform: allTf, compose, caddyfile, manifest })) {
      for (const pattern of forbidden)
        assert.doesNotMatch(code(text), pattern, `${name} names ${pattern}`);
    }
  });

  it('the scripts name the test environment only to reuse its code by path, never its hosts', () => {
    for (const pattern of [
      /mailpit/i,
      /hr-dev\.dival\.ai/,
      /meta-provider/,
      /payment-provider/,
      /dev-only|changeme/i,
    ]) {
      const hits = code(scriptsText)
        .split('\n')
        .filter(
          (line) => pattern.test(line) && !/grep|case |for svc in|^\s*r"|\*dival\.ai\*/.test(line),
        );
      assert.deepEqual(hits, [], `scripts name ${pattern}`);
    }
  });

  it('only the alarm address is a dival.ai name, and the resource prefix cannot be a test name', () => {
    const matches = allTf.match(/[A-Za-z0-9.@-]*dival\.ai/g) ?? [];
    assert.deepEqual([...new Set(matches)], ['contact@dival.ai']);
    assert.match(variable('name_prefix'), /test\|dev/);
  });

  it('Compose runs the production environment from the private registry with no build and no test service', () => {
    assert.match(compose, /DIVALHR_ENVIRONMENT:\s*production/);
    assert.doesNotMatch(compose, /^\s*build:/m);
    for (const image of compose.match(/^\s*image:\s*(.+)$/gm)) {
      assert.match(image, /\$\{PROD_REGISTRY:\?\}\/divalhr-prod\//);
    }
    const services = [...compose.matchAll(/^ {2}([a-z-]+):\s*$/gm)].map((m) => m[1]);
    for (const name of ['caddy', 'postgres', 'keycloak', 'core-api', 'ai-service', 'web'])
      assert.ok(services.includes(name), name);
    for (const name of services)
      assert.ok(
        !/mail|provider|receiver|catcher|mock/.test(name.replace('mail-out', '')),
        `${name} looks like a test service`,
      );
  });
});

describe('Compose boundaries (L2-3, L2-5)', () => {
  it('publishes only the load-balancer listener and the loopback operator port, from Caddy alone', () => {
    const published = [...compose.matchAll(/^\s+- '([^']*:[0-9]+)'$/gm)]
      .map((m) => m[1])
      .filter((v) => /:(8443|18180)$/.test(v) && !v.startsWith('/'));
    assert.deepEqual(
      published.sort(),
      ['${PROD_HTTPS_PUBLISH:-8443}:8443', '127.0.0.1:${PROD_ADMIN_PORT:-18180}:18180'].sort(),
    );
    assert.equal((compose.match(/^\s+ports:\s*$/gm) ?? []).length, 1, 'only Caddy publishes ports');
  });

  it('no host network, no privileged container and no Docker socket', () => {
    assert.doesNotMatch(compose, /network_mode:\s*host/);
    assert.doesNotMatch(compose, /privileged:\s*true/);
    assert.doesNotMatch(compose, /docker\.sock/);
  });

  it('only Core and Keycloak join the outbound mail network', () => {
    const section = (name) => {
      const start = compose.search(new RegExp(`^ {2}${name}:\\s*$`, 'm'));
      const rest = compose.slice(start + 1);
      const next = rest.search(/^ {2}[a-z-]+:\s*$/m);
      return next < 0 ? rest : rest.slice(0, next);
    };
    const members = ['caddy', 'postgres', 'keycloak', 'core-api', 'ai-service', 'web'].filter(
      (name) => /\bmail-out\b/.test(section(name)),
    );
    assert.deepEqual(members.sort(), ['core-api', 'keycloak']);
  });

  it('no secret value appears in the environment: secrets are read-only files', () => {
    const sensitive = /(PASSWORD|SECRET|TOKEN|PRIVATE|SIGNING_KEY|ENCRYPTION_KEY|LOOKUP_KEY)/;
    for (const line of compose.split('\n').filter((l) => /^\s{6}[A-Z][A-Z0-9_]+:/.test(l))) {
      const [key, ...rest] = line.trim().split(':');
      const value = rest.join(':').trim();
      if (!sensitive.test(key)) continue;
      assert.ok(
        /_FILE$/.test(key) || /^['"]?\/run\/secrets\//.test(value) || /URL|ID$/.test(key),
        `${key} must not carry a secret value`,
      );
    }
    assert.match(compose, /\/run\/secrets\//);
    assert.match(compose, /secrets\/core:\/run\/secrets\/core:ro/);
    assert.match(compose, /secrets\/postgres:\/run\/secrets\/postgres:ro/);
  });
});

describe('edge: Caddy (L2-3)', () => {
  it('trusts X-Forwarded-For only from the VPC range and serves the load balancer without SNI', () => {
    assert.match(caddyfile, /trusted_proxies static \{\$PROD_VPC_CIDR\}/);
    assert.match(caddyfile, /client_ip_headers X-Forwarded-For/);
    assert.match(caddyfile, /default_sni \{\$PROD_APP_HOST\}/);
    assert.match(caddyfile, /auto_https disable_redirects/);
  });

  it('never exposes the Keycloak administration, the master realm, health, metrics or the Core actuator', () => {
    const blocked = /@blocked path_regexp (.+)/.exec(caddyfile)?.[1] ?? '';
    for (const token of [
      'admin',
      'realms/master',
      'health',
      'metrics',
      'account',
      'divalhr-provisioning',
      'actuator',
      'api/actuator',
    ]) {
      assert.ok(blocked.includes(token), `${token} must be in the blocked pattern`);
    }
    assert.match(caddyfile, /handle @blocked/);
  });

  it('keeps the OIDC and credential fields out of the access log', () => {
    for (const field of [
      'request>uri regexp',
      'request>headers>Authorization delete',
      'request>headers>Cookie delete',
      'resp_headers>Set-Cookie delete',
      'resp_headers>Location delete',
    ]) {
      assert.ok(caddyfile.includes(field), field);
    }
  });

  it('sends HSTS and a correlation identifier, and keeps the application out of search engines', () => {
    assert.match(caddyfile, /Strict-Transport-Security "max-age=31536000"/);
    assert.match(caddyfile, /X-Request-Id/);
    assert.match(caddyfile, /X-Robots-Tag/);
  });

  it('the commercial name serves only the commercial pages and sends everything else to the application', () => {
    assert.match(caddyfile, /\/legal\/privacy/);
    assert.match(caddyfile, /redir https:\/\/\{\$PROD_APP_HOST\}\{uri\} 308/);
  });
});

describe('the load balancer (L2-3)', () => {
  const alb = tf['alb.tf'];
  it('redirects HTTP to HTTPS and www to the commercial origin, with a modern TLS policy', () => {
    assert.match(alb, /HTTP_301/);
    assert.match(alb, /ELBSecurityPolicy-TLS13|ELBSecurityPolicy-TLS-1-2/);
    assert.match(alb, /protocol\s*=\s*"HTTPS"/);
    assert.match(alb, /drop_invalid_header_fields\s*=\s*true/);
    assert.match(alb, /\/healthz/);
  });
  it('writes no access logs (OIDC query strings) and never creates the certificate', () => {
    assert.doesNotMatch(alb, /access_logs\s*\{[^}]*enabled\s*=\s*true/);
    assert.equal(resources('aws_acm_certificate').length, 0, 'ACM is a separately approved step');
  });
});

describe('secrets (L2-5)', () => {
  const entries = manifest
    .split('\n')
    .filter((line) => line && !line.startsWith('#'))
    .map((line) => line.split('|'));

  it('has well-formed entries with numeric owners and unique files', () => {
    assert.ok(entries.length >= 20);
    for (const [name, file, owner, kind] of entries) {
      assert.match(name, /^[a-z0-9_/.-]+$/i);
      assert.match(file, /^[A-Za-z0-9_./-]+$/);
      assert.match(owner, /^\d+$/);
      assert.ok(['generated', 'operator'].includes(kind), kind);
    }
    assert.equal(new Set(entries.map((e) => e[1])).size, entries.length, 'every file once');
  });

  it('only the e-mail provider password is operator-supplied; everything else is generated', () => {
    assert.deepEqual(
      entries.filter((e) => e[3] === 'operator').map((e) => e[0]),
      ['mail/DIVALHR_MAIL_PASSWORD'],
    );
  });

  it('declares the keys Core needs', () => {
    const files = new Set(entries.map((e) => e[1]));
    for (const key of [
      'core/DIVALHR_EMAIL_LOOKUP_KEY',
      'core/DIVALHR_WEBHOOK_ENCRYPTION_KEY',
      'core/DIVALHR_PAYMENT_ENCRYPTION_KEY',
      'core/DIVALHR_PARTNER_HANDOFF_SIGNING_KEY',
      'ops/provisioner-secret',
      'keycloak/.db-password',
    ]) {
      assert.ok(files.has(key), key);
    }
  });

  it('the scripts never print a secret value or put one on a command line', () => {
    for (const name of ['init-secrets.sh', 'secrets-bootstrap.sh']) {
      const text = read(`ops/production/${name}`);
      assert.doesNotMatch(text, /echo\s+"?\$\(cat/);
      assert.doesNotMatch(text, /--secret-string\s+"?\$/);
    }
    assert.match(read('ops/production/init-secrets.sh'), /chmod 0400/);
    assert.match(read('ops/production/secrets-bootstrap.sh'), /file:\/\/\$SCRATCH\/value/);
    assert.match(read('ops/production/secrets-bootstrap.sh'), /never overwritten or rotated here/);
  });

  it('secrets-bootstrap needs a region and lists generated secrets in a dry run without values', () => {
    const bin = mkdtempSync(join(tmpdir(), 'fakeaws-'));
    writeFileSync(join(bin, 'aws'), '#!/bin/sh\nexit 0\n', { mode: 0o755 });
    const env = { ...process.env, PATH: `${bin}:${process.env.PATH}` };
    const none = spawnSync('bash', [join(prodOps, 'secrets-bootstrap.sh')], {
      env,
      encoding: 'utf8',
    });
    assert.notEqual(none.status, 0);
    assert.match(none.stderr, /--region is required/);
    const dry = spawnSync(
      'bash',
      [join(prodOps, 'secrets-bootstrap.sh'), '--region', 'x-test-1', '--dry-run'],
      { env, encoding: 'utf8' },
    );
    assert.equal(dry.status, 0, dry.stderr);
    assert.match(
      dry.stdout,
      /would create \(if absent\): divalhr\/production\/core\/DIVALHR_EMAIL_LOOKUP_KEY/,
    );
    assert.match(dry.stdout, /operator-supplied: divalhr\/production\/mail\/DIVALHR_MAIL_PASSWORD/);
  });
});

describe('the production realm (L2-6)', () => {
  const template = join(root, 'infrastructure/hr-dev/keycloak/realm-divalhr-test.json');
  const render = (origin = 'https://app.divalhr.com') => {
    const dir = mkdtempSync(join(tmpdir(), 'realm-'));
    writeFileSync(join(dir, 'secret'), 'x'.repeat(48));
    const out = join(dir, 'realm.json');
    const result = spawnSync(
      'python3',
      [join(prodOps, 'render-realm.py'), template, out, origin, join(dir, 'secret')],
      { encoding: 'utf8' },
    );
    return { result, out };
  };

  it('renders the production realm: no accounts, no development origin, exact redirect, MFA marker kept', () => {
    const { result, out } = render();
    assert.equal(result.status, 0, result.stderr);
    const realm = JSON.parse(readFileSync(out, 'utf8'));
    assert.equal(realm.realm, 'divalhr-production');
    assert.deepEqual(
      (realm.users ?? []).filter((u) => !u.serviceAccountClientId),
      [],
      'no seeded or public development accounts',
    );
    assert.doesNotMatch(JSON.stringify(realm), /hr-dev|dival\.ai|localhost|divalhr-test|mailpit/i);
    const web = realm.clients.find((c) => c.clientId === 'divalhr-web');
    assert.ok(
      web.redirectUris.every((u) => u.startsWith('https://app.divalhr.com/')),
      'redirects stay on the application origin',
    );
    assert.equal(realm.registrationAllowed, false);
    assert.ok((realm.roles?.realm ?? []).some((r) => r.name === 'divalhr-privileged-mfa'));
  });

  it('refuses a non-https or development application origin', () => {
    for (const origin of [
      'http://app.divalhr.com',
      'https://hr-dev.dival.ai',
      'https://localhost',
    ]) {
      assert.notEqual(render(origin).result.status, 0, origin);
    }
  });
});

describe('the launch lock is closed by default and unliftable by LAUNCH-002 (L2-12)', () => {
  const lock = (data, ...args) =>
    spawnSync('bash', [join(prodOps, 'launch-lock.sh'), ...args], {
      env: { PATH: process.env.PATH, PROD_DATA: data },
      encoding: 'utf8',
    });
  const dataDir = (state) => {
    const dir = mkdtempSync(join(tmpdir(), 'prod-'));
    mkdirSync(join(dir, 'state'));
    mkdirSync(join(dir, 'config'));
    if (state !== undefined) writeFileSync(join(dir, 'state/launch-lock'), state);
    return dir;
  };

  it('is CLOSED when the file is missing, empty, lower-case, padded or anything but OPEN', () => {
    for (const state of [undefined, '', 'open', 'OPEN-ish', 'TRUE', '1', 'OPEN OPEN']) {
      const dir = dataDir(state);
      const result = lock(dir, 'status');
      assert.match(result.stdout, /launch-lock: CLOSED/, JSON.stringify(state));
      assert.match(result.stdout, /customer-onboarding: REFUSED/);
      assert.equal(lock(dir, 'require-closed').status, 0, JSON.stringify(state));
    }
  });

  it('reads OPEN only for the exact word, and then require-closed fails', () => {
    const dir = dataDir('OPEN\n');
    assert.match(lock(dir, 'status').stdout, /launch-lock: OPEN/);
    assert.notEqual(lock(dir, 'require-closed').status, 0);
  });

  it('reports the e-mail prerequisite and has no command that lifts the lock', () => {
    const dir = dataDir(undefined);
    assert.match(lock(dir, 'status').stdout, /email: NOT_CONFIGURED/);
    for (const verb of ['lift', 'open', 'unlock']) assert.notEqual(lock(dir, verb).status, 0, verb);
  });

  it('Terraform creates the parameter CLOSED and ignores later changes; nothing in the repository writes OPEN', () => {
    const text = tf['launch-lock.tf'];
    assert.match(text, /value\s*=\s*"CLOSED"/);
    assert.match(text, /ignore_changes\s*=\s*\[value\]/);
    assert.doesNotMatch(allTf, /value\s*=\s*"OPEN"/);
    assert.doesNotMatch(scriptsText, /put-parameter/);
  });
});

describe('guards of the production scripts (L2-10)', () => {
  const run = (script, args = [], env = {}) =>
    spawnSync('bash', [join(prodOps, script), ...args], {
      env: { PATH: process.env.PATH, HOME: mkdtempSync(join(tmpdir(), 'home-')), ...env },
      encoding: 'utf8',
      input: '',
    });
  const sourceLib = (env) =>
    spawnSync('bash', ['-c', `. ${join(prodOps, 'lib.sh')}; echo reached`], {
      env: { PATH: process.env.PATH, ...env },
      encoding: 'utf8',
    });

  it('refuse to run in the hr-dev rehearsal mode', () => {
    const result = sourceLib({ HR_DEV_REHEARSAL: '1' });
    assert.notEqual(result.status, 0);
    assert.doesNotMatch(result.stdout, /reached/);
  });

  it('refuse a test or development name in their settings', () => {
    for (const [key, value] of [
      ['PROD_DATA', '/srv/divalhr-test'],
      ['PROD_APP_HOST', 'hr-dev.dival.ai'],
      ['PROD_PROJECT', 'divalhr-hrdev'],
      ['PROD_PUBLIC_HOST', 'localhost'],
    ]) {
      const result = sourceLib({ [key]: value });
      assert.notEqual(result.status, 0, key);
      assert.doesNotMatch(result.stdout, /reached/, key);
    }
  });

  it('deploy.sh stops without the configuration, the evidence run, the approval or a terminal', () => {
    assert.match(run('deploy.sh', ['0'.repeat(40)]).stderr, /missing .*deploy\.env/);
    const dir = mkdtempSync(join(tmpdir(), 'env-'));
    const envFile = join(dir, 'deploy.env');
    writeFileSync(
      envFile,
      'DIVALHR_PROD_REGION=x\nDIVALHR_PROD_INSTANCE_ID=i\nDIVALHR_PROD_EVIDENCE_BUCKET=b\nDIVALHR_PROD_REGISTRY=r\n',
    );
    const env = { DIVALHR_PROD_ENV: envFile };
    assert.match(run('deploy.sh', ['abc'], env).stderr, /full 40-character/);
    assert.match(run('deploy.sh', ['0'.repeat(40)], env).stderr, /--evidence-run/);
    assert.match(
      run('deploy.sh', ['0'.repeat(40), '--evidence-run', dir], env).stderr,
      /--approval/,
    );
    assert.match(
      run('deploy.sh', ['0'.repeat(40), '--evidence-run', dir, '--approval', 'ADR-0048#1'], env)
        .stderr,
      /interactive|repository/,
    );
  });

  it('the deployment gate requires the founder phrase, a fresh full verification and tree-bound evidence', () => {
    const text = read('ops/production/deploy.sh');
    assert.match(text, /DEPLOY PRODUCTION/);
    assert.match(text, /merge-base --is-ancestor/);
    assert.match(text, /provenance\.py/);
    assert.match(text, /evidence\.py" decide/);
    assert.doesNotMatch(
      text,
      /--deployed/,
      'the class is never narrowed from a previous deployment',
    );
    assert.match(text, /PROD_EVIDENCE_MAX_AGE_HOURS/);
    assert.match(text, /release\.bundle/);
    assert.doesNotMatch(text, /\bssh |\bscp /, 'no SSH: Systems Manager only');
    const remote = read('ops/production/remote-deploy.sh');
    assert.match(remote, /list-heads/);
    assert.match(remote, /evidence\.py" decide/);
    assert.doesNotMatch(remote, /--deployed/);
    assert.match(remote, /rollback\.sh" --from/);
    assert.match(remote, /backup\.sh" --reason pre-deploy/);
    assert.match(remote, /prod_export_env/);
    assert.match(remote, /realm-verify\.sh/);
  });

  it('build-push.sh builds from git archive of an origin/main commit and refuses a non-SHA', () => {
    assert.match(run('build-push.sh', ['nothex']).stderr, /full 40-character/);
    const text = read('ops/production/build-push.sh');
    assert.match(text, /git archive "\$SHA"/);
    assert.match(text, /merge-base --is-ancestor/);
    for (const service of ['keycloak', 'core-api', 'ai-service', 'web'])
      assert.match(text, new RegExp(`build_push ${service}`));
  });

  it('the ECR repositories Terraform creates are the ones the tooling pushes', () => {
    const services = /variable "services" \{[^]*?default\s*=\s*\[([^\]]+)\]/.exec(
      tf['variables.tf'],
    );
    assert.ok(services, 'the service repositories come from the services variable');
    assert.deepEqual([...services[1].matchAll(/"([^"]+)"/g)].map((m) => m[1]).sort(), [
      'ai-service',
      'core-api',
      'keycloak',
      'web',
    ]);
  });

  it('rollback needs a pre-deploy dump and restores both databases before starting the previous release', () => {
    const text = read('ops/production/rollback.sh');
    assert.match(text, /hr_latest_backup pre-deploy/);
    assert.match(text, /hr_restore_backup "\$FROM" "\$backup"/);
    assert.match(text, /no pre-deploy backup/);
    assert.ok(text.indexOf('hr_restore_backup "$FROM"') < text.indexOf('up -d --no-build'));
  });

  it('a backup counts only after the off-host copy is verified, and never contains secrets', () => {
    const text = read('ops/production/backup.sh');
    assert.match(text, /NOT counted as successful/);
    assert.ok(
      text.indexOf('s3 cp') < text.indexOf('date +%s > "$PROD_DATA/state/last-backup-success"'),
    );
    assert.doesNotMatch(
      text
        .split('\n')
        .filter((l) => !l.startsWith('#'))
        .join('\n'),
      /secrets/,
    );
    assert.match(text, /--sse aws:kms/);
  });

  it('the restore drill is isolated, loopback-only, mail-neutral and proves the migration state', () => {
    const text = read('ops/production/restore-drill.sh');
    assert.match(text, /PROD_HTTPS_PUBLISH=127\.0\.0\.1:29443/);
    assert.match(text, /drill-no-mail\.invalid/);
    assert.match(text, /hr_flyway_count/);
    assert.match(text, /hr_release_migrations/);
    assert.match(text, /live_failures/);
    assert.match(text, /off-host/);
  });
});

describe('observability (L2-11)', () => {
  const alarms = tf['monitoring.tf'];
  const metricsScript = read('ops/production/metrics.sh');

  it('every metric the host publishes has an alarm and vice versa, in one namespace with no dimensions', () => {
    const published = [...metricsScript.matchAll(/"MetricName\\":\\"([A-Za-z0-9]+)\\"/g)]
      .map((m) => m[1])
      .sort();
    const alarmed = [...alarms.matchAll(/metric\s*=\s*"([A-Za-z0-9]+)"/g)].map((m) => m[1]).sort();
    assert.ok(published.length >= 11);
    assert.deepEqual(published, alarmed);
    assert.match(metricsScript, /NS="Divalhr\/Production"/);
    assert.match(alarms, /namespace\s*=\s*"Divalhr\/Production"/);
    assert.doesNotMatch(metricsScript, /Dimensions/);
  });

  it('the backup-age and restore-drill-age alarms exist and a silent host raises an alarm', () => {
    assert.match(alarms, /"backup-age-hours"[^\n]*threshold = 26[^\n]*missing = "breaching"/);
    assert.match(alarms, /"restore-drill-age"[^\n]*missing = "breaching"/);
    assert.match(alarms, /"heartbeat"[^\n]*missing = "breaching"/);
  });

  it('covers 5xx, identity, workers, WAF, containers, disk, memory, CPU, deploy, watchdog and the certificate', () => {
    for (const needle of [
      'HTTPCode_Target_5XX_Count',
      'BlockedRequests',
      'UnHealthyHostCount',
      'CPUUtilization',
      'DiskUsedPercent',
      'MemoryUsedPercent',
      'UnhealthyContainers',
      'IdentityFailures',
      'WorkerFailures',
      'DeployFailed',
      'WatchdogRestarts',
      'DaysToExpiry',
      'HealthCheckStatus',
    ]) {
      assert.ok(alarms.includes(needle), needle);
    }
  });

  it('notifies the monitored Dival address by e-mail and encrypts the topic', () => {
    assert.match(variable('alarm_email'), /default\s*=\s*"contact@dival\.ai"/);
    assert.match(alarms, /protocol\s*=\s*"email"/);
    assert.match(alarms, /kms_master_key_id/);
  });

  it('metrics are counts and gauges: log lines are only counted, never kept', () => {
    assert.match(metricsScript, /count_lines\(\)[^\n]*grep -cE/);
    assert.doesNotMatch(metricsScript, /docker logs[^\n|]*>/, 'no log content is written anywhere');
  });

  it('the units run the production scripts on their timers', () => {
    assert.deepEqual(readdirSync(join(prodOps, 'systemd')).sort(), [
      'divalhr-prod-backup.service',
      'divalhr-prod-backup.timer',
      'divalhr-prod-metrics.service',
      'divalhr-prod-metrics.timer',
      'divalhr-prod-watchdog.service',
      'divalhr-prod-watchdog.timer',
    ]);
    assert.match(
      read('ops/production/systemd/divalhr-prod-backup.timer'),
      /OnCalendar=\*-\*-\* 02:15:00 UTC/,
    );
    assert.match(read('ops/production/systemd/divalhr-prod-metrics.timer'), /OnUnitActiveSec=5min/);
    assert.match(
      read('ops/production/systemd/divalhr-prod-backup.service'),
      /ops\/production\/backup\.sh --reason nightly/,
    );
  });
});

describe('every production script parses', () => {
  for (const name of readdirSync(prodOps).filter((n) => n.endsWith('.sh'))) {
    it(`${name} is valid bash`, () => {
      execFileSync('bash', ['-n', join(prodOps, name)]);
    });
  }
  it('render-realm.py compiles', () => {
    execFileSync('python3', [
      '-I',
      '-c',
      'import ast,sys; ast.parse(open(sys.argv[1]).read())',
      join(prodOps, 'render-realm.py'),
    ]);
  });
});
