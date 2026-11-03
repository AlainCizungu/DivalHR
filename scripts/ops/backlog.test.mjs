import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

/**
 * MVP-093A (Issue #175, D93-13, required test 16): docs/BACKLOG.md reconciles with Git evidence.
 * Document structure checks always run; the Git checks run only when the history that holds the
 * cited merge commits is available (a shallow clone skips them rather than passing silently).
 */

const root = fileURLToPath(new URL('../../', import.meta.url));
const backlog = readFileSync(`${root}docs/BACKLOG.md`, 'utf8');

/** The releases that hr-dev ran, in deployment order (known deployment facts). */
const DEPLOYED = [
  '1d910234e09c56ba6647dfbdd2f2de2aa83d0ebf', // MVP-081, PR #170: deployed first
  '5d4cd1068b63b84e33671123405d6bef1178b6aa', // SEC-003, PR #174: deployed second
  '3223454379a9b6342fe3c6b2728d8f17680466e7', // MVP-093A, PR #176: deployed third
  '035072d3d6b0d4afc126490b2414bab43206d18d', // MVP-094C, PR #182: the payroll release train
  '5b0ff854693e702f0cc05f334609d96fb962346a', // MVP-095A, PR #184: payment destinations and batches
  'b60300f998b5f6cf6bc380a93241be7e93fb4bb7', // MVP-095B, PR #188: synthetic provider submission
  'c93ce0ec0a828e9dc4fd78298ca10475208ff58a', // R187-1, PR #189: trimmed provider key, running
];
const RUNNING = DEPLOYED[6];
/** The stories of the payroll release train: contained in the single release that deployed them. */
const TRAIN = ['MVP-094A', 'MVP-094B'];
/** Stories that D93-13 requires to carry a real merged or deployed status. */
const REQUIRED = [
  'MVP-080',
  'MVP-081',
  'MVP-090A',
  'MVP-091',
  'MVP-092',
  'MVP-100',
  'MVP-101',
  'MVP-110',
  'MVP-111',
  'SEC-003',
];

function git(...args) {
  return execFileSync('git', args, {
    cwd: root,
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
  }).trim();
}

function hasCommit(sha) {
  try {
    git('cat-file', '-e', `${sha}^{commit}`);
    return true;
  } catch {
    return false;
  }
}

/** Rows of the "Git evidence" table. */
function evidenceRows() {
  const section = backlog.split('### Git evidence for the stories reconciled by MVP-093A')[1];
  assert.ok(section, 'the Git evidence section exists');
  const table = section.split('\n## ')[0];
  return table
    .split('\n')
    .filter((line) => /^\|\s*[A-Z]+\d*-/u.test(line))
    .map((line) => {
      const cells = line.split('|').map((cell) => cell.trim());
      return {
        story: cells[1],
        pr: cells[2],
        sha: cells[3].replaceAll('`', ''),
        date: cells[4],
        state: cells[5],
      };
    });
}

