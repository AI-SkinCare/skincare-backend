import { kstDate, secondsUntilNextKstMidnight, startOfKstDay, toIsoUtc } from './time';

describe('time (TRD §7.4)', () => {
  it('toIsoUtc는 밀리초를 뺀다', () => {
    expect(toIsoUtc(new Date('2026-10-15T09:12:33.987Z'))).toBe('2026-10-15T09:12:33Z');
  });

  it('KST 날짜는 UTC+9 고정', () => {
    expect(kstDate(new Date('2026-10-15T14:59:59Z'))).toBe('2026-10-15');
    expect(kstDate(new Date('2026-10-15T15:00:00Z'))).toBe('2026-10-16');
  });

  it('일일 한도 기준: KST 00:00 = 전날 UTC 15:00', () => {
    expect(startOfKstDay(new Date('2026-10-15T03:00:00Z')).toISOString()).toBe(
      '2026-10-14T15:00:00.000Z',
    );
    expect(startOfKstDay(new Date('2026-10-15T15:00:00Z')).toISOString()).toBe(
      '2026-10-15T15:00:00.000Z',
    );
  });

  it('Retry-After = 다음 KST 00:00까지 초', () => {
    expect(secondsUntilNextKstMidnight(new Date('2026-10-15T14:59:00Z'))).toBe(60);
    expect(secondsUntilNextKstMidnight(new Date('2026-10-15T15:00:00Z'))).toBe(86400);
  });
});
