import type {
  AgeBand,
  ConcernCode,
  DeviceType,
  FixtureScenario,
  Gender,
  Level,
  Roi,
  SkinTypeCode,
} from '../common/enums';
import type { IngredientItem, RoutineItem } from '../analyses/entities/recommendation.entity';
import type { MetricRatios } from '../analyses/scoring';

export const AI_CLIENT = Symbol('AI_CLIENT');

export interface AiCallContext {
  requestId: string;
  /** 비운영 환경의 X-Fixture-Scenario (FixtureAiClient만 사용) */
  scenario?: FixtureScenario;
}

export interface AiHealth {
  status: string;
  recLoaded?: boolean;
  skinLoaded?: boolean;
  recBackend?: string;
  skinBackend?: string;
}

export type MetricsByRoi = Partial<Record<Roi, MetricRatios>>;

export interface MeasureSkinInput {
  image: Buffer;
  mimeType: 'image/jpeg' | 'image/png';
  deviceType: DeviceType;
}

/** 검증·camelCase 변환을 마친 측정 결과 (0–1 원본) */
export interface SkinMetricsResult {
  roiDetected: Roi[];
  metrics: MetricsByRoi;
  confidence: number;
  calibrationApplied: boolean;
  isStub: boolean;
  modelVersion: string;
  inferenceMs: number;
}

/** AI 서버가 받는 설문 — 코드가 아니라 한글 라벨 (TRD §12.4) */
export interface AiSurvey {
  gender: Gender;
  ageBand: AgeBand;
  skinType?: string;
  sleepHours?: number;
  waterIntakeLevel?: Level;
  sunExposureLevel?: Level;
  stressLevel?: Level;
  currentProducts?: string[];
  selfReportedConcerns?: string[];
  note?: string;
}

export interface RecommendRequest {
  /** is_demo=true 분석은 null (FR-MEA-04) */
  metrics: MetricsByRoi | null;
  survey: AiSurvey;
}

/** 검증을 마치고 라벨을 코드로 바꾼 추천 결과 */
export interface RecommendResult {
  skinType: SkinTypeCode;
  concerns: ConcernCode[];
  ingredients: IngredientItem[];
  routine: RoutineItem[];
  reasoning: string[];
  answer: string;
  disclaimer: string;
  filtered: boolean;
  usedFallback: boolean;
  inputNotes: string[];
  modelVersion: string;
  inferenceMs: number;
  /** 개수 규칙 위반 등 저장은 하되 WARN으로 남길 것 */
  warnings: string[];
}

/**
 * AI_MODE=real → HttpAiClient, fixture → FixtureAiClient.
 * 오류는 AiError로 던지고, 백엔드 코드로의 변환은 호출하는 쪽이 한다 (TRD §12.1).
 */
export interface AiClient {
  health(): Promise<AiHealth>;
  modelInfo(): Promise<unknown>;
  measureSkin(input: MeasureSkinInput, ctx: AiCallContext): Promise<SkinMetricsResult>;
  recommend(input: RecommendRequest, ctx: AiCallContext): Promise<RecommendResult>;
}
