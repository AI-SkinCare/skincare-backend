package com.aiskincare.backend.auth;

import com.aiskincare.backend.config.AppProperties;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * TRD §9.3 — 클라이언트 IP당 1분 고정 창 LOGIN_RATE_PER_MIN회. 성공·실패와 관계없이 모든 시도를 센다. 인스턴스가 1개라 메모리 카운터면 된다.
 */
@Component
public class LoginRateLimiter {
  private static final long WINDOW_MS = 60_000;
  private static final int SWEEP_THRESHOLD = 10_000;

  /** allowed=false면 retryAfterSec초 뒤에 다시 */
  public record Decision(boolean allowed, int retryAfterSec) {}

  private record Window(long startedAt, int count) {}

  private final Map<String, Window> windows = new ConcurrentHashMap<>();
  private final int limit;

  public LoginRateLimiter(AppProperties props) {
    this.limit = props.loginRatePerMin();
  }

  public Decision hit(String ip) {
    return hit(ip, System.currentTimeMillis());
  }

  public Decision hit(String ip, long now) {
    if (windows.size() > SWEEP_THRESHOLD) {
      windows.entrySet().removeIf(e -> now - e.getValue().startedAt() >= WINDOW_MS);
    }
    Window window =
        windows.compute(
            ip,
            (key, current) ->
                current == null || now - current.startedAt() >= WINDOW_MS
                    ? new Window(now, 1)
                    : new Window(current.startedAt(), current.count() + 1));
    if (window.count() > limit) {
      int retryAfter = (int) Math.max(1, (window.startedAt() + WINDOW_MS - now + 999) / 1000);
      return new Decision(false, retryAfter);
    }
    return new Decision(true, 0);
  }
}
