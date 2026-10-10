import { resolveRequestId } from './request-id.middleware';

describe('resolveRequestId (TRD §7.5)', () => {
  it('형식에 맞으면 그대로', () => {
    expect(resolveRequestId('ios-01J9Z3K5.abc_1')).toBe('ios-01J9Z3K5.abc_1');
  });
  it.each([undefined, '', 'a'.repeat(65), 'has space', '한글', ['a', 'b']])(
    '%j → UUID v4',
    (value) => {
      expect(resolveRequestId(value)).toMatch(/^[0-9a-f-]{36}$/);
    },
  );
});
