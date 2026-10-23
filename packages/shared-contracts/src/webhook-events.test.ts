import { readdirSync, readFileSync } from 'node:fs';
import { Ajv2020 } from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
import { describe, expect, it } from 'vitest';

const read = (relative: string) => readFileSync(new URL(relative, import.meta.url), 'utf8');
const schema = JSON.parse(read('../schemas/webhook-event.schema.json'));
const dir = new URL('../webhooks/v1/examples/', import.meta.url);
const examples = readdirSync(dir).map((f) => ({
  name: f.replace(/\.json$/, ''),
  body: JSON.parse(readFileSync(new URL(f, dir), 'utf8')),
}));

const ajv = new Ajv2020({ allErrors: true, strict: true });
addFormats.default(ajv);
const validate = ajv.compile(schema);
const first = examples[0]!.body;

describe('public webhook events', () => {
  it('has one example per catalogued event type, and nothing else', () => {
    expect(examples.map((e) => e.name).sort()).toEqual(
      [...schema.properties.eventType.enum].sort(),
    );
  });

  it.each(examples)('accepts the $name example', ({ body }) => {
    expect(validate(body), JSON.stringify(validate.errors)).toBe(true);
  });

  it('rejects unknown top-level and resource fields', () => {
    expect(validate({ ...first, employeeName: 'x' })).toBe(false);
    expect(validate({ ...first, resource: { ...first.resource, email: 'a@b.c' } })).toBe(false);
  });

  it('rejects an event type outside the catalogue', () => {
    expect(validate({ ...first, eventType: 'people.leave-request.approved.v1' })).toBe(false);
  });

  it('rejects a status or link that does not belong to the event type', () => {
    expect(validate({ ...first, resource: { ...first.resource, status: 'LOCKED' } })).toBe(false);
    expect(validate({ ...first, links: ['/admin/timesheets'] })).toBe(false);
    expect(validate({ ...first, links: ['https://evil.example/x'] })).toBe(false);
  });

  it('carries no personal or free-text fields in any example', () => {
    const text = examples.map((e) => JSON.stringify(e.body)).join('\n');
    expect(text).not.toMatch(/@|name|reason|comment|amount|salary|correlation|digest/i);
  });
});
