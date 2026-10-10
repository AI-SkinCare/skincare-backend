import { ValueTransformer } from 'typeorm';

/**
 * pg는 NUMERIC·BIGINT를 문자열로 준다 (TRD §8.2).
 * 빠뜨리면 점수 계산이 문자열 연결로 깨지므로 해당 컬럼마다 반드시 붙인다.
 */
export const numberTransformer: ValueTransformer = {
  to: (value: number | null | undefined) => value,
  from: (value: string | number | null) => (value === null ? null : Number(value)),
};
