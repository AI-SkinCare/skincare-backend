import type { AppConfig } from '../config/app-config';
import { LoginRateLimiter } from './login-rate-limiter';

describe('LoginRateLimiter (TRD §9.3)', () => {
  const limiter = () => new LoginRateLimiter({ loginRatePerMin: 10 } as AppConfig);

  it('IP당 1분 고정 창 10회, 11번째부터 거절 + Retry-After', () => {
    const l = limiter();
    const t0 = 1_000_000;
    for (let i = 0; i < 10; i++) expect(l.hit('1.1.1.1', t0 + i).allowed).toBe(true);
    expect(l.hit('1.1.1.1', t0 + 20_000)).toEqual({ allowed: false, retryAfterSec: 40 });
    expect(l.hit('2.2.2.2', t0 + 20_000).allowed).toBe(true);
  });

  it('창이 끝나면 다시 허용', () => {
    const l = limiter();
    for (let i = 0; i < 11; i++) l.hit('1.1.1.1', 0);
    expect(l.hit('1.1.1.1', 60_000).allowed).toBe(true);
  });
});
