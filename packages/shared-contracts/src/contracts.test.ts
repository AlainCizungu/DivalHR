import { readFileSync } from 'node:fs';
import { Ajv2020 } from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
import { describe, expect, it } from 'vitest';
import { parse } from 'yaml';

const read = (relative: string) => readFileSync(new URL(relative, import.meta.url), 'utf8');
const envelopeSchema = JSON.parse(read('../schemas/event-envelope.schema.json'));
const problemSchema = JSON.parse(read('../schemas/problem.schema.json'));

function validator(schema: object) {
  const ajv = new Ajv2020({ allErrors: true, strict: true });
  addFormats.default(ajv);
  return ajv.compile(schema);
}

const validEvent = {
  eventId: '6f1c1f0e-8a8b-4b43-9a3e-0f6f7b0c2a11',
  eventType: 'tenant.organization-created.v1',
  schemaVersion: 1,
  tenantId: '00000000-0000-4000-8000-00000000000a',
  source: 'core-api/tenant',
  subject: 'organization/00000000-0000-4000-8000-00000000000a',
  eventTime: '2026-09-28T12:00:00Z',
  correlationId: 'corr-12345678',
  causationId: null,
  data: {},
};

describe('event envelope', () => {
  it('accepts a complete envelope', () => {
    expect(validator(envelopeSchema)(validEvent)).toBe(true);
  });

  it('rejects an envelope without tenant scope', () => {
    const { tenantId: _omitted, ...withoutTenant } = validEvent;
    expect(validator(envelopeSchema)(withoutTenant)).toBe(false);
  });

  it('rejects unversioned event types', () => {
    expect(validator(envelopeSchema)({ ...validEvent, eventType: 'OrganizationCreated' })).toBe(
      false,
    );
  });
});

describe('problem schema', () => {
  it('lists exactly the error codes declared in the authoritative API contract', () => {
    const spec = parse(read('../../../docs/API-SPEC.yaml'));
    expect(problemSchema.properties.code.enum).toEqual(spec.components.schemas.ErrorCode.enum);
  });
});

describe('AI service status contract', () => {
  it('matches the Core API status shape', () => {
    const ai = parse(read('../openapi/ai-service.yaml'));
    const core = parse(read('../../../docs/API-SPEC.yaml'));
    expect(ai.components.schemas.SystemStatus).toEqual({
      ...core.components.schemas.SystemStatus,
      properties: {
        ...core.components.schemas.SystemStatus.properties,
        service: {
          ...core.components.schemas.SystemStatus.properties.service,
          example: 'ai-service',
        },
      },
    });
  });
});
