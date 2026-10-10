import { parseSurvey } from './survey';

const ok = { gender: 'female', ageBand: '20s' };

describe('parseSurvey (TRD §10.1)', () => {
  it('정규화: 모든 키, 미입력 null, 배열 []', () => {
    expect(parseSurvey(JSON.stringify(ok))).toEqual({
      ok: true,
      survey: {
        gender: 'female',
        ageBand: '20s',
        skinType: null,
        sleepHours: null,
        waterIntakeLevel: null,
        sunExposureLevel: null,
        stressLevel: null,
        concerns: [],
        currentProducts: [],
        note: null,
      },
    });
  });

  it('TC-ANA-02: gender 누락 → survey.gender required', () => {
    expect(parseSurvey(JSON.stringify({ ageBand: '20s' }))).toEqual({
      ok: false,
      errors: [{ field: 'survey.gender', reason: 'required' }],
    });
  });

  it('JSON 파싱 실패·배열·누락', () => {
    expect(parseSurvey('{bad')).toEqual({
      ok: false,
      errors: [{ field: 'survey', reason: 'invalid_format' }],
    });
    expect(parseSurvey('[]')).toEqual({
      ok: false,
      errors: [{ field: 'survey', reason: 'invalid_format' }],
    });
    expect(parseSurvey(undefined)).toEqual({
      ok: false,
      errors: [{ field: 'survey', reason: 'required' }],
    });
  });

  it.each([
    [{ concerns: ['pore', 'acne', 'wrinkle', 'redness'] }, 'survey.concerns', 'too_many'],
    [{ concerns: ['pore', 'pore'] }, 'survey.concerns', 'duplicate'],
    [{ concerns: ['여드름'] }, 'survey.concerns', 'invalid_value'],
    [{ sleepHours: 15 }, 'survey.sleepHours', 'out_of_range'],
    [{ sleepHours: 6.5 }, 'survey.sleepHours', 'invalid_type'],
    [{ skinType: '복합성' }, 'survey.skinType', 'invalid_value'],
    [{ note: 'a'.repeat(301) }, 'survey.note', 'too_long'],
    [{ currentProducts: ['a', 'b', 'c', 'd', 'e', 'f'] }, 'survey.currentProducts', 'too_many'],
  ])('%j → %s %s', (extra, field, reason) => {
    const result = parseSurvey(JSON.stringify({ ...ok, ...extra }));
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.errors).toContainEqual({ field, reason });
  });

  it('skinType null은 미입력, 모르는 키는 버린다', () => {
    const result = parseSurvey(JSON.stringify({ ...ok, skinType: null, extra: 'x' }));
    expect(result.ok && result.survey.skinType).toBeNull();
    expect(result.ok && 'extra' in result.survey).toBe(false);
  });
});
