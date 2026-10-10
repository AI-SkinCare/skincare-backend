// TRD §7.4 — 저장은 UTC, 응답은 밀리초 없는 ISO 8601 UTC. KST는 일일 한도·배치 시각에만 (UTC+9 고정).

const KST_OFFSET_MS = 9 * 60 * 60 * 1000;
const DAY_MS = 24 * 60 * 60 * 1000;

/** `2026-10-15T09:12:33Z` */
export function toIsoUtc(date: Date): string {
  return date.toISOString().replace(/\.\d{3}Z$/, 'Z');
}

export function toIsoUtcOrNull(date: Date | null | undefined): string | null {
  return date ? toIsoUtc(date) : null;
}

/** KST 날짜 `YYYY-MM-DD` */
export function kstDate(date: Date): string {
  return new Date(date.getTime() + KST_OFFSET_MS).toISOString().slice(0, 10);
}

/** now가 속한 KST 날짜의 00:00 (UTC 시각으로) */
export function startOfKstDay(now: Date): Date {
  const shifted = now.getTime() + KST_OFFSET_MS;
  return new Date(Math.floor(shifted / DAY_MS) * DAY_MS - KST_OFFSET_MS);
}

/** 다음 KST 00:00까지 남은 초 (올림, 최소 1) */
export function secondsUntilNextKstMidnight(now: Date): number {
  const next = startOfKstDay(now).getTime() + DAY_MS;
  return Math.max(1, Math.ceil((next - now.getTime()) / 1000));
}
