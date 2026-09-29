import { describe, expect, it } from 'vitest';
import { compareKeyset, mergeKeyset } from './pagedList';

const row = (code: string, id: string, name = code) => ({ id, code, name });

describe('keyset merge', () => {
  it('orders by code in byte order, then id', () => {
    const rows = [
      row('b', '2'),
      row('B', '9'),
      row('A-1', '5'),
      row('A_1', '4'),
      row('A1', '3'),
      row('B', '1'),
    ];
    expect([...rows].sort(compareKeyset).map((r) => `${r.code}/${r.id}`)).toEqual([
      'A-1/5',
      'A1/3',
      'A_1/4',
      'B/1',
      'B/9',
      'b/2',
    ]);
  });

  it('keeps one row per id, the incoming one winning, in keyset order', () => {
    const merged = mergeKeyset(
      [row('RH', 'a'), row('TR', 'c', 'created')],
      [row('SEC', 'b'), row('TR', 'c', 'from server')],
    );
    expect(merged.map((r) => `${r.code}:${r.name}`)).toEqual([
      'RH:RH',
      'SEC:SEC',
      'TR:from server',
    ]);
  });

  it('does not mutate its inputs', () => {
    const current = [row('B', '2')];
    const incoming = [row('A', '1')];
    mergeKeyset(current, incoming);
    expect(current).toEqual([row('B', '2')]);
    expect(incoming).toEqual([row('A', '1')]);
  });
});
