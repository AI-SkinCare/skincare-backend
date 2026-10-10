// TRD §12.3·12.4 — AI 응답 검증. AI 출력은 그대로 믿지 않는다 (설계 원칙 5).
// 응답은 unknown으로 받아 필드마다 확인한다. 위반 시 경로와 이유만 모으고 값은 남기지 않는다.

import {
  METRICS,
  ROIS,
  ROUTINE_TIMES,
  Roi,
  RoutineTime,
  concernFromAiLabel,
  skinTypeFromAi,
} from '../common/enums';
import type { MetricRatios } from '../analyses/scoring';
import type { AiHealth, MetricsByRoi, SkinMetricsResult } from './ai-client.interface';
import { AiError, ValidationIssue } from './ai-error';

const MODEL_VERSION_MAX = 40;

type Obj = Record<string, unknown>;

function isObj(value: unknown): value is Obj {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

class Checker {
  readonly issues: ValidationIssue[] = [];

  fail(path: string, reason: string): undefined {
    this.issues.push({ path, reason });
    return undefined;
  }

  object(value: unknown, path: string): Obj | undefined {
    return isObj(value) ? value : this.fail(path, 'not_object');
  }

  string(value: unknown, path: string, options: { maxLength?: number } = {}): string | undefined {
    if (typeof value !== 'string') return this.fail(path, 'not_string');
    if (options.maxLength !== undefined && value.length > options.maxLength) {
      return this.fail(path, 'too_long');
    }
    return value;
  }

  boolean(value: unknown, path: string): boolean | undefined {
    return typeof value === 'boolean' ? value : this.fail(path, 'not_boolean');
  }

  number(
    value: unknown,
    path: string,
    range: { min?: number; max?: number } = {},
  ): number | undefined {
    if (typeof value !== 'number' || !Number.isFinite(value)) return this.fail(path, 'not_number');
    if (range.min !== undefined && value < range.min) return this.fail(path, 'out_of_range');
    if (range.max !== undefined && value > range.max) return this.fail(path, 'out_of_range');
    return value;
  }

  integer(value: unknown, path: string): number | undefined {
    const n = this.number(value, path);
    if (n === undefined) return undefined;
    return Number.isInteger(n) ? n : this.fail(path, 'not_integer');
  }

  array(value: unknown, path: string): unknown[] | undefined {
    return Array.isArray(value) ? value : this.fail(path, 'not_array');
  }

  stringArray(value: unknown, path: string): string[] | undefined {
    const arr = this.array(value, path);
    if (!arr) return undefined;
    const before = this.issues.length;
    const result = arr.map((item, i) => this.string(item, `${path}[${i}]`));
    return this.issues.length === before ? (result as string[]) : undefined;
  }

  /** 루트가 객체가 아니면 더 볼 것이 없다 */
  root(value: unknown): Obj {
    if (isObj(value)) return value;
    this.fail('$', 'not_object');
    throw new AiError('invalid_response', { issues: this.issues });
  }

  throwIfInvalid(): void {
    if (this.issues.length > 0) throw new AiError('invalid_response', { issues: this.issues });
  }
}

function isRoi(value: string): value is Roi {
  return (ROIS as readonly string[]).includes(value);
}

/** POST /infer/skin-metrics 응답 → SkinMetricsResult. ROI 이름·0–1 범위·빈 metrics 검사 */
export function parseSkinMetricsResponse(body: unknown): SkinMetricsResult {
  const c = new Checker();
  const root = c.root(body);

  const roiDetected: Roi[] = [];
  const detected = c.array(root.roi_detected, 'roi_detected');
  detected?.forEach((item, i) => {
    const roi = c.string(item, `roi_detected[${i}]`);
    if (roi === undefined) return;
    if (isRoi(roi)) roiDetected.push(roi);
    else c.fail(`roi_detected[${i}]`, 'unknown_roi');
  });

  const metrics: MetricsByRoi = {};
  const metricsObj = c.object(root.metrics, 'metrics');
  if (metricsObj) {
    const entries = Object.entries(metricsObj);
    if (entries.length === 0) c.fail('metrics', 'empty');
    for (const [roi, raw] of entries) {
      if (!isRoi(roi)) {
        c.fail(`metrics.${roi}`, 'unknown_roi');
        continue;
      }
      const values = c.object(raw, `metrics.${roi}`);
      if (!values) continue;
      const ratios = {} as MetricRatios;
      let valid = true;
      for (const metric of METRICS) {
        const v = c.number(values[metric], `metrics.${roi}.${metric}`, { min: 0, max: 1 });
        if (v === undefined) valid = false;
        else ratios[metric] = v;
      }
      if (valid) metrics[roi] = ratios;
    }
  }

  const confidence = c.number(root.confidence, 'confidence', { min: 0, max: 1 });
  const calibrationApplied = c.boolean(root.calibration_applied, 'calibration_applied');
  const isStub = c.boolean(root.is_stub, 'is_stub');
  const modelVersion = c.string(root.model_version, 'model_version', {
    maxLength: MODEL_VERSION_MAX,
  });
  const inferenceMs = c.number(root.inference_ms, 'inference_ms', { min: 0 });
  c.throwIfInvalid();

  return {
    roiDetected,
    metrics,
    confidence: confidence as number,
    calibrationApplied: calibrationApplied as boolean,
    isStub: isStub as boolean,
    modelVersion: modelVersion as string,
    inferenceMs: Math.round(inferenceMs as number),
  };
}

/** 검증만 마친 추천 응답 (라벨 그대로, snake_case 필드는 camelCase로) */
export interface ValidatedRecommendResponse {
  skinType: string;
  concerns: string[];
  ingredients: { name: string; purpose: string | null; priority: number }[];
  routine: { step: number; time: RoutineTime; action: string }[];
  reasoning: string[];
  answer: string;
  disclaimer: string;
  filtered: boolean;
  usedFallback: boolean;
  inputNotes: string[];
  modelVersion: string;
  inferenceMs: number;
  warnings: string[];
}

/**
 * POST /infer/recommend 응답 검증 (TRD §12.4 표).
 * 열거형·타입 위반은 invalid_response(→ failed E4001 + ERROR),
 * 개수 위반(ingredients > 5, concerns > 2, reasoning ≠ 3)은 WARN만 남기고 저장한다.
 */
export function parseRecommendResponse(body: unknown): ValidatedRecommendResponse {
  const c = new Checker();
  const root = c.root(body);
  const warnings: string[] = [];

  const skinType = c.string(root.skin_type, 'skin_type');
  if (skinType !== undefined && skinTypeFromAi(skinType) === null) {
    c.fail('skin_type', 'unknown_skin_type');
  }

  const concerns = c.stringArray(root.concerns, 'concerns');
  concerns?.forEach((label, i) => {
    if (concernFromAiLabel(label) === null) c.fail(`concerns[${i}]`, 'unknown_concern');
  });
  if (concerns && concerns.length > 2) warnings.push(`concerns_count:${concerns.length}`);

  const ingredients: ValidatedRecommendResponse['ingredients'] = [];
  c.array(root.ingredients, 'ingredients')?.forEach((raw, i) => {
    const item = c.object(raw, `ingredients[${i}]`);
    if (!item) return;
    const name = c.string(item.name, `ingredients[${i}].name`);
    const purpose =
      item.purpose === null || item.purpose === undefined
        ? null
        : c.string(item.purpose, `ingredients[${i}].purpose`);
    const priority = c.integer(item.priority, `ingredients[${i}].priority`);
    if (name !== undefined && purpose !== undefined && priority !== undefined) {
      ingredients.push({ name, purpose, priority });
    }
  });
  if (ingredients.length > 5) warnings.push(`ingredients_count:${ingredients.length}`);

  const routine: ValidatedRecommendResponse['routine'] = [];
  c.array(root.routine, 'routine')?.forEach((raw, i) => {
    const item = c.object(raw, `routine[${i}]`);
    if (!item) return;
    const step = c.integer(item.step, `routine[${i}].step`);
    const time = c.string(item.time, `routine[${i}].time`);
    if (time !== undefined && !(ROUTINE_TIMES as readonly string[]).includes(time)) {
      c.fail(`routine[${i}].time`, 'unknown_routine_time');
    }
    const action = c.string(item.action, `routine[${i}].action`);
    if (step !== undefined && time !== undefined && action !== undefined) {
      routine.push({ step, time: time as RoutineTime, action });
    }
  });

  const reasoning = c.stringArray(root.reasoning, 'reasoning');
  if (reasoning && reasoning.length !== 3) warnings.push(`reasoning_count:${reasoning.length}`);

  const answer = c.string(root.answer, 'answer');
  const disclaimer = c.string(root.disclaimer, 'disclaimer');
  const filtered = c.boolean(root.filtered, 'filtered');
  const usedFallback = c.boolean(root.used_fallback, 'used_fallback');
  const inputNotes =
    root.input_notes === undefined || root.input_notes === null
      ? []
      : c.stringArray(root.input_notes, 'input_notes');
  const modelVersion = c.string(root.model_version, 'model_version', {
    maxLength: MODEL_VERSION_MAX,
  });
  const inferenceMs = c.number(root.inference_ms, 'inference_ms', { min: 0 });
  c.throwIfInvalid();

  return {
    skinType: skinType as string,
    concerns: concerns as string[],
    ingredients,
    routine,
    reasoning: reasoning as string[],
    answer: answer as string,
    disclaimer: disclaimer as string,
    filtered: filtered as boolean,
    usedFallback: usedFallback as boolean,
    inputNotes: inputNotes as string[],
    modelVersion: modelVersion as string,
    inferenceMs: Math.round(inferenceMs as number),
    warnings,
  };
}

/** GET /health */
export function parseHealthResponse(body: unknown): AiHealth {
  const c = new Checker();
  const root = c.root(body);
  const status = c.string(root.status, 'status');
  c.throwIfInvalid();
  return {
    status: status as string,
    recLoaded: typeof root.rec_loaded === 'boolean' ? root.rec_loaded : undefined,
    skinLoaded: typeof root.skin_loaded === 'boolean' ? root.skin_loaded : undefined,
    recBackend: typeof root.rec_backend === 'string' ? root.rec_backend : undefined,
    skinBackend: typeof root.skin_backend === 'string' ? root.skin_backend : undefined,
  };
}
