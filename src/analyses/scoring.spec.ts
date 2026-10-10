import {
  MetricRatios,
  buildScoreReport,
  grade,
  metricBp,
  overallFromBp,
  scoreFromValue,
  toBp,
  valueFromBp,
} from './scoring';

const ratios = (moisture: number, elasticity: number, pigmentation: number, pore: number) =>
  ({ moisture, elasticity, pigmentation, pore }) satisfies MetricRatios;

const valuesOf = (r: MetricRatios) => {
  const bp = metricBp(r);
  return [bp.moisture, bp.elasticity, bp.pigmentation, bp.pore].map(valueFromBp);
};

// TRD §11.3 표를 그대로 옮긴다.
describe('scoring (TRD §11.3)', () => {
  it('full_face (0.61, 0.74, 0.38, 0.45) → value 61,74,38,45 / score 61,74,62,55 / overall 63 normal', () => {
    const r = ratios(0.61, 0.74, 0.38, 0.45);
    const report = buildScoreReport({ full_face: metricBp(r) });
    expect(report.summary.map((s) => s.value)).toEqual([61, 74, 38, 45]);
    expect(report.summary.map((s) => s.score)).toEqual([61, 74, 62, 55]);
    expect(report.overallScore).toBe(63);
    expect(grade(report.overallScore)).toBe('normal');
  });

  it('forehead (0.58, 0.71, 0.35, 0.52) → score 58,71,65,48 / overall 61 (60.5 → 61) normal', () => {
    const r = ratios(0.58, 0.71, 0.35, 0.52);
    expect(valuesOf(r)).toEqual([58, 71, 35, 52]);
    const report = buildScoreReport({
      full_face: metricBp(ratios(0.5, 0.5, 0.5, 0.5)),
      forehead: metricBp(r),
    });
    const forehead = report.byRoi.find((x) => x.roi === 'forehead');
    expect(forehead).toEqual({
      roi: 'forehead',
      moisture: 58,
      elasticity: 71,
      pigmentation: 35,
      pore: 52,
      overallScore: 61,
      grade: 'normal',
    });
    expect(
      ['moisture', 'elasticity', 'pigmentation', 'pore'].map((m, i) =>
        scoreFromValue(m as 'moisture', valuesOf(r)[i]),
      ),
    ).toEqual([58, 71, 65, 48]);
  });

  it('cheek_left (0.66, 0.76, 0.41, 0.38) → score 66,76,59,62 / overall 66 (65.75) normal', () => {
    const r = ratios(0.66, 0.76, 0.41, 0.38);
    expect(valuesOf(r)).toEqual([66, 76, 41, 38]);
    expect(overallFromBp(metricBp(r))).toBe(66);
    expect(grade(66)).toBe('normal');
  });

  it('함정: moisture 0.565 → value 57 (naive 56)', () => {
    expect(Math.round(0.565 * 100)).toBe(56); // naive가 틀리는 것을 고정
    expect(valueFromBp(toBp(0.565))).toBe(57);
    expect(scoreFromValue('moisture', valueFromBp(toBp(0.565)))).toBe(57);
  });

  it.each([
    [0.145, 15],
    [0.285, 29],
    [0.565, 57],
    [0.575, 58],
  ])('함정: %s → %s (0.5 올림)', (v, expected) => {
    expect(valueFromBp(toBp(v))).toBe(expected);
  });

  it('함정: (0.8906, 0.971, 0.9249, 0.5167) → overall 61 (naive 60) normal', () => {
    const r = ratios(0.8906, 0.971, 0.9249, 0.5167);
    const naive = Math.round(
      (r.moisture * 100 +
        r.elasticity * 100 +
        (100 - r.pigmentation * 100) +
        (100 - r.pore * 100)) /
        4,
    );
    expect(naive).toBe(60);
    expect(overallFromBp(metricBp(r))).toBe(61);
    expect(grade(61)).toBe('normal');
  });

  it('경계: score 70 / 69 / 40 / 39 → good / normal / normal / care', () => {
    expect([70, 69, 40, 39].map(grade)).toEqual(['good', 'normal', 'normal', 'care']);
  });
});

describe('buildScoreReport', () => {
  it('summary 순서는 moisture → elasticity → pigmentation → pore', () => {
    const report = buildScoreReport({ full_face: metricBp(ratios(0.61, 0.74, 0.38, 0.45)) });
    expect(report.summary.map((s) => s.metric)).toEqual([
      'moisture',
      'elasticity',
      'pigmentation',
      'pore',
    ]);
    expect(report.summary.map((s) => s.grade)).toEqual(['normal', 'good', 'normal', 'normal']);
  });

  it('byRoi는 full_face 제외, forehead → glabella → … → chin 순서', () => {
    const m = metricBp(ratios(0.5, 0.5, 0.5, 0.5));
    const report = buildScoreReport({
      chin: m,
      full_face: m,
      cheek_left: m,
      forehead: m,
      glabella: m,
    });
    expect(report.byRoi.map((r) => r.roi)).toEqual(['forehead', 'glabella', 'cheek_left', 'chin']);
  });

  it('full_face가 없으면 검출된 ROI 평균 (나눗셈 한 번)', () => {
    const report = buildScoreReport({
      forehead: metricBp(ratios(0.58, 0.71, 0.35, 0.52)),
      cheek_left: metricBp(ratios(0.66, 0.76, 0.41, 0.38)),
    });
    // moisture (5800+6600)/200 = 62, elasticity 73.5 → 74, pigmentation 38, pore 45
    expect(report.summary.map((s) => s.value)).toEqual([62, 74, 38, 45]);
    // (24200 + 26300) / 800 = 63.125 → 63
    expect(report.overallScore).toBe(63);
  });

  it('지표가 없으면 예외', () => {
    expect(() => buildScoreReport({})).toThrow();
  });
});
