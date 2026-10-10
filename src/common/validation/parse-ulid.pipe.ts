import { Injectable, PipeTransform } from '@nestjs/common';
import { ApiException } from '../errors/api.exception';
import { ErrorCode } from '../errors/error-codes';
import { isUlid } from '../utils/ulid';

/** 경로의 분석 id가 ULID 형식이 아니면 404 E4003 — 422가 아니다 (존재 여부를 흘리지 않음, TRD §7.4) */
@Injectable()
export class ParseUlidPipe implements PipeTransform<unknown, string> {
  transform(value: unknown): string {
    if (!isUlid(value)) throw new ApiException(ErrorCode.E4003);
    return value;
  }
}
