import { ulid } from 'ulidx';

const ULID_PATTERN = /^[0-9A-HJKMNP-TV-Z]{26}$/;

export function isUlid(value: unknown): value is string {
  return typeof value === 'string' && ULID_PATTERN.test(value);
}

export function newUlid(seedTime?: number): string {
  return ulid(seedTime);
}
