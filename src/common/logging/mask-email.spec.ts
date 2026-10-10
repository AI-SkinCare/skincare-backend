import { maskEmail } from './mask-email';

describe('maskEmail (TRD §7.6)', () => {
  it('첫 글자 + *** + @도메인', () => {
    expect(maskEmail('dohee@gmail.com')).toBe('d***@gmail.com');
    expect(maskEmail('a@b.co')).toBe('a***@b.co');
  });
  it('형식이 아니면 전부 가린다', () => {
    expect(maskEmail('nope')).toBe('***');
  });
});