/** Every story section (### or ####) with the text up to the next heading. */
function storySections() {
  const parts = backlog.split(/^(#{3,4} .*)$/mu);
  const sections = [];
  for (let index = 1; index < parts.length; index += 2) {
    const match = /^#{3,4} ((?:MVP|UI|OPS|SEC|DEVX)-\d+[A-Z]?)\b/u.exec(parts[index]);
    if (match) sections.push({ id: match[1], heading: parts[index], body: parts[index + 1] });
  }
  return sections;
}

test('the header names the deployed hr-dev release and the one deployed before it', () => {
  const header = backlog.split('## Epic 1')[0];
  for (const sha of DEPLOYED) assert.ok(header.includes(sha), `the header names ${sha}`);
  assert.match(header, new RegExp(`runs \`main\` at \`${RUNNING}\``, 'u'));
  assert.match(header, /PR #184, MVP-095A/u);
  assert.match(header, /PR #188, MVP-095B/u);
  assert.match(header, /PR #189, R187-1/u);
  assert.match(header, /as of 2026-11-03/u);
  // The payroll release train (094A, 094B, 094C) is merged, deployed and accepted, from Git.
  assert.match(header, /PR #178, merge commit `dcd4d7e24c6a0c2a3af68a481a576d5eb3cc5430`/u);
  assert.match(header, /PR #180, merge commit `995a6937c1d22aeef3ab0023856195a1adcae4e2`/u);
  assert.match(header, /migrations V44, V45 and V46 are applied; Issue #181 is closed/u);
  assert.match(header, /`CD-SALARY-PREVIEW` is still DRAFT and inactive/u);
  assert.match(header, /migration V47 is applied/u);
  assert.match(header, /Issue #183 is closed/u);
  assert.match(header, /migration V48 is applied/u);
  assert.match(header, /Issue #187 is closed/u);
  // MVP-095C (Issue #190): in progress, not merged, not deployed.
  assert.match(
    header,
    /MVP-095C \(Issue #190, migration V49\) is in progress: not merged, not deployed/u,
  );
});

test('every listed story has a status', () => {
  const statusWord =
    /\b(Complete|Merged|In progress|In review|Planned|Deferred|Umbrella story)\b/iu;
  const sections = storySections();
  assert.ok(sections.length >= 38, `found ${sections.length} stories`);
  const ids = sections.map((section) => section.id);
  assert.equal(new Set(ids).size, ids.length, 'story ids are unique');
  for (const section of sections) {
    assert.match(section.body, statusWord, `${section.id} has a status`);
  }
  for (const id of [...REQUIRED, 'MVP-093A']) assert.ok(ids.includes(id), `${id} is listed`);
});

test('the required stories carry a merged or deployed status, and none is still "not merged"', () => {
  const sections = new Map(storySections().map((section) => [section.id, section]));
  for (const id of REQUIRED) {
    const text = sections.get(id).body;
    assert.match(text, /\b(Merged|merged)\b/u, `${id} is merged`);
    const opening = text.split('\n').slice(0, 4).join('\n');
    assert.doesNotMatch(opening, /not merged|In review/u, `${id}`);
  }
  assert.match(sections.get('SEC-003').body, /deployed to hr-dev on 2026-10-29/u);
});

test('MVP-093A and the payroll train (094A, 094B, 094C) are merged and deployed; MVP-095A and MVP-095B are deployed and MVP-095C is in progress for Issue #190', () => {
  const epic10 = backlog.split('## Epic 10')[1].split('\n## ')[0];
  assert.match(epic10, /### MVP-093A /u);
  assert.match(epic10, /### MVP-094A /u);
  const sections = storySections();
  const done = sections.find((entry) => entry.id === 'MVP-093A');
  assert.match(done.body, /Issue #175\. Merged \(PR #176/u);
  assert.match(done.body, /deployed to hr-dev on 2026-10-29/u);
  const next = sections.find((entry) => entry.id === 'MVP-094A');
  assert.match(
    next.body,
    /Issue #177\. Merged \(PR #178, merge commit `dcd4d7e24c6a0c2a3af68a481a576d5eb3cc5430`\), deployed to hr-dev with the payroll release train/u,
  );
  assert.doesNotMatch(next.body.split('\n').slice(0, 4).join('\n'), /In progress/u);
  assert.match(next.body, /not taxable gross, net pay or money owed/u);
  const statutory = sections.find((entry) => entry.id === 'MVP-094B');
  assert.match(epic10, /### MVP-094B /u);
  assert.match(
    statutory.body,
    /Issue #179\. Merged \(PR #180, merge commit `995a6937c1d22aeef3ab0023856195a1adcae4e2`\), deployed to hr-dev with the payroll release train/u,
  );
  const approval = sections.find((entry) => entry.id === 'MVP-094C');
  assert.match(epic10, /### MVP-094C /u);
  assert.match(
    approval.body,
    /Issue #181\. Merged \(PR #182, merge commit `035072d3d6b0d4afc126490b2414bab43206d18d`\), deployed to hr-dev and accepted/u,
  );
  assert.match(approval.body, /Not proof of payment/u);
  const payment = sections.find((entry) => entry.id === 'MVP-095A');
  assert.match(epic10, /### MVP-095A /u);
  assert.match(
    payment.body,
    /Issue #183\. Merged \(PR #184, merge commit `5b0ff854693e702f0cc05f334609d96fb962346a`\), deployed to hr-dev and accepted/u,
  );
  assert.match(
    payment.body,
    /Nothing is sent to a bank or provider by MVP-095A itself, no money moves/u,
  );
  assert.doesNotMatch(payment.body, /compliant|disbursed|payment sent/iu);
  const submission = sections.find((entry) => entry.id === 'MVP-095B');
  assert.match(epic10, /### MVP-095B /u);
  assert.match(
    submission.body,
    /Issue #187\. Merged \(PR #188, merge commit `b60300f998b5f6cf6bc380a93241be7e93fb4bb7`\), deployed to hr-dev and accepted; Issue #187 is closed/u,
  );
  assert.match(
    submission.body,
    /R187-1 \(PR #189, merge commit `c93ce0ec0a828e9dc4fd78298ca10475208ff58a`/u,
  );
  assert.match(submission.body, /Accepted for processing does not mean paid/u);
  assert.match(submission.body, /SYNTHETIC-TEST/u);
  assert.doesNotMatch(submission.body, /compliant|disbursed|payment sent|\bpaid status exists/iu);
  const reconciliation = sections.find((entry) => entry.id === 'MVP-095C');
  assert.match(epic10, /### MVP-095C /u);
  assert.match(reconciliation.body, /Issue #190\. In progress \(not merged, not deployed;/u);
  // D94B-13: only a DRAFT pack ships, so the backlog never calls the statutory preview available.
  assert.match(statutory.body, /`CD-SALARY-PREVIEW` version 1, \*\*DRAFT\*\*/u);
  assert.match(statutory.body, /stays under "coming next"/u);
  assert.match(statutory.body, /Professional verification required/u);
  assert.doesNotMatch(statutory.body, /compliant payroll|payroll compliance/iu);
});

test('open maintenance is separate and names Issues #151 and #173 as duplicates, without closing them', () => {
  const [, tail] = backlog.split('\n## Open maintenance');
  assert.ok(tail, 'an "Open maintenance" section exists');
  assert.match(tail, /#151/u);
  assert.match(tail, /#173/u);
  assert.match(tail, /duplicate/iu);
  assert.match(tail, /consolidate/iu);
  assert.match(tail, /does not close either/iu);
});

test('the evidence table covers every required story and names only known deployed releases', () => {
  const rows = evidenceRows();
  const stories = rows.map((row) => row.story);
  for (const id of REQUIRED) assert.ok(stories.includes(id), `${id} has an evidence row`);
  for (const row of rows) {
    assert.match(row.sha, /^[0-9a-f]{40}$/u, `${row.story} names a full commit`);
    assert.match(row.date, /^\d{4}-\d{2}-\d{2}$/u);
    assert.match(row.state, /^(contained|deployed)$/u);
    if (row.state === 'deployed') {
      assert.ok(DEPLOYED.includes(row.sha), `${row.story}: only the known releases are "deployed"`);
    }
  }
  assert.deepEqual(
    rows.filter((row) => row.state === 'deployed').map((row) => row.sha),
    DEPLOYED,
  );
});

test('the evidence table matches Git: merge subject, PR number, date and ancestry', (t) => {
  const rows = evidenceRows();
  if (!rows.every((row) => hasCommit(row.sha)) || !hasCommit(RUNNING)) {
    t.skip('the merge commits are not in this clone (shallow history)');
    return;
  }
  const firstParent = new Set(git('rev-list', '--first-parent', '--merges', RUNNING).split('\n'));
  for (const row of rows) {
    const [subject, committed] = git('show', '-s', '--format=%s%n%cI', row.sha).split('\n');
    assert.ok(
      firstParent.has(row.sha),
      `${row.story}: merge commit is on the first-parent history`,
    );
    assert.match(
      subject,
      new RegExp(`^Merge pull request ${row.pr} from `, 'u'),
      `${row.story} subject`,
    );
    assert.equal(committed.slice(0, 10), row.date, `${row.story}: merge date`);
    if (row.state === 'contained' && !TRAIN.includes(row.story)) {
      git('merge-base', '--is-ancestor', row.sha, DEPLOYED[0]); // throws if not an ancestor
    }
    git('merge-base', '--is-ancestor', row.sha, RUNNING);
  }
});

/**
 * MVP-095C (D95C-14): release and capability claims are checked, not only described. The rules are
 * pure functions over text so that each can be proved to fail on a stale fixture.
 */
const SHA_PATTERN = /\b[0-9a-f]{40}\b/gu;

/** Rule 1: the release the header says hr-dev runs equals the expected one, and the evidence table agrees. */
export function releaseClaimProblems(text, expected) {
  const header = text.split('## Epic 1')[0];
  const problems = [];
  const running = /runs `main` at `([0-9a-f]{40})`/u.exec(header)?.[1];
  if (running !== expected) problems.push(`header runs ${running}, expected ${expected}`);
  const lastDeployed = [
    ...text.matchAll(
      /^\|\s*[A-Z]+\d*-[^|]*\|[^|]*\|\s*`([0-9a-f]{40})`[^|]*\|[^|]*\|\s*deployed\s*\|/gmu,
    ),
  ]
    .map((match) => match[1])
    .at(-1);
  if (lastDeployed !== expected) problems.push(`last deployed evidence row is ${lastDeployed}`);
  return problems;
}

/** Story sections as { id, body } for any backlog text. */
function sectionsOf(text) {
  const parts = text.split(/^(#{3,4} .*)$/mu);
  const out = [];
  for (let index = 1; index < parts.length; index += 2) {
    const match = /^#{3,4} ((?:MVP|UI|OPS|SEC|DEVX)-\d+[A-Z]?)\b/u.exec(parts[index]);
    if (match) out.push({ id: match[1], body: parts[index + 1] });
  }
  return out;
}

/**
 * Rule 2: a capability claimed active (by story id) needs a backlog entry that is deployed and accepted.
 * Rule 3: MVP-095C cannot be active or done while its entry says not deployed.
 */
export function capabilityClaimProblems(text, activeStories) {
  const sections = new Map(sectionsOf(text).map((entry) => [entry.id, entry.body]));
  const problems = [];
  for (const id of activeStories) {
    const body = sections.get(id);
    if (!body) problems.push(`${id} is claimed active but has no backlog entry`);
    else if (!/deployed to hr-dev and accepted/u.test(body.split('\n').slice(0, 4).join('\n')))
      problems.push(`${id} is claimed active without a "deployed and accepted" backlog entry`);
    else if (/not deployed/u.test(body.split('\n').slice(0, 4).join('\n')))
      problems.push(`${id} is claimed active but its entry says not deployed`);
  }
  return problems;
}

export function reconciliationClaimProblems(text, claimedActive) {
  const body =
    new Map(sectionsOf(text).map((entry) => [entry.id, entry.body])).get('MVP-095C') ?? '';
  const opening = body.split('\n').slice(0, 4).join('\n');
  const notDeployed = /not deployed/u.test(opening);
  if (claimedActive && notDeployed)
    return ['MVP-095C is active or done while its entry says not deployed'];
  if (/\bComplete\b|\bdone\b/iu.test(opening.replace(/not deployed/gu, '')) && notDeployed)
    return ['MVP-095C is marked done while its entry says not deployed'];
  return [];
}

/** The capabilities the landing page and homes list as active in the payment train. */
const ACTIVE_STORIES = ['MVP-095A', 'MVP-095B'];

test('truth rule 1: the stated release equals the deployed release, and a stale header fails', () => {
  assert.deepEqual(releaseClaimProblems(backlog, RUNNING), []);
  const stale = backlog.replace(
    `runs \`main\` at \`${RUNNING}\``,
    `runs \`main\` at \`${DEPLOYED[4]}\``,
  );
  assert.notEqual(stale, backlog);
  assert.ok(releaseClaimProblems(stale, RUNNING).length > 0, 'a stale header SHA fails');
  const staleTable = backlog.replace(/^\| R187-1 .*$/mu, '');
  assert.ok(
    releaseClaimProblems(staleTable, RUNNING).length > 0,
    'a missing latest evidence row fails',
  );
  assert.ok(SHA_PATTERN.test(RUNNING));
});

test('truth rule 2: every capability claimed active has a deployed-and-accepted backlog entry', () => {
  assert.deepEqual(capabilityClaimProblems(backlog, ACTIVE_STORIES), []);
  assert.ok(
    capabilityClaimProblems(backlog, ['MVP-095C']).length > 0,
    'reconciliation is not deployed, so claiming it active fails',
  );
  const stale = backlog.replace(
    'merge commit `b60300f998b5f6cf6bc380a93241be7e93fb4bb7`), deployed to hr-dev and accepted',
    'merge commit `b60300f998b5f6cf6bc380a93241be7e93fb4bb7`), merged',
  );
  assert.notEqual(stale, backlog);
  assert.ok(capabilityClaimProblems(stale, ACTIVE_STORIES).length > 0, 'a stale entry fails');
  assert.ok(capabilityClaimProblems(backlog, ['MVP-999']).length > 0, 'an unknown story fails');
});

test('truth rule 3: MVP-095C is not active or done while its entry says not deployed', () => {
  assert.deepEqual(reconciliationClaimProblems(backlog, false), []);
  assert.ok(reconciliationClaimProblems(backlog, true).length > 0, 'claiming it active fails');
  const done = backlog.replace('**Issue #190. In progress', '**Issue #190. Complete, In progress');
  assert.notEqual(done, backlog);
  assert.ok(reconciliationClaimProblems(done, false).length > 0, 'marking it done fails');
});

test('the landing page and homes state MVP-095C as in progress, never active', () => {
  const read = (path) => readFileSync(`${root}${path}`, 'utf8');
  const catalogue = read('apps/web/src/features/landing/catalogue.ts');
  assert.match(catalogue, /'paymentSubmission'/u);
  assert.doesNotMatch(catalogue, /'reconciliation'/u);
  for (const locale of ['en', 'fr']) {
    const strings = JSON.parse(read(`packages/localization/locales/${locale}/common.json`));
    const now = strings.landing.modules.now;
    assert.ok(now.paymentSubmission, `${locale}: submission is listed as available now`);
    assert.doesNotMatch(
      Object.values(now).join(' '),
      /reconcil|rapproch/iu,
      `${locale}: no active reconciliation`,
    );
    const next = strings.landing.modules.next.disbursement;
    assert.match(next, /in progress|en cours/u, `${locale}: reconciliation is in progress`);
  }
});
