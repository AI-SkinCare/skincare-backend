// TRD 부록 A.3·A.4·A.5·A.7 — 코드 ↔ AI 라벨의 단일 출처.
// 라벨은 AI 서버가 문자열 일치로 인식하므로 괄호·슬래시까지 한 글자도 바꾸지 않는다.

export const GENDERS = ['male', 'female'] as const;
export type Gender = (typeof GENDERS)[number];

export const AGE_BANDS = ['10s', '20s', '30s', '40s', '50s', '60s', '70s'] as const;
export type AgeBand = (typeof AGE_BANDS)[number];

export const LEVELS = ['low', 'medium', 'high'] as const;
export type Level = (typeof LEVELS)[number];

export const SKIN_TYPE_LABELS = {
  dry: '건성',
  normal: '중성',
  oily: '지성',
  combination: '복합성',
} as const;
export type SkinTypeCode = keyof typeof SKIN_TYPE_LABELS;
export const SKIN_TYPES = Object.keys(SKIN_TYPE_LABELS) as SkinTypeCode[];

export const CONCERN_LABELS = {
  pigmentation: '미백(색소침착/기미/칙칙함)',
  pore: '모공',
  wrinkle: '주름',
  acne: '여드름/뾰루지',
  redness: '붉어짐(홍조)',
  dryness: '과각질/악건성',
  sagging: '피부처짐/탄력저하',
  sensitivity: '민감성(트러블/자극감)',
} as const;
export type ConcernCode = keyof typeof CONCERN_LABELS;
export const CONCERNS = Object.keys(CONCERN_LABELS) as ConcernCode[];

export const ROUTINE_TIMES = ['morning', 'evening', 'daily', 'weekly', 'anytime'] as const;
export type RoutineTime = (typeof ROUTINE_TIMES)[number];

const SKIN_TYPE_BY_LABEL = new Map<string, SkinTypeCode>(
  SKIN_TYPES.map((code) => [SKIN_TYPE_LABELS[code], code]),
);
const CONCERN_BY_LABEL = new Map<string, ConcernCode>(
  CONCERNS.map((code) => [CONCERN_LABELS[code], code]),
);

export function skinTypeLabel(code: SkinTypeCode): string {
  return SKIN_TYPE_LABELS[code];
}

export function concernLabel(code: ConcernCode): string {
  return CONCERN_LABELS[code];
}

/** AI 라벨(또는 영문 코드) → 코드. 표에 없으면 null */
export function skinTypeFromAi(value: string): SkinTypeCode | null {
  if ((SKIN_TYPES as string[]).includes(value)) return value as SkinTypeCode;
  return SKIN_TYPE_BY_LABEL.get(value) ?? null;
}

/** AI 라벨 → 코드. 표에 없으면 null */
export function concernFromAiLabel(label: string): ConcernCode | null {
  return CONCERN_BY_LABEL.get(label) ?? null;
}
