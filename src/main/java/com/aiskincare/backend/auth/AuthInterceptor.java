package com.aiskincare.backend.auth;

import com.aiskincare.backend.common.error.ApiException;
import com.aiskincare.backend.common.error.ErrorCode;
import com.aiskincare.backend.common.error.RateLimitReason;
import com.aiskincare.backend.common.web.AuthUser;
import com.aiskincare.backend.common.web.ClientIp;
import com.aiskincare.backend.common.web.PublicApi;
import com.aiskincare.backend.config.AppProperties;
import com.aiskincare.backend.users.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.OptionalLong;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 인증 (TRD §9.1). {@link PublicApi}가 아닌 우리 핸들러는 액세스 토큰이 필요하다. 토큰 없음·만료·위조·탈퇴한 사용자 모두 401 E1001 — 앱은
 * E1001에서만 refresh한다. 로그인 제한(TRD §9.3)도 여기서 — 본문 검증보다 먼저 모든 시도를 센다.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {
  private static final String OUR_PACKAGE = "com.aiskincare.backend";

  private final JwtTokens tokens;
  private final UserRepository users;
  private final LoginRateLimiter loginRateLimiter;
  private final AppProperties props;

  public AuthInterceptor(
      JwtTokens tokens,
      UserRepository users,
      LoginRateLimiter loginRateLimiter,
      AppProperties props) {
    this.tokens = tokens;
    this.users = users;
    this.loginRateLimiter = loginRateLimiter;
    this.props = props;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod method)
        || !method.getBeanType().getPackageName().startsWith(OUR_PACKAGE)) {
      return true;
    }
    if (method.hasMethodAnnotation(LoginRateLimited.class)) {
      var decision = loginRateLimiter.hit(ClientIp.of(request, props.trustProxy()));
      if (!decision.allowed()) {
        throw ApiException.rateLimited(RateLimitReason.LOGIN_RATE, decision.retryAfterSec());
      }
    }
    if (method.hasMethodAnnotation(PublicApi.class)
        || method.getBeanType().isAnnotationPresent(PublicApi.class)) {
      return true;
    }
    String header = request.getHeader("Authorization");
    String token =
        header != null && header.startsWith("Bearer ") ? header.substring(7).trim() : null;
    OptionalLong userId = tokens.verifyAccess(token);
    if (userId.isEmpty() || !users.existsById(userId.getAsLong())) {
      throw new ApiException(ErrorCode.E1001);
    }
    request.setAttribute(AuthUser.REQUEST_ATTRIBUTE, new AuthUser(userId.getAsLong()));
    MDC.put("userId", Long.toString(userId.getAsLong()));
    return true;
  }
}
