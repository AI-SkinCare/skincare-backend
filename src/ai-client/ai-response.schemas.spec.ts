import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { AiError } from './ai-error';
import { parseRecommendResponse, parseSkinMetricsResponse } from './ai-response.schemas';
import { toRecommendResult } from './ai-transform';

const FIXTURE_DIR = join(__dirname, '..', '..', 'test', 'fixtures', 'ai');
const fixture = (name: string): Record<string, unknown> =>
  JSON.parse(readFileSync(join(FIXTURE_DIR, name), 'utf8')) as Record<string, unknown>;

function issuesOf(fn: () => unknown): { path: string; reason: string }[] {
  try {
    fn();
  } catch (error) {
    if (error instanceof AiError && error.kind === 'invalid_response') return error.issues;
    throw error;
  }
  throw new Error('검증이 통과해 버렸다');
}

// 계약 테스트 — skincare-ai/docs/fixtures 실제 출력이 검증·변환을 통과해야 한다 (TRD §16.1)
describe('fixture 계약', () => {
  it('skin-metrics.ok.json 통과', () => {
    const result = parseSkinMetricsResponse(fixture('skin-metrics.ok.json'));
    expect(result.isStub).toBe(true);
    expect(Object.keys(result.metrics).length).toBeGreaterThan(0);
  });

  it('recommend.ok.json 통과 → 라벨이 코드로', () => {
    const result = toRecommendResult(parseRecommendResponse(fixture('recommend.ok.json')));
    expect(result.skinType).toBe('combination');
    expect(result.concerns).toEqual(['pore', 'pigmentation']);
    expect(result.reasoning).toHaveLength(3);
    expect(result.warnings).toEqual([]);
  });
});

describe('parseSkinMetricsResponse', () => {
  const valid = () => fixture('skin-metrics.ok.json');

  it('ROI 이름이 표에 없으면 invalid_response', () => {
    const body = valid();
    (body.metrics as Record<string, unknown>).nose = {
      moisture: 0.5,
      elasticity: 0.5,
      pigmentation: 0.5,
      pore: 0.5,
    };
    expect(issuesOf(() => parseSkinMetricsResponse(body))).toContainEqual({
      path: 'metrics.nose',
      reason: 'unknown_roi',
    });
  });

  it('값이 0–1 밖이면 invalid_response', () => {
    const body = valid();
    (body.metrics as Record<string, Record<string, number>>).full_face.pore = 1.2;
    expect(issuesOf(() => parseSkinMetricsResponse(body))).toContainEqual({
      path: 'metrics.full_face.pore',
      reason: 'out_of_range',
    });
  });

  it('metrics가 비어 있으면 invalid_response', () => {
    expect(issuesOf(() => parseSkinMetricsResponse({ ...valid(), metrics: {} }))).toContainEqual({
      path: 'metrics',
      reason: 'empty',
    });
  });

  it('객체가 아니면 invalid_response', () => {
    expect(issuesOf(() => parseSkinMetricsResponse('<html>'))).toEqual([
      { path: '$', reason: 'not_object' },
    ]);
  });
});

describe('parseRecommendResponse', () => {
  const valid = () => fixture('recommend.ok.json');

  it('영문 코드 skin_type도 받는다', () => {
    expect(
      toRecommendResult(parseRecommendResponse({ ...valid(), skin_type: 'oily' })).skinType,
    ).toBe('oily');
  });

  it('skin_type이 표에 없으면 failed E4001 대상', () => {
    expect(
      issuesOf(() => parseRecommendResponse({ ...valid(), skin_type: '수부지' })),
    ).toContainEqual({ path: 'skin_type', reason: 'unknown_skin_type' });
  });

  it('concerns 라벨이 한 글자라도 다르면 위반 (괄호·슬래시 그대로)', () => {
    expect(
      issuesOf(() => parseRecommendResponse({ ...valid(), concerns: ['미백(색소침착/기미)'] })),
    ).toContainEqual({ path: 'concerns[0]', reason: 'unknown_concern' });
  });

  it('routine.time이 열거형 밖이면 위반', () => {
    const body = valid();
    (body.routine as Record<string, unknown>[])[0].time = 'night';
    expect(issuesOf(() => parseRecommendResponse(body))).toContainEqual({
      path: 'routine[0].time',
      reason: 'unknown_routine_time',
    });
  });

  it('필수 필드가 없으면 위반', () => {
    const body = valid();
    delete body.disclaimer;
    expect(issuesOf(() => parseRecommendResponse(body))).toContainEqual({
      path: 'disclaimer',
      reason: 'not_string',
    });
  });

  it('개수 위반(ingredients > 5, concerns > 2, reasoning ≠ 3)은 WARN만', () => {
    const ingredient = { name: '판테놀', purpose: null, priority: 1 };
    const result = parseRecommendResponse({
      ...valid(),
      concerns: ['모공', '주름', '피부처짐/탄력저하'],
      ingredients: Array.from({ length: 6 }, () => ingredient),
      reasoning: ['한 문단'],
    });
    expect(result.warnings).toEqual([
      'concerns_count:3',
      'ingredients_count:6',
      'reasoning_count:1',
    ]);
  });

  it('purpose null·빈 ingredients/routine·input_notes 없음은 정상', () => {
    const body = { ...valid(), ingredients: [], routine: [] } as Record<string, unknown>;
    delete body.input_notes;
    const result = toRecommendResult(parseRecommendResponse(body));
    expect(result.ingredients).toEqual([]);
    expect(result.inputNotes).toEqual([]);
  });

  it('중복 고민은 하나로', () => {
    expect(
      toRecommendResult(parseRecommendResponse({ ...valid(), concerns: ['모공', '모공'] }))
        .concerns,
    ).toEqual(['pore']);
  });
});
