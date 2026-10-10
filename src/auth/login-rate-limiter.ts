import { Inject, Injectable } from '@nestjs/common';
import { APP_CONFIG, AppConfig } from '../config/app-config';

const WINDOW_MS = 60_000;
const SWEEP_THRESHOLD = 10_000;

export type RateDecision = { allowed: true } | { allowed: false; retryAfterSec: number };

/**
 * TRD §9.3 — 클라이언트 IP당 1분 고정 창 LOGIN_RATE_PER_MIN회.
 * 성공·실패 관계없이 모든 시도를 센다. 인스턴스가 1개라 메모리 카운터면 된다.
 */
@Injectable()
export class LoginRateLimiter {
  private readonly windows = new Map<string, { startedAt: number; count: number }>();

  constructor(@Inject(APP_CONFIG) private readonly config: AppConfig) {}

  hit(ip: string, now = Date.now()): RateDecision {
    if (this.windows.size > SWEEP_THRESHOLD) this.sweep(now);
    let window = this.windows.get(ip);
    if (!window || now - window.startedAt >= WINDOW_MS) {
      window = { startedAt: now, count: 0 };
      this.windows.set(ip, window);
    }
    window.count += 1;
    if (window.count > this.config.loginRatePerMin) {
      return {
        allowed: false,
        retryAfterSec: Math.max(1, Math.ceil((window.startedAt + WINDOW_MS - now) / 1000)),
      };
    }
    return { allowed: true };
  }

  private sweep(now: number): void {
    for (const [ip, window] of this.windows) {
      if (now - window.startedAt >= WINDOW_MS) this.windows.delete(ip);
    }
  }
}
