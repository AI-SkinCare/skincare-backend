// TRD 부록 A.1·A.2·A.6·A.7 — 열거형 값의 단일 출처.

export const ANALYSIS_STATUSES = ['queued', 'recommending', 'done', 'failed'] as const;
export type AnalysisStatus = (typeof ANALYSIS_STATUSES)[number];

export const RECOMMENDATION_STATUSES = ['pending', 'done', 'failed'] as const;
export type RecommendationStatus = (typeof RECOMMENDATION_STATUSES)[number];

export const ROIS = [
  'full_face',
  'forehead',
  'glabella',
  'eye_left',
  'eye_right',
  'cheek_left',
  'cheek_right',
  'lip',
  'chin',
] as const;
export type Roi = (typeof ROIS)[number];

/** byRoi 응답 순서 — full_face 제외 (TRD §10.2) */
export const BY_ROI_ORDER: readonly Roi[] = ROIS.filter((roi) => roi !== 'full_face');

/** summary 응답 순서 고정 */
export const METRICS = ['moisture', 'elasticity', 'pigmentation', 'pore'] as const;
export type Metric = (typeof METRICS)[number];

/** 값이 높을수록 좋은 지표. pigmentation·pore는 높을수록 나쁨 */
export const GOOD_DIRECTION_METRICS: ReadonlySet<Metric> = new Set<Metric>([
  'moisture',
  'elasticity',
]);

export const GRADES = ['good', 'normal', 'care'] as const;
export type Grade = (typeof GRADES)[number];

export const DEVICE_TYPES = ['smartphone', 'tablet', 'camera'] as const;
export type DeviceType = (typeof DEVICE_TYPES)[number];
