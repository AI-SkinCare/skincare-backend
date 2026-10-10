/**
 * TypeORM의 postgres `query()`는 UPDATE·DELETE에 `[rows, rowCount]`를, 그 밖에는 rows를 준다.
 * raw SQL(§13.3, FOR UPDATE SKIP LOCKED)을 쓰는 곳에서 두 모양을 하나로 맞춘다.
 */
export function rowsAndCount<T>(raw: unknown): { rows: T[]; count: number } {
  if (
    Array.isArray(raw) &&
    raw.length === 2 &&
    Array.isArray(raw[0]) &&
    typeof raw[1] === 'number'
  ) {
    return { rows: raw[0] as T[], count: raw[1] };
  }
  if (Array.isArray(raw)) return { rows: raw as T[], count: raw.length };
  return { rows: [], count: 0 };
}

/** pg는 COUNT(*)·AVG·BIGINT를 문자열로 준다 */
export function toNumber(value: unknown): number {
  const n = Number(value);
  if (!Number.isFinite(n)) throw new Error('숫자가 아닌 집계 결과');
  return n;
}
