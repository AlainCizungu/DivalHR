// SEC-001: the static access-log policy of the test environment's Caddy and of the web image's
// nginx. OIDC authorization data (code, state, session_state) travels in callback queries, in
// referring URLs and in redirect targets, so none of these may reach an access log.
// Each check returns a list of violations (empty when the configuration complies).

/** Caddy filter fields that must be deleted outright, never rewritten. */
export const CADDY_DELETED = [
  'request>headers>Authorization',
  'request>headers>Cookie',
  'request>headers>Referer',
  'resp_headers>Location',
  'resp_headers>Set-Cookie',
];

/** nginx variables the safe format may use, and only these. */
export const NGINX_ALLOWED = [
  'time_iso8601',
  'request_method',
  'divalhr_path',
  'server_protocol',
  'status',
  'body_bytes_sent',
  'request_time',
];

/** Variables that carry the query, the referrer, cookies, credentials or redirect targets. */
export const NGINX_FORBIDDEN = [
  'request',
  'request_uri',
  'args',
  'query_string',
  'is_args',
  'http_referer',
  'http_cookie',
  'cookie_',
  'arg_',
  'http_authorization',
  'sent_http_location',
  'sent_http_set_cookie',
  'request_body',
];

const withoutComments = (text) =>
  text
    .split('\n')
    .map((line) => line.replace(/(^|\s)#.*$/, ''))
    .join('\n');

/** The body of the `(accesslog) { ... }` snippet, comments removed; null when absent. */
export function caddyAccessLogSnippet(caddyfile) {
  const lines = withoutComments(caddyfile).split('\n');
  const start = lines.findIndex((line) => line.trim() === '(accesslog) {');
  if (start < 0) return null;
  const end = lines.findIndex((line, i) => i > start && line === '}');
  return end < 0 ? null : lines.slice(start + 1, end).join('\n');
}

export function checkCaddy(caddyfile) {
  const problems = [];
  const snippet = caddyAccessLogSnippet(caddyfile);
  if (snippet === null) return ['no (accesslog) snippet'];
  const fields = snippet
    .split('\n')
    .map((line) => line.trim().split(/\s+/))
    .filter((words) => words[0] && /^(request|resp_headers|common_log|user_id)/.test(words[0]));
  for (const field of CADDY_DELETED) {
    const entry = fields.find((words) => words[0] === field);
    if (!entry) problems.push(`${field} is not filtered`);
    else if (entry[1] !== 'delete' || entry.length !== 2) problems.push(`${field} is not deleted`);
  }
  const uri = fields.find((words) => words[0] === 'request>uri');
  if (!uri || uri.slice(1).join(' ') !== 'regexp "\\?.*$" ""') {
    problems.push('request>uri does not remove the query');
  }
  if (!/\bformat filter \{/.test(snippet)) problems.push('the log is not filtered');
  if (!/\bwrap json\b/.test(snippet)) problems.push('the filtered log is not JSON');
  // Every site imports the filtered log; no other log directive bypasses it.
  const body = withoutComments(caddyfile);
  if ((body.match(/^\s*log\b/gm) ?? []).length !== 1)
    problems.push('a log directive outside the snippet');
  return problems;
}

export function checkNginx(template) {
  const problems = [];
  const body = withoutComments(template);
  const formats = [...body.matchAll(/log_format\s+(\S+)\s+((?:'[^']*'\s*)+);/g)];
  const safe = formats.find((m) => m[1] === 'divalhr_safe');
  if (!safe) return ['no divalhr_safe log_format'];
  if (formats.length !== 1) problems.push('more than one log_format');
  const used = [...safe[2].matchAll(/\$\{?([a-z_][a-z0-9_]*)/gi)].map((m) => m[1]);
  for (const name of used) {
    if (!NGINX_ALLOWED.includes(name)) problems.push(`the format uses $${name}`);
  }
  for (const name of ['request_method', 'divalhr_path', 'status']) {
    if (!used.includes(name)) problems.push(`the format lacks $${name}`);
  }
  // The forbidden variables appear nowhere in the template (no map or set can smuggle them in).
  for (const name of NGINX_FORBIDDEN) {
    const pattern = name.endsWith('_')
      ? new RegExp(`\\$\\{?${name}`, 'i')
      : new RegExp(`\\$\\{?${name}(?![a-z0-9_])`, 'i');
    if (pattern.test(body)) problems.push(`the template uses $${name}`);
  }
  const accessLogs = [...body.matchAll(/^\s*access_log\s+([^;]+);/gm)].map((m) =>
    m[1].trim().split(/\s+/),
  );
  const on = accessLogs.filter((args) => args[0] !== 'off');
  if (on.length !== 1) problems.push(`expected exactly one active access_log, found ${on.length}`);
  else if (on[0][1] !== 'divalhr_safe')
    problems.push('the active access_log does not use divalhr_safe');
  if (!/set \$divalhr_path \$uri;/.test(body))
    problems.push('$divalhr_path is not taken from $uri');
  return problems;
}
