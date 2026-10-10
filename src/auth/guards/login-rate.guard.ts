import { CanActivate, ExecutionContext, Injectable } from '@nestjs/common';
import type { Request } from 'express';
import { ApiException } from '../../common/errors/api.exception';
import { getClientIp } from '../../common/utils/client-ip';
import { LoginRateLimiter } from '../login-rate-limiter';

/** 초과 시 429 E4004 (reason: login_rate, Retry-After = 창이 끝날 때까지 초) */
@Injectable()
export class LoginRateGuard implements CanActivate {
  constructor(private readonly limiter: LoginRateLimiter) {}

  canActivate(context: ExecutionContext): boolean {
    const req = context.switchToHttp().getRequest<Request>();
    const decision = this.limiter.hit(getClientIp(req));
    if (!decision.allowed) throw ApiException.rateLimited('login_rate', decision.retryAfterSec);
    return true;
  }
}
