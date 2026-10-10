import { ValidationError } from 'class-validator';
import { ApiException } from '../errors/api.exception';
import { ErrorCode } from '../errors/error-codes';

export interface FieldError {
  field: string;
  reason: string;
}

/** class-validator 제약 이름 → detail.reason (소문자 snake_case) */
const REASON_BY_CONSTRAINT: Record<string, string> = {
  isDefined: 'required',
  isNotEmpty: 'required',
  isString: 'invalid_type',
  isInt: 'invalid_type',
  isNumber: 'invalid_type',
  isBoolean: 'invalid_type',
  isArray: 'invalid_type',
  isObject: 'invalid_type',
  nestedValidation: 'invalid_type',
  isIn: 'invalid_value',
  isEnum: 'invalid_value',
  equals: 'invalid_value',
  isEmail: 'invalid_format',
  matches: 'invalid_format',
  minLength: 'too_short',
  maxLength: 'too_long',
  arrayMinSize: 'too_short',
  arrayMaxSize: 'too_many',
  arrayUnique: 'duplicate',
  min: 'out_of_range',
  max: 'out_of_range',
};

export function reasonFor(constraint: string, value: unknown): string {
  if (value === undefined || value === null) return 'required';
  return (
    REASON_BY_CONSTRAINT[constraint] ?? constraint.replace(/[A-Z]/g, (c) => `_${c.toLowerCase()}`)
  );
}

/** 중첩 오류를 `survey.gender`, `agreements.terms` 같은 경로로 편다. 필드마다 첫 사유 하나만. */
export function flattenValidationErrors(errors: ValidationError[], prefix = ''): FieldError[] {
  const result: FieldError[] = [];
  for (const error of errors) {
    const field = prefix ? `${prefix}.${error.property}` : error.property;
    const constraints = error.constraints ? Object.keys(error.constraints) : [];
    if (constraints.length > 0) {
      result.push({ field, reason: reasonFor(constraints[0], error.value) });
    }
    if (error.children && error.children.length > 0) {
      result.push(...flattenValidationErrors(error.children, field));
    }
  }
  return result;
}

export function validationException(fields: FieldError[]): ApiException {
  return new ApiException(ErrorCode.E4002, { detail: { fields } });
}

/** 전역 ValidationPipe의 exceptionFactory — 400 대신 422 E4002 (TRD §7.3) */
export function validationExceptionFactory(errors: ValidationError[]): ApiException {
  return validationException(flattenValidationErrors(errors));
}
