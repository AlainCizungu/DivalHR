import { readFileSync } from 'node:fs';
import { Ajv2020 } from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
import { describe, expect, it } from 'vitest';
import { parse } from 'yaml';

/**
 * MVP-101 (ADR 0027): the closed Core to AI service contract and its evaluation set. The semantic
 * invariants (partition, fact values, severity bands) are asserted where they are enforced: the AI
 * service (pytest) and Core (JUnit). Here every fixture is proven against the JSON Schemas.
 */
const read = (relative: string) => readFileSync(new URL(relative, import.meta.url), 'utf8');
const json = (relative: string) => JSON.parse(read(relative));
const inputSchema = json('../schemas/operational-summary-input.schema.json');
const planSchema = json('../schemas/operational-summary-plan.schema.json');
const outputSchema = json('../schemas/operational-summary-output.schema.json');
const EVAL = '../evaluation/operational-summary/v1/';
const cases = json(`${EVAL}cases.json`).cases as {
  id: string;
  input: Record<string, unknown>;
  expected: { headlineCode: string; factIds: string[]; selectedTypes: string[] };
}[];
const invalid = json(`${EVAL}invalid-inputs.json`).entries as {
  id: string;
  layer: 'schema' | 'semantic';
  base: string;
  patch: { op: 'add' | 'replace' | 'remove'; path: string; value?: unknown }[];
}[];
const plans = json(`${EVAL}plans.json`).entries as {
  id: string;
  expect: string;
  plan: unknown;
}[];

function validator(schema: object) {
  const ajv = new Ajv2020({ allErrors: true, strict: true });
  addFormats.default(ajv);
  return ajv.compile(schema);
}

function patched(base: unknown, patch: (typeof invalid)[number]['patch']): unknown {
  const copy = structuredClone(base) as Record<string, unknown>;
  for (const step of patch) {
    const parts = step.path.split('/').slice(1);
    const last = parts.pop() as string;
    let node: any = copy;
    for (const part of parts) node = node[part];
    if (step.op === 'remove') {
      if (Array.isArray(node)) node.splice(Number(last), 1);
      else delete node[last];
    } else {
      node[last] = step.value;
    }
  }
  return copy;
}

describe('operational summary input contract', () => {
  const valid = validator(inputSchema);

  it('accepts every evaluation case in both languages', () => {
    expect(cases.length).toBeGreaterThanOrEqual(12);
    for (const testCase of cases) {
      for (const language of ['en', 'fr']) {
        expect(valid({ ...testCase.input, language }), `${testCase.id} ${language}`).toBe(true);
      }
    }
  });

  it('rejects every schema-layer invalid entry, including injection-looking strings', () => {
    const entries = invalid.filter((entry) => entry.layer === 'schema');
    expect(entries.length).toBeGreaterThanOrEqual(14);
    for (const entry of entries) {
      const base = cases.find((testCase) => testCase.id === entry.base);
      expect(base, entry.base).toBeDefined();
      expect(valid(patched(base?.input, entry.patch)), entry.id).toBe(false);
    }
  });

  it('keeps semantic entries schema-valid so the semantic layer is what rejects them', () => {
    for (const entry of invalid.filter((candidate) => candidate.layer === 'semantic')) {
      const base = cases.find((testCase) => testCase.id === entry.base);
      expect(valid(patched(base?.input, entry.patch)), entry.id).toBe(true);
    }
  });

  it('has no property that can carry free text', () => {
    const text: string[] = [];
    (function walk(node: any, path: string) {
      if (node && typeof node === 'object') {
        if (node.type === 'string' && !node.enum && !node.const && !node.pattern) text.push(path);
        for (const [key, value] of Object.entries(node)) walk(value, `${path}/${key}`);
      }
    })(inputSchema, '');
    expect(text).toEqual([]);
  });
});

describe('operational summary plan contract', () => {
  const valid = validator(planSchema);

  it('accepts the oracle of every case', () => {
    for (const testCase of cases) {
      expect(
        valid({ headlineCode: testCase.expected.headlineCode, factIds: testCase.expected.factIds }),
        testCase.id,
      ).toBe(true);
    }
  });

  it('accepts or rejects the candidate plans at the schema layer as classified', () => {
    for (const entry of plans) {
      const schemaRejects = [
        'duplicate-fact-id',
        'too-many-ids',
        'unknown-headline',
        'extra-property',
        'free-text-field',
        'no-fact-ids',
      ];
      expect(valid(entry.plan), entry.id).toBe(!schemaRejects.includes(entry.id));
    }
  });
});

describe('operational summary output contract', () => {
  const valid = validator(outputSchema);
  const base = {
    language: 'en',
    generationMode: 'FALLBACK',
    templateVersion: 'ops-summary-1',
    headline: {
      code: 'OPERATIONS_WITHIN_RANGE',
      text: 'Recorded operations are within the expected range.',
    },
    highlights: [
      {
        factId: 'f1',
        factType: 'SCHEDULED_SHIFTS',
        severity: 'NONE',
        routeId: 'SCHEDULES',
        text: 'No shifts are scheduled today in this scope.',
      },
    ],
    limitations: [
      { code: 'RECORDED_FACTS_ONLY', text: 'a' },
      { code: 'NO_CONCLUSION', text: 'b' },
      { code: 'REVIEW_SOURCES', text: 'c' },
    ],
  };

  it('accepts a complete briefing and rejects provider or model fields', () => {
    expect(valid(base)).toBe(true);
    expect(valid({ ...base, provider: 'x' })).toBe(false);
    expect(valid({ ...base, model: 'x' })).toBe(false);
    expect(valid({ ...base, generationMode: 'OPENAI' })).toBe(false);
  });

  it('allows at most five highlights and exactly the three closed limitations', () => {
    const six = Array.from({ length: 6 }, () => base.highlights[0]);
    expect(valid({ ...base, highlights: six })).toBe(false);
    expect(valid({ ...base, limitations: base.limitations.slice(0, 2) })).toBe(false);
  });
});

describe('internal OpenAPI', () => {
  it('declares exactly the one Core-only operation with the service-token scheme', () => {
    const spec = parse(read('../openapi/ai-service-internal.yaml'));
    expect(Object.keys(spec.paths)).toEqual(['/internal/operational-summary']);
    expect(spec.paths['/internal/operational-summary'].post.operationId).toBe(
      'generateOperationalSummary',
    );
    expect(spec.security).toEqual([{ coreServiceToken: ['generate-operational-summary'] }]);
  });
});
