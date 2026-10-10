import { ValidationError } from 'class-validator';
import { flattenValidationErrors, reasonFor } from './validation-errors';

describe('validation errors → detail.fields', () => {
  it('값이 없으면 required, 아니면 제약별 사유', () => {
    expect(reasonFor('isIn', undefined)).toBe('required');
    expect(reasonFor('isIn', 'x')).toBe('invalid_value');
    expect(reasonFor('isEmail', 'x')).toBe('invalid_format');
    expect(reasonFor('someCustomThing', 'x')).toBe('some_custom_thing');
  });

  it('중첩 경로를 편다 (agreements.terms)', () => {
    const child = Object.assign(new ValidationError(), {
      property: 'terms',
      value: false,
      constraints: { equals: 'x' },
      children: [],
    });
    const parent = Object.assign(new ValidationError(), {
      property: 'agreements',
      value: {},
      children: [child],
    });
    expect(flattenValidationErrors([parent])).toEqual([
      { field: 'agreements.terms', reason: 'invalid_value' },
    ]);
  });
});
