import { CallHandler, ExecutionContext, Injectable, NestInterceptor } from '@nestjs/common';
import type { Response } from 'express';
import { Observable, map } from 'rxjs';

export interface SuccessBody<T> {
  success: true;
  data: T;
}

/** 성공 응답에 `{ success: true, data }` 봉투를 씌운다. 204는 본문 없음 (TRD §7.2). */
@Injectable()
export class ResponseEnvelopeInterceptor<T> implements NestInterceptor<
  T,
  SuccessBody<T> | undefined
> {
  intercept(
    context: ExecutionContext,
    next: CallHandler<T>,
  ): Observable<SuccessBody<T> | undefined> {
    const res = context.switchToHttp().getResponse<Response>();
    return next
      .handle()
      .pipe(map((data) => (res.statusCode === 204 ? undefined : { success: true as const, data })));
  }
}
