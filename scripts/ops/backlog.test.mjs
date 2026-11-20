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
  'c93ce0ec0a828e9dc4fd78298ca10475208ff58a', // R187-1, PR #189: trimmed provider key
  'e8759cc5ca71cb8a54dc22b97aa9b6760fa1969f', // MVP-095C, PR #191: reconciliation of uncertain submissions (synthetic provider only)
  'ce871f48e7be14d824a0800bc7a03be05b72d2ff', // TRUTH-002, PR #193: truth refresh for MVP-095C, preserved as the previous release
  'c368d58b15e1aeddb755997728154a497348c07c', // MVP-096, PR #195: accounting journal export (manual export, never a posting), the latest capability release before MVP-097A
  '55b130f5bde8eedee4589f5e00b9e17d927457f1', // TRUTH-003, PR #199: presentation and truth release for MVP-096, the previous release
  '4b947a6cfa2135369cd2e8ac1e2710c84a2bb525', // MVP-120A, PR #205: consented salary-advance requests
  'd0f6dd1ed9c8c26a4d9055f9c225a12b30eef0b4', // MVP-120B, PR #207: controlled synthetic partner handoff
  '769fa601e058f6f1ee390d206215113b4ca66963', // Issue #173 observability logging, PR #208, the previous release
  '46b9969b26838a72dda48a6840d6e9bd4d9db458', // MVP-120C, PR #210: controlled synthetic partner lifecycle, running
];
const RUNNING = DEPLOYED[14];
const PREVIOUS = DEPLOYED[13];
const CAPABILITY = DEPLOYED[9];
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
  assert.match(header, /as of 2026-11-20/u);
  assert.match(header, /PR #199, TRUTH-003/u);
  assert.match(
    header,
    new RegExp(`latest capability release before MVP-097A is \`${CAPABILITY}\``, 'u'),
  );
  assert.match(header, /PR #195, MVP-096/u);
  assert.match(header, /PR #193, TRUTH-002/u);
  assert.match(header, /Issue #198 is closed\); migration V50 is applied/u);
  assert.match(header, /PR #191, MVP-095C/u);
  // The payroll release train (094A, 094B, 094C) is merged, deployed and accepted, from Git.
  assert.match(header, /PR #178, merge commit `dcd4d7e24c6a0c2a3af68a481a576d5eb3cc5430`/u);
  assert.match(header, /PR #180, merge commit `995a6937c1d22aeef3ab0023856195a1adcae4e2`/u);
  assert.match(header, /migrations V44, V45 and V46 are applied; Issue #181 is closed/u);
  assert.match(header, /`CD-SALARY-PREVIEW` is still DRAFT and inactive/u);
  assert.match(header, /migration V47 is applied/u);
  assert.match(header, /Issue #183 is closed/u);
  assert.match(header, /migration V48 is applied/u);
  assert.match(header, /Issue #187 is closed/u);
  // MVP-095C (Issue #190, PR #191): merged, deployed and accepted; V49 applied; synthetic provider only.
  assert.match(header, /migration V49 is applied/u);
  assert.match(header, /Issue #190 is closed/u);
  assert.match(
    header,
    /MVP-095C \(Issue #190, PR #191, migration V49\) is merged, deployed and accepted/u,
  );
  assert.match(header, /no real bank or mobile-money operator is connected, no money moves/u);
  assert.match(header, /Issue #173 .*is still open/u);
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

test('MVP-093A and the payroll train (094A, 094B, 094C) are merged and deployed; MVP-095A, MVP-095B and MVP-095C are deployed and accepted', () => {
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
  assert.match(
    reconciliation.body,
    /Issue #190\. Merged \(PR #191, merge commit `e8759cc5ca71cb8a54dc22b97aa9b6760fa1969f`\), deployed to hr-dev and accepted; Issue #190 is closed/u,
  );
  assert.match(reconciliation.body, /SYNTHETIC-TEST/u);
  assert.doesNotMatch(
    reconciliation.body.split('\n').slice(0, 4).join('\n'),
    /In progress|not deployed/u,
  );
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
  // TRUTH-002: Issue #173 stays listed as open, described as the intermittent five-second failure, never as fixed.
  assert.match(
    tail,
    /Issue #173 \(open\): intermittent five-second invitation-accept identity-provider failure/u,
  );
  assert.match(tail, /The cause is not proven and the failure is not fixed/u);
  assert.doesNotMatch(tail, /#173[^\n]*(is fixed|was fixed|resolved)/iu);
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

/**
 * Rule 1: the release the header says hr-dev runs equals the expected one, the migration it names is the expected
 * one, MVP-095C is stated merged, deployed and accepted, and the evidence table agrees.
 */
export function releaseClaimProblems(text, expected, migration = 'V54') {
  const header = text.split('## Epic 1')[0];
  const problems = [];
  const running = /runs `main` at `([0-9a-f]{40})`/u.exec(header)?.[1];
  if (running !== expected) problems.push(`header runs ${running}, expected ${expected}`);
  const applied = /runs `main` at `[0-9a-f]{40}`[^\n]*?\); migration (V\d+) is applied/u.exec(
    header,
  )?.[1];
  if (applied !== migration)
    problems.push(`header names migration ${applied}, expected ${migration}`);
  if (
    !/MVP-095C \(Issue #190, PR #191, migration V49\) is merged, deployed and accepted/u.test(
      header,
    )
  )
    problems.push('header does not state MVP-095C as merged, deployed and accepted');
  if (
    !/MVP-096 \(Issue #194, PR #195, migration V50\) is merged, deployed and accepted on hr-dev on 2026-11-14/u.test(
      header,
    )
  )
    problems.push('header does not state MVP-096 as merged, deployed and accepted');
  if (!/previous release, `[0-9a-f]{40}`[^\n]*?is preserved as `ce871f48e7be`/u.test(header))
    problems.push('header does not preserve the previous release ce871f48e7be');
  if (/MVP-095C[^.]*(in progress|not merged|not deployed)/iu.test(header))
    problems.push('header still says MVP-095C is in progress or not deployed');
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
  const accepted = /deployed to hr-dev and accepted/u.test(opening);
  const notDeployed = /not deployed|not merged|In progress/u.test(opening);
  if (claimedActive && (!accepted || notDeployed))
    return ['MVP-095C is claimed active without a deployed-and-accepted entry'];
  if (/\bComplete\b|\bdone\b/iu.test(opening.replace(/not deployed/gu, '')) && notDeployed)
    return ['MVP-095C is marked done while its entry says not deployed'];
  return [];
}

/**
 * Rule 4 (TRUTH-002): what the landing page, role homes and roadmap say about reconciliation, per locale. Pure over the
 * parsed locale file and the two source texts, so every rule is proved to fail on a stale fixture.
 */
const LEAVES = (value, path = []) =>
  typeof value === 'string'
    ? [[path.join('.'), value]]
    : Object.entries(value ?? {}).flatMap(([key, child]) => LEAVES(child, [...path, key]));
const MENTIONS = /reconcil|rapproch/iu;
const NOT_ACTIVE =
  /in progress|not (yet )?active|en cours|pas encore actif|n’est pas encore actif/iu;
const REQUIRED_WORDING = {
  en: [
    /Reconciliation of an uncertain submission is available for the controlled synthetic test provider only/u,
    /never resends the payment/u,
    /not payment or settlement/u,
    /No real provider is connected and real disbursement is not available yet/u,
    /not a real bank or mobile-money integration and no money moves/u,
  ],
  fr: [
    /Le rapprochement d’une soumission incertaine est disponible uniquement pour le prestataire de test synthétique contrôlé/u,
    /ne renvoie jamais le paiement/u,
    /ni un paiement ni un règlement/u,
    /Aucun prestataire réel n’est connecté et le versement réel n’est pas encore disponible/u,
    /pas une intégration bancaire ou de mobile money réelle et aucun argent n’est transféré/u,
  ],
};
const REAL_CLAIMS = {
  en: [
    /(connected|integrated) (to|with) (a |an )?(real|live) (bank|provider|operator)/iu,
    /\b(sends?|moves?|transfers?|pays?) real money/iu,
    /(?<!\bno )real (bank|provider|operator)s? (is|are) connected/iu,
  ],
  fr: [
    /(connecté|intégré) (à|avec) (une |un )?(vraie|vrai|réelle|réel) (banque|prestataire|opérateur)/iu,
    /(envoie|transfère|verse) de l’argent réel/iu,
    /(banque|prestataire|opérateur)s? réel(le)?s? (est|sont) connecté/iu,
  ],
};
export function localeTruthProblems(strings, locale, catalogueText, roadmapText) {
  const problems = [];
  const leaves = LEAVES(strings);
  for (const [path, value] of leaves) {
    if (MENTIONS.test(value) && NOT_ACTIVE.test(value))
      problems.push(`${locale}: ${path} says reconciliation is in progress or not active`);
  }
  const payroll = strings.landing?.modules?.items?.payroll?.description ?? '';
  for (const pattern of REQUIRED_WORDING[locale])
    if (!pattern.test(payroll)) problems.push(`${locale}: the payroll card lacks ${pattern}`);
  for (const [path, value] of leaves) {
    if (!path.startsWith('landing.') && !path.startsWith('home.') && !path.startsWith('roadmap.'))
      continue;
    for (const pattern of REAL_CLAIMS[locale])
      if (pattern.test(value))
        problems.push(
          `${locale}: ${path} claims a real bank or provider integration or real money`,
        );
  }
  // "Coming next" surfaces: the roadmap tiles and the landing list never carry reconciliation again.
  for (const [path, value] of leaves) {
    if (
      (path.startsWith('roadmap.items.') || path.startsWith('landing.modules.next.')) &&
      MENTIONS.test(value)
    )
      problems.push(`${locale}: ${path} lists reconciliation under "Coming next"`);
  }
  if (!strings.landing?.modules?.now?.paymentSubmission)
    problems.push(`${locale}: submission is not listed as available now`);
  if (MENTIONS.test(Object.values(strings.landing?.modules?.now ?? {}).join(' ')))
    problems.push(
      `${locale}: the plain "Available now" list names reconciliation (it belongs on the payroll card)`,
    );
  if (/'reconciliation'/u.test(catalogueText))
    problems.push('the catalogue lists a reconciliation id');
  if (/id: 'reconciliation'/u.test(roadmapText))
    problems.push('the roadmap lists a reconciliation tile');
  if (/in progress and not active until deployed/u.test(`${catalogueText}${roadmapText}`))
    problems.push('a source comment still says reconciliation is in progress');
  return problems;
}

/** The capabilities the landing page and homes list as active in the payment train. */
const ACTIVE_STORIES = ['MVP-095A', 'MVP-095B', 'MVP-095C', 'MVP-096'];

test('truth rule 1: the stated release, migration and MVP-095C status equal the deployed ones, and a stale header fails', () => {
  assert.deepEqual(releaseClaimProblems(backlog, RUNNING), []);
  const stale = backlog.replace(
    `runs \`main\` at \`${RUNNING}\``,
    `runs \`main\` at \`${PREVIOUS}\``,
  );
  assert.notEqual(stale, backlog);
  assert.ok(releaseClaimProblems(stale, RUNNING).length > 0, 'a stale header SHA fails');
  const wrongMigration = backlog.replace(
    'Issue #206 is closed); migration V54 is applied',
    'Issue #206 is closed); migration V53 is applied',
  );
  assert.notEqual(wrongMigration, backlog);
  assert.ok(
    releaseClaimProblems(wrongMigration, RUNNING).some((problem) => /migration/u.test(problem)),
    'a wrong migration fails',
  );
  const inProgress = backlog.replace(
    'is merged, deployed and accepted. It works only with the controlled SYNTHETIC-TEST provider',
    'is in progress: not merged, not deployed. It works only with the controlled SYNTHETIC-TEST provider',
  );
  assert.notEqual(inProgress, backlog);
  assert.ok(releaseClaimProblems(inProgress, RUNNING).length > 0, 'an in-progress MVP-095C fails');
  const staleTable = backlog.replace(/^\| MVP-120C .*$/mu, '');
  assert.notEqual(staleTable, backlog);
  assert.ok(
    releaseClaimProblems(staleTable, RUNNING).length > 0,
    'a missing latest evidence row fails',
  );
  assert.ok(SHA_PATTERN.test(RUNNING));
});

test('truth rule 2: every capability claimed active has a deployed-and-accepted backlog entry', () => {
  assert.deepEqual(capabilityClaimProblems(backlog, ACTIVE_STORIES), []);
  const stale = backlog.replace(
    'merge commit `b60300f998b5f6cf6bc380a93241be7e93fb4bb7`), deployed to hr-dev and accepted',
    'merge commit `b60300f998b5f6cf6bc380a93241be7e93fb4bb7`), merged',
  );
  assert.notEqual(stale, backlog);
  assert.ok(capabilityClaimProblems(stale, ACTIVE_STORIES).length > 0, 'a stale entry fails');
  const staleReconciliation = backlog.replace(
    '`e8759cc5ca71cb8a54dc22b97aa9b6760fa1969f`), deployed to hr-dev and accepted; Issue #190 is closed',
    '`e8759cc5ca71cb8a54dc22b97aa9b6760fa1969f`), merged; Issue #190 is closed',
  );
  assert.notEqual(staleReconciliation, backlog);
  assert.ok(
    capabilityClaimProblems(staleReconciliation, ACTIVE_STORIES).length > 0,
    'a stale MVP-095C entry fails',
  );
  const staleJournal = backlog.replace(
    'deployed to hr-dev and accepted on 2026-11-14; Issue #194 is closed',
    'merged; Issue #194 is open',
  );
  assert.notEqual(staleJournal, backlog);
  assert.ok(
    capabilityClaimProblems(staleJournal, ACTIVE_STORIES).length > 0,
    'a stale MVP-096 entry fails',
  );
  assert.ok(capabilityClaimProblems(backlog, ['MVP-999']).length > 0, 'an unknown story fails');
});

test('truth rule 3: MVP-095C is active only with a deployed-and-accepted entry, and cannot go back to in progress', () => {
  assert.deepEqual(reconciliationClaimProblems(backlog, true), []);
  assert.deepEqual(reconciliationClaimProblems(backlog, false), []);
  const inProgress = backlog.replace(
    '**Issue #190. Merged (PR #191, merge commit `e8759cc5ca71cb8a54dc22b97aa9b6760fa1969f`), deployed to hr-dev and accepted; Issue #190 is closed.**',
    '**Issue #190. In progress (not merged, not deployed).**',
  );
  assert.notEqual(inProgress, backlog);
  assert.ok(reconciliationClaimProblems(inProgress, true).length > 0, 'claiming it active fails');
  const done = backlog.replace(
    '**Issue #190. Merged (PR #191, merge commit `e8759cc5ca71cb8a54dc22b97aa9b6760fa1969f`), deployed to hr-dev and accepted; Issue #190 is closed.**',
    '**Issue #190. Complete (not deployed).**',
  );
  assert.notEqual(done, backlog);
  assert.ok(
    reconciliationClaimProblems(done, false).length > 0,
    'marking it done while undeployed fails',
  );
});

test('truth rule 4: both locales state reconciliation as active for the synthetic provider only (TRUTH-002)', () => {
  const read = (path) => readFileSync(`${root}${path}`, 'utf8');
  const catalogue = read('apps/web/src/features/landing/catalogue.ts');
  const roadmap = read('apps/web/src/features/home/roadmap.ts');
  assert.match(catalogue, /'paymentSubmission'/u);
  assert.doesNotMatch(catalogue, /'reconciliation'/u);
  for (const locale of ['en', 'fr']) {
    const strings = JSON.parse(read(`packages/localization/locales/${locale}/common.json`));
    assert.deepEqual(
      localeTruthProblems(strings, locale, catalogue, roadmap),
      [],
      `${locale} truth`,
    );
    // Each rule fails on a stale fixture.
    const clone = () => structuredClone(strings);
    const payrollWord =
      locale === 'en'
        ? 'Reconciliation of an uncertain submission is available'
        : 'Le rapprochement d’une soumission incertaine est disponible';
    const stale = clone();
    stale.landing.modules.items.payroll.description =
      stale.landing.modules.items.payroll.description.replace(
        payrollWord,
        locale === 'en'
          ? 'Reconciliation of uncertain submissions is in progress and not yet active. Available'
          : 'Le rapprochement est en cours et n’est pas encore actif. Disponible',
      );
    assert.ok(
      localeTruthProblems(stale, locale, catalogue, roadmap).length > 0,
      `${locale}: an in-progress statement fails`,
    );
    const next = clone();
    next.landing.modules.next.disbursement +=
      locale === 'en'
        ? '; reconciliation of uncertain submissions'
        : ' ; rapprochement des soumissions incertaines';
    assert.ok(
      localeTruthProblems(next, locale, catalogue, roadmap).some((p) => /Coming next/u.test(p)),
      `${locale}: reconciliation back in "Coming next" fails`,
    );
    const tile = clone();
    tile.roadmap.items.disbursement.title +=
      locale === 'en' ? ' and reconciliation' : ' et rapprochement';
    assert.ok(
      localeTruthProblems(tile, locale, catalogue, roadmap).some((p) => /Coming next/u.test(p)),
      `${locale}: a reconciliation roadmap tile fails`,
    );
    const real = clone();
    real.landing.modules.items.payroll.description +=
      locale === 'en'
        ? ' DivalHR is connected to a real bank.'
        : ' DivalHR est connecté à une vraie banque.';
    assert.ok(
      localeTruthProblems(real, locale, catalogue, roadmap).some((p) => /real bank/u.test(p)),
      `${locale}: a real-integration claim fails`,
    );
    const money = clone();
    money.landing.modules.items.payroll.description +=
      locale === 'en' ? ' DivalHR sends real money.' : ' DivalHR envoie de l’argent réel.';
    assert.ok(
      localeTruthProblems(money, locale, catalogue, roadmap).length > 0,
      `${locale}: a real-money claim fails`,
    );
    const lost = clone();
    lost.landing.modules.items.payroll.description =
      lost.landing.modules.items.payroll.description.replace(
        locale === 'en' ? 'No real provider is connected' : 'Aucun prestataire réel n’est connecté',
        '',
      );
    assert.ok(
      localeTruthProblems(lost, locale, catalogue, roadmap).length > 0,
      `${locale}: losing the no-real-provider wording fails`,
    );
    const now = clone();
    now.landing.modules.now.paymentSubmission +=
      locale === 'en' ? ' and reconciliation' : ' et rapprochement';
    assert.ok(
      localeTruthProblems(now, locale, catalogue, roadmap).length > 0,
      `${locale}: reconciliation in the plain list fails`,
    );
  }
  assert.ok(
    localeTruthProblems(
      JSON.parse(read('packages/localization/locales/en/common.json')),
      'en',
      "ids: 'reconciliation'",
      roadmap,
    ).length > 0,
    'a catalogue id fails',
  );
  assert.ok(
    localeTruthProblems(
      JSON.parse(read('packages/localization/locales/en/common.json')),
      'en',
      catalogue,
      "{ id: 'reconciliation', icon: 'bank' }",
    ).length > 0,
    'a roadmap tile fails',
  );
});

/**
 * Rule 5 (TRUTH-003, Issue #198): MVP-096, the accounting journal export, is active, and every surface keeps its limits.
 * Pure over text and parsed locale JSON so each guard is proved to fail on a stale fixture.
 */
const JOURNAL_MENTION = {
  en: /accounting[- ]ready journal|accounting journal|journal export|balanced journal|journals? (is|are) generated/iu,
  fr: /journal comptable|export (de|du) journal|journal immuable|journaux sont générés/iu,
};
const NOT_AVAILABLE_JOURNAL = {
  en: /being built|not (yet )?available|not (yet )?active|\bplanned\b|coming (next|soon)|under construction/iu,
  fr: /en préparation|en cours de (construction|développement)|pas encore (disponible|actif)|n’est pas encore (disponible|actif)|\bprévu|prochainement|à venir/iu,
};
const NEGATION = {
  en: /\b(not|no|never|without|nor|nothing)\b/iu,
  fr: /\b(pas|aucun|aucune|jamais|rien|ni|sans)\b|\bn’/iu,
};
const FORBIDDEN_CLAIMS = {
  en: [
    /(?<!not )(?<!nothing is )(?<!never )posted (directly )?(to|into|in) (Sage|Odoo|QuickBooks|an? accounting|any accounting)/iu,
    /\b(posts?|pushes|sends?) (the )?(journals?|entries) (directly )?(to|into) (Sage|Odoo|QuickBooks)/iu,
    /direct (accounting )?posting (is )?(available|active|now)/iu,
    /\b(USD|multi-currency) journals? (is|are) (generated|supported|available|active)/iu,
    /(cost[- ]cent(er|re)|site) (allocation|dimension)s? (is|are) (supported|available|active)/iu,
    /(real|live) (bank|mobile-money) (payment|disbursement|connection) (is|are) (available|active)/iu,
    /(paid status|settlement|salary advances?|loans?|employee payment notifications?) (is|are) (available|active|supported)/iu,
  ],
  fr: [
    /(?<!pas )(?<!jamais )(?<!rien n’est )comptabilis[ée]s? (directement )?(dans|vers) (Sage|Odoo|QuickBooks|un système comptable)/iu,
    /comptabilisation directe (est )?(disponible|active)/iu,
    /journaux? (en|de) USD (est|sont) (généré|pris en charge|disponible|actif)/iu,
    /(centre de coûts|site)[^.]*(répartition|dimension)s? (est|sont) (prise? en charge|disponible|active?)/iu,
    /(paiement|versement|connexion) (bancaire|mobile money) réel(le)? (est|sont) (disponible|active?)/iu,
    /(statut payé|règlement|avances sur salaire|prêts|notification de paiement aux employés) (est|sont) (disponible|actif|actifs|pris en charge)/iu,
  ],
};
/** Sentences of a string; a claim and its limit are judged within one sentence. */
const SENTENCES = (value) => value.split(/(?<=[.;:])\s+/u);
const LIMITED_TOPIC = {
  en: /paid status|settlement|proof of payment|payment notification|salary advance|\bloans?\b|cost[- ]cent(er|re)|site (dimension|allocation)|\bUSD\b/iu,
  fr: /statut payé|règlement|preuve de paiement|notification de paiement|avance sur salaire|avances sur salaire|\bprêts\b|\bprêt(?! pour)\b|centre de coûts|dimension de[^.]*site|\bUSD\b/iu,
};
const SURFACES = {
  payrollDescription: 'landing.modules.items.payroll.description',
  payrollBody: 'landing.payroll.body',
  nowExport: 'landing.modules.now.accountingExport',
  homeCard: 'home.tenant.cards.accountingMappings.description',
  homeProgress: 'home.tenant.progress.items.accountingJournal.description',
  homePayrollCard: 'home.tenant.cards.payrollPackages.description',
  roadmap: 'roadmap.items.filingAccounting.description',
  nextPayslips: 'landing.modules.next.payslips',
};
const REQUIRED_JOURNAL = {
  en: {
    payrollDescription: [
      /effective-dated account mappings/u,
      /immutable, balanced accounting journal from an approved payroll package/u,
      /deterministic CSV and manifest downloads/u,
      /manifest binds the CSV SHA-256/u,
      /accounting-ready manual export for CDF packages/u,
      /not posted to Sage, Odoo, QuickBooks or any accounting system/u,
      /not proof of payment/u,
      /No journal is produced for USD/u,
      /no cost-center or site dimension is claimed/u,
    ],
    payrollBody: [
      /accounting-ready journal export \(CSV and manifest, manual import only\) is available for approved CDF payroll packages/u,
      /Filing, direct accounting posting,[^.]*salary advances come next/u,
    ],
    nowExport: [
      /immutable, balanced journal/u,
      /CSV and manifest downloads/u,
      /nothing is posted to an accounting system/u,
      /CDF/u,
    ],
    homeCard: [/effective-dated/u, /never posted to an accounting system/u],
    homeProgress: [
      /immutable, balanced journal/u,
      /CSV and manifest/u,
      /CDF packages/u,
      /not posted to any accounting system/u,
      /not proof of payment/u,
    ],
    homePayrollCard: [
      /accounting-ready journal export/u,
      /manual import only/u,
      /nothing is posted to an accounting system/u,
    ],
    roadmap: [/direct posting to accounting systems/u, /not a posting/u],
    nextPayslips: [/direct accounting posting/u],
  },
  fr: {
    payrollDescription: [
      /correspondances de comptes datées/u,
      /journal comptable immuable et équilibré à partir d’un dossier de paie approuvé/u,
      /téléchargements déterministes du CSV et du manifeste/u,
      /manifeste liant l’empreinte SHA-256 du CSV/u,
      /export manuel prêt pour la comptabilité, pour les dossiers en CDF/u,
      /pas comptabilisé dans Sage, Odoo, QuickBooks ni dans aucun système comptable/u,
      /pas une preuve de paiement/u,
      /Aucun journal n’est produit pour l’USD/u,
      /aucune dimension de centre de coûts ou de site n’est revendiquée/u,
    ],
    payrollBody: [
      /export de journal comptable prêt pour la comptabilité \(CSV et manifeste, import manuel uniquement\) est disponible pour les dossiers de paie approuvés en CDF/u,
      /déclarations, la comptabilisation directe[^.]*avances sur salaire arrivent prochainement/u,
    ],
    nowExport: [
      /journal comptable immuable et équilibré/u,
      /CSV et du manifeste/u,
      /rien n’est comptabilisé dans un système comptable/u,
      /CDF/u,
    ],
    homeCard: [/datés et versionnés/u, /jamais comptabilisés dans un système comptable/u],
    homeProgress: [
      /journal immuable et équilibré/u,
      /CSV et son manifeste/u,
      /dossiers en CDF/u,
      /rien n’est comptabilisé dans un système comptable/u,
      /pas une preuve de paiement/u,
    ],
    homePayrollCard: [
      /export de journal comptable prêt pour la comptabilité/u,
      /import manuel uniquement/u,
      /rien n’est comptabilisé dans un système comptable/u,
    ],
    roadmap: [/comptabilisation directe/u, /pas une comptabilisation/u],
    nextPayslips: [/comptabilisation directe/u],
  },
};
const dig = (strings, path) => path.split('.').reduce((node, key) => node?.[key], strings);

/** One locale: MVP-096 is active, every limit is kept, nothing overclaims. */
export function journalLocaleProblems(strings, locale) {
  const problems = [];
  for (const [surface, path] of Object.entries(SURFACES)) {
    const value = dig(strings, path);
    if (typeof value !== 'string') {
      problems.push(`${locale}: ${path} is missing`);
      continue;
    }
    for (const pattern of REQUIRED_JOURNAL[locale][surface])
      if (!pattern.test(value)) problems.push(`${locale}: ${path} lacks ${pattern}`);
  }
  for (const [path, value] of LEAVES(strings)) {
    if (!/^(landing|home|roadmap)\./u.test(path)) continue;
    for (const sentence of SENTENCES(value)) {
      if (JOURNAL_MENTION[locale].test(sentence) && NOT_AVAILABLE_JOURNAL[locale].test(sentence))
        problems.push(`${locale}: ${path} describes the accounting journal export as not active`);
      if (
        JOURNAL_MENTION[locale].test(sentence) &&
        LIMITED_TOPIC[locale].test(sentence) &&
        !NEGATION[locale].test(sentence)
      )
        problems.push(`${locale}: ${path} ties the journal export to an unlimited claim`);
    }
    for (const pattern of FORBIDDEN_CLAIMS[locale])
      if (pattern.test(value)) problems.push(`${locale}: ${path} overclaims (${pattern})`);
  }
  // "Available now" lists the export; "Coming next" never lists the journal export.
  for (const [path, value] of LEAVES(strings)) {
    if (
      (path.startsWith('roadmap.items.') || path.startsWith('landing.modules.next.')) &&
      JOURNAL_MENTION[locale].test(
        value.replace(/[^.]*\b(not a posting|pas une comptabilisation)[^.]*\./u, ''),
      )
    )
      problems.push(`${locale}: ${path} lists the journal export under "Coming next"`);
  }
  return problems;
}

/** English and French carry the same claims on the same surfaces. */
export function journalParityProblems(en, fr) {
  const problems = [];
  const keys = (strings, path) => Object.keys(dig(strings, path) ?? {}).sort();
  for (const path of [
    'landing.modules.now',
    'landing.modules.next',
    'home.tenant.cards',
    'home.tenant.progress.items',
    'roadmap.items',
  ])
    if (keys(en, path).join() !== keys(fr, path).join())
      problems.push(`${path}: English and French keys diverge`);
  for (const [surface, path] of Object.entries(SURFACES)) {
    const required = [REQUIRED_JOURNAL.en[surface].length, REQUIRED_JOURNAL.fr[surface].length];
    if (required[0] !== required[1]) problems.push(`${surface}: required claims diverge`);
    if (typeof dig(en, path) !== typeof dig(fr, path))
      problems.push(`${path}: one locale lacks it`);
  }
  const sha = (strings) => /SHA-256/u.test(dig(strings, SURFACES.payrollDescription) ?? '');
  if (sha(en) !== sha(fr))
    problems.push('the SHA-256 manifest binding is stated in one locale only');
  return problems;
}

/** The backlog records MVP-096 as merged, deployed and accepted, with its limits, and never as planned or unavailable. */
export function journalBacklogProblems(text) {
  const problems = [];
  const header = text.split('## Epic 1')[0];
  const section = new Map(sectionsOf(text).map((entry) => [entry.id, entry.body])).get('MVP-096');
  if (!section) return ['MVP-096 has no backlog entry'];
  const opening = section.split('\n').slice(0, 4).join('\n');
  const need = (label, haystack, pattern) => {
    if (!pattern.test(haystack)) problems.push(`${label} lacks ${pattern}`);
  };
  need(
    'MVP-096 entry',
    opening,
    /Merged \(PR #195, merge commit `c368d58b15e1aeddb755997728154a497348c07c`\)/u,
  );
  need('MVP-096 entry', opening, /deployed to hr-dev and accepted on 2026-11-14/u);
  need('MVP-096 entry', opening, /Issue #194/u);
  need('MVP-096 entry', opening, /Migration V50 is applied/u);
  need(
    'MVP-096 entry',
    opening,
    /hr-dev ran `c368d58b…` when it was accepted, then ran the presentation release `55b130f5…` \(TRUTH-003\), then `4b947a6c…` \(MVP-120A\), then `769fa601…` \(PR #208, which contains MVP-120B\), and now runs the release `46b9969b…`/u,
  );
  need('MVP-096 entry', opening, /previous release `ce871f48e7be` is preserved/u);
  for (const [label, haystack] of [
    ['MVP-096 entry', section],
    ['header', header],
  ]) {
    if (
      /being built|not available yet|in progress|\bplanned\b|not merged|not deployed/iu.test(
        haystack.replace(/MVP-095[A-C][^\n]*/gu, ''),
      )
    )
      problems.push(`${label} still describes MVP-096 as planned, being built or unavailable`);
    for (const pattern of FORBIDDEN_CLAIMS.en)
      if (pattern.test(haystack)) problems.push(`${label} overclaims (${pattern})`);
  }
  for (const pattern of [
    /not posted to Sage, Odoo, QuickBooks or any accounting system/u,
    /not proof of payment/u,
    /no real bank or mobile-money connection exists/u,
    /no settlement or paid status/u,
    /no employee payment notification/u,
    /no loans or salary advances/u,
    /real DRC statutory pack stays DRAFT/u,
    /SYNTHETIC-TEST only and no money moves/u,
    /USD journal generation is not claimed while payroll packages are CDF-only/u,
    /cost-center and site dimensions are not claimed while frozen payroll lines do not carry them/u,
    /manifest binds the CSV SHA-256/u,
    /effective-dated account mappings/u,
  ])
    need('header', header, pattern);
  need('MVP-096 entry', section, /DivalHR never posts to an accounting system/u);
  need(
    'MVP-096 entry',
    section,
    /Not included:[^\n]*direct accounting-system posting[^\n]*settlement or paid status[^\n]*employee notifications[^\n]*loans and salary advances/u,
  );
  return problems;
}

test('truth rule 5: the backlog records MVP-096 as deployed and accepted, with its limits (TRUTH-003)', () => {
  assert.deepEqual(journalBacklogProblems(backlog), []);
  const fixtures = [
    [
      'header SHA',
      (t) =>
        t.replace(
          `runs \`main\` at \`${RUNNING}\``,
          'runs `main` at `c368d58b15e1aeddb755997728154a497348c07c`',
        ),
      null,
    ],
    [
      'entry deployed',
      (t) =>
        t.replace(
          'deployed to hr-dev and accepted on 2026-11-14; Issue #194 is closed',
          'in review',
        ),
      /deployed to hr-dev and accepted/,
    ],
    [
      'entry planned',
      (t) =>
        t.replace(
          '### MVP-096 Accounting journal export\n',
          '### MVP-096 Accounting journal export\n\nThe journal export is being built.\n',
        ),
      /being built/,
    ],
    [
      'entry V50',
      (t) => t.replace('Migration V50 is applied;', 'Migration V50 is pending;'),
      /V50/,
    ],
    [
      'previous release',
      (t) => t.replace('previous release `ce871f48e7be` is preserved', 'previous release is gone'),
      /previous release/,
    ],
    [
      'posting claim',
      (t) =>
        t.replace(
          'not posted to Sage, Odoo, QuickBooks or any accounting system',
          'posted to Sage',
        ),
      /posted to Sage|overclaims/,
    ],
    [
      'USD limit',
      (t) =>
        t.replace(
          'USD journal generation is not claimed while payroll packages are CDF-only',
          'USD journals are available',
        ),
      /USD/,
    ],
    [
      'site limit',
      (t) =>
        t.replace(
          'cost-center and site dimensions are not claimed while frozen payroll lines do not carry them',
          'cost-center allocation is available',
        ),
      /cost-center/,
    ],
    [
      'paid status',
      (t) => t.replace('no settlement or paid status', 'paid status is available'),
      /paid status|settlement/,
    ],
    [
      'salary advances',
      (t) => t.replace('no loans or salary advances', 'salary advances are available'),
      /salary advances|loans/,
    ],
  ];
  for (const [name, mutate, expected] of fixtures) {
    const stale = mutate(backlog);
    assert.notEqual(stale, backlog, `${name}: the fixture changed the backlog`);
    const found = journalBacklogProblems(stale);
    const release = releaseClaimProblems(stale, RUNNING);
    assert.ok(found.length > 0 || release.length > 0, `${name}: a stale backlog fails`);
    if (expected)
      assert.ok(
        found.some((p) => expected.test(p)),
        `${name}: names the problem`,
      );
  }
  const noTableRow = backlog.replace(/^\| MVP-120C .*$/mu, '');
  assert.ok(releaseClaimProblems(noTableRow, RUNNING).length > 0, 'a missing release row fails');
  const wrongPr = backlog.replace('| MVP-096   | #195 |', '| MVP-096   | #196 |');
  assert.notEqual(wrongPr, backlog);
});

test('truth rule 5: both locales mark the journal export active, keep every limit and stay in step (TRUTH-003)', () => {
  const read = (path) => readFileSync(`${root}${path}`, 'utf8');
  const en = JSON.parse(read('packages/localization/locales/en/common.json'));
  const fr = JSON.parse(read('packages/localization/locales/fr/common.json'));
  assert.deepEqual(journalLocaleProblems(en, 'en'), [], 'en truth');
  assert.deepEqual(journalLocaleProblems(fr, 'fr'), [], 'fr truth');
  assert.deepEqual(journalParityProblems(en, fr), [], 'en/fr parity');
  const catalogue = read('apps/web/src/features/landing/catalogue.ts');
  const roadmap = read('apps/web/src/features/home/roadmap.ts');
  const home = read('apps/web/src/features/home/HomePage.tsx');
  assert.match(catalogue, /AVAILABLE_NOW[^\]]*'accountingExport'/su);
  assert.doesNotMatch(roadmap, /id: 'accounting(Journal|Export)'/u);
  assert.match(home, /'accountingMappings',\s*'\/admin\/accounting-mappings'/u);
  for (const [locale, strings] of [
    ['en', en],
    ['fr', fr],
  ]) {
    const clone = () => structuredClone(strings);
    const edit = (path, change) => {
      const copy = clone();
      const parts = path.split('.');
      const last = parts.pop();
      const parent = parts.reduce((node, key) => node[key], copy);
      parent[last] = change(parent[last]);
      return copy;
    };
    const failing = (name, copy, expected) => {
      const found = journalLocaleProblems(copy, locale);
      assert.ok(found.length > 0, `${locale}: ${name} fails`);
      if (expected)
        assert.ok(
          found.some((p) => expected.test(p)),
          `${locale}: ${name} names the problem`,
        );
    };
    const en_ = locale === 'en';
    failing(
      '"being built" on the payroll card',
      edit(
        SURFACES.payrollDescription,
        (v) =>
          v +
          (en_
            ? ' An accounting journal export is being built.'
            : ' Un export de journal comptable est en préparation.'),
      ),
      /not active/u,
    );
    failing(
      '"not available yet" on the home progress item',
      edit(
        SURFACES.homeProgress,
        (v) =>
          v +
          (en_
            ? ' The accounting journal export is not available yet.'
            : ' L’export du journal comptable n’est pas encore disponible.'),
      ),
      /not active/u,
    );
    failing(
      'journal back under "Coming next"',
      edit(
        'landing.modules.next.disbursement',
        (v) => v + (en_ ? '; accounting journal export' : ' ; export de journal comptable'),
      ),
      /Coming next|not active|unlimited/u,
    );
    failing(
      'direct posting available',
      edit(
        SURFACES.nowExport,
        (v) =>
          v +
          (en_
            ? ' Direct accounting posting is available.'
            : ' La comptabilisation directe est disponible.'),
      ),
      /overclaims/u,
    );
    failing(
      'posted to Sage',
      edit(SURFACES.payrollDescription, (v) =>
        v.replace(
          en_ ? 'not posted to Sage' : 'pas comptabilisé dans Sage',
          en_ ? 'posted to Sage' : 'comptabilisé dans Sage',
        ),
      ),
      /overclaims|lacks/u,
    );
    failing(
      'USD journal claimed',
      edit(SURFACES.payrollDescription, (v) =>
        v.replace(
          en_ ? 'No journal is produced for USD' : 'Aucun journal n’est produit pour l’USD',
          en_ ? 'USD journals are generated' : 'Les journaux en USD sont générés',
        ),
      ),
      /overclaims|lacks/u,
    );
    failing(
      'cost-center claimed',
      edit(SURFACES.payrollDescription, (v) =>
        v.replace(
          en_
            ? 'no cost-center or site dimension is claimed'
            : 'aucune dimension de centre de coûts ou de site n’est revendiquée',
          en_
            ? 'cost-center allocation is available'
            : 'la répartition par centre de coûts est disponible',
        ),
      ),
      /overclaims|lacks/u,
    );
    failing(
      'proof of payment dropped',
      edit(SURFACES.homeProgress, (v) =>
        v.replace(en_ ? 'and not proof of payment' : 'et ce n’est pas une preuve de paiement', ''),
      ),
      /lacks/u,
    );
    failing(
      'settlement claimed with the journal',
      edit(
        SURFACES.homeCard,
        (v) =>
          v +
          (en_
            ? ' The journal export confirms settlement and paid status.'
            : ' L’export du journal confirme le règlement et le statut payé.'),
      ),
      /unlimited|overclaims/u,
    );
    failing(
      'SHA-256 binding dropped',
      edit(SURFACES.payrollDescription, (v) => v.replace('SHA-256', 'checksum')),
      /lacks/u,
    );
    failing(
      'export removed from "Available now"',
      (() => {
        const c = clone();
        delete c.landing.modules.now.accountingExport;
        return c;
      })(),
      /missing/u,
    );
    failing(
      'real payment claimed',
      edit(
        SURFACES.payrollBody,
        (v) =>
          v +
          (en_ ? ' Real bank payment is available.' : ' Le paiement bancaire réel est disponible.'),
      ),
      /overclaims/u,
    );
  }
  const lopsided = structuredClone(fr);
  delete lopsided.landing.modules.now.accountingExport;
  assert.ok(journalParityProblems(en, lopsided).length > 0, 'a one-sided claim fails');
  const extra = structuredClone(en);
  extra.home.tenant.cards.accountingExtra = { title: 'x', description: 'y' };
  assert.ok(journalParityProblems(extra, fr).length > 0, 'diverging keys fail');
  assert.ok(journalLocaleProblems(en, 'en').length === 0);
  assert.ok(!/accountingExport/u.test(roadmap), 'the roadmap does not list the export');
});

/**
 * MVP-120B (D120B-15): the backlog states what Git and the deployment prove about MVP-097A and
 * MVP-120A, describes MVP-120B only as in review with a controlled synthetic partner, and never
 * claims a real partner, WhatsApp, bank or payment connection.
 */
export function handoffBacklogProblems(text) {
  const problems = [];
  const sections = new Map(sectionsOf(text).map((entry) => [entry.id, entry.body]));
  const opening = (id) => (sections.get(id) ?? '').split('\n').slice(0, 4).join('\n');
  const need = (label, haystack, pattern) => {
    if (!pattern.test(haystack)) problems.push(`${label} lacks ${pattern}`);
  };
  need(
    'MVP-120A entry',
    opening('MVP-120A'),
    /Merged \(PR #205, merge commit `4b947a6cfa2135369cd2e8ac1e2710c84a2bb525`\)/u,
  );
  need(
    'MVP-120A entry',
    opening('MVP-120A'),
    /deployed to hr-dev and accepted on 2026-11-16; Issue #204 is closed/u,
  );
  need('MVP-120A entry', opening('MVP-120A'), /Migration V52 is applied/u);
  if (/not merged|not deployed|In review/u.test(opening('MVP-120A')))
    problems.push('MVP-120A is still described as in review or not deployed');
  need(
    'MVP-097A entry',
    opening('MVP-097A'),
    /Merged \(PR #201, merge commit `967c401af358dc9ce0517e094254955c5a3c00e4`/u,
  );
  need('MVP-097A entry', opening('MVP-097A'), /PR #202/u);
  if (/not merged|In review/u.test(opening('MVP-097A')))
    problems.push('MVP-097A is still described as in review or not merged');
  const handoff = sections.get('MVP-120B');
  if (!handoff) return [...problems, 'MVP-120B has no backlog entry'];
  const head = opening('MVP-120B');
  need(
    'MVP-120B entry',
    head,
    /Issue #206\. Merged \(PR #207, merge commit `d0f6dd1ed9c8c26a4d9055f9c225a12b30eef0b4`\) and deployed to hr-dev; Issue #206 is closed/u,
  );
  need('MVP-120B entry', head, /Migration V53 is applied/u);
  if (/In review|not merged|not deployed/u.test(head))
    problems.push('MVP-120B is still described as in review or not deployed');
  need('MVP-120B entry', handoff, /DivalHR is not the lender/u);
  need('MVP-120B entry', handoff, /every handoff is synthetic/u);
  need(
    'MVP-120B entry',
    handoff,
    /no real partner, lender, bank, bureau, WhatsApp number, endpoint or credential exists/u,
  );
  need('MVP-120B entry', handoff, /`CD-SALARY-PREVIEW` stays DRAFT/u);
  need('MVP-120B entry', handoff, /never resent automatically|never resend/u);
  need('MVP-120B entry', handoff, /the request stays `EMPLOYER_CONFIRMED`/u);
  if (/\b(approved loan|guaranteed|funded|interest rate|APR|credit score)\b/iu.test(handoff))
    problems.push('MVP-120B entry uses banned lending wording');
  // MVP-120C is merged, deployed and accepted (PR #210, V54): the facts of the truth refresh are pinned.
  const lifecycle = sections.get('MVP-120C');
  if (!lifecycle) return [...problems, 'MVP-120C has no backlog entry'];
  const lifecycleHead = opening('MVP-120C');
  need(
    'MVP-120C entry',
    lifecycleHead,
    /Issue #209\. Merged \(PR #210, merge commit `46b9969b26838a72dda48a6840d6e9bd4d9db458`\), deployed to hr-dev and accepted on 2026-11-20; Issue #209 is closed as completed/u,
  );
  need('MVP-120C entry', lifecycleHead, /Migration V54 is applied/u);
  if (/In review|not merged|not deployed|not applied/u.test(lifecycleHead))
    problems.push('MVP-120C is still described as in review or not deployed');
  need('MVP-120C entry', lifecycle, /synthetic partner/u);
  need('MVP-120C entry', lifecycle, /existing linked synthetic accounts, without invitations/u);
  need('MVP-120C entry', lifecycle, /existing 121 salary-advance rows/u);
  need('MVP-120C entry', lifecycle, /genuine `DELIVERY_UNKNOWN` evidence remained unchanged/u);
  need('MVP-120C entry', lifecycle, /retention preview and exact confirmed purge passed/u);
  need('MVP-120C entry', lifecycle, /30-day organization retention policy remains configured/u);
  need('MVP-120C entry', lifecycle, /deletion is manual only/u);
  need(
    'MVP-120C entry',
    lifecycle,
    /No real lender, credit decision, disbursement, repayment or payroll deduction exists/u,
  );
  const maintenance = text.split('## Open maintenance')[1] ?? '';
  need('open maintenance', maintenance, /Issue #211 \(open, nonblocking\)/u);
  need('open maintenance', maintenance, /Issue #212 \(open, nonblocking\)/u);
  return problems;
}

test('truth rule 6: 097A, 120A, 120B and 120C carry their Git and deployment facts, with a synthetic partner only (D120B-15, D120C)', () => {
  assert.deepEqual(handoffBacklogProblems(backlog), []);
  const fixtures = [
    [
      '120A in review',
      (t) =>
        t.replace(
          'deployed to hr-dev and accepted on 2026-11-16; Issue #204 is closed',
          'not deployed',
        ),
      /MVP-120A/u,
    ],
    [
      '120A migration',
      (t) => t.replace('Migration V52 is applied.', 'Migration V52 is pending.'),
      /V52/u,
    ],
    [
      '097A not merged',
      (t) => t.replace('Issue #200. Merged (PR #201', 'Issue #200. In review (PR #201'),
      /MVP-097A/u,
    ],
    [
      '120B in review',
      (t) => t.replace('Issue #206. Merged (PR #207', 'Issue #206. In review (PR #207'),
      /MVP-120B/u,
    ],
    [
      '120C in review',
      (t) => t.replace('Issue #209. Merged (PR #210', 'Issue #209. In review (PR #210'),
      /MVP-120C/u,
    ],
    [
      '120C V54 not applied',
      (t) => t.replace('Migration V54 is applied.', 'Migration V54 is pending.'),
      /V54/u,
    ],
    [
      '120C evidence rows changed',
      (t) => t.replaceAll('existing 121 salary-advance rows', 'existing salary-advance rows'),
      /121/u,
    ],
    [
      '120C deletion automatic',
      (t) => t.replaceAll('deletion is manual only', 'deletion is automatic'),
      /manual only/u,
    ],
    [
      '#211 closed',
      (t) => t.replace('Issue #211 (open, nonblocking)', 'Issue #211 (closed)'),
      /#211/u,
    ],
    [
      '#212 closed',
      (t) => t.replace('Issue #212 (open, nonblocking)', 'Issue #212 (closed)'),
      /#212/u,
    ],
    [
      '120B lender',
      (t) => t.replaceAll('DivalHR is not the lender', 'DivalHR is the lender'),
      /not the lender/u,
    ],
    [
      '120B real partner',
      (t) =>
        t.replace(
          'no real partner, lender, bank, bureau, WhatsApp number, endpoint or credential exists',
          'a real partner is connected',
        ),
      /real partner/u,
    ],
    ['120B drc pack', (t) => t.replace('stays DRAFT and untouched', 'is active'), /DRAFT/u],
    [
      '120B banned wording',
      (t) =>
        t.replace(
          'Not included:** a real partner,',
          'Not included:** guaranteed funded loans, a real partner,',
        ),
      /banned/u,
    ],
  ];
  for (const [name, mutate, expected] of fixtures) {
    const stale = mutate(backlog);
    assert.notEqual(stale, backlog, `${name}: the fixture changed the backlog`);
    assert.ok(
      handoffBacklogProblems(stale).some((problem) => expected.test(problem)),
      `${name}: names the problem`,
    );
  }
});
