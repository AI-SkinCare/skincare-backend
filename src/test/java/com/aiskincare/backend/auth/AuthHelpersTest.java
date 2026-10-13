package com.aiskincare.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiskincare.backend.auth.dto.SignupRequest;
import com.aiskincare.backend.support.TestProps;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthHelpersTest {
  @Nested
  @DisplayName("refresh 쿠키 (TRD §9.1)")
  class Cookies {
    // MockHttpServletResponse는 Set-Cookie를 다시 조립하므로 헤더 문자열을 직접 본다 (실제 전송 값은 AuthIT가 확인)
    @Test
    void exactAttributes() {
      assertThat(RefreshCookies.header("tok", 1209600, true))
          .isEqualTo(
              "refresh_token=tok; HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth; Max-Age=1209600");
    }

    @Test
    @DisplayName("지울 때도 같은 Path, Max-Age=0. COOKIE_SECURE=false면 Secure 없음")
    void clear() {
      var response = new MockHttpServletResponse();
      RefreshCookies.clear(response, false);
      assertThat(response.getHeaders("Set-Cookie")).hasSize(1);
      assertThat(RefreshCookies.header("", 0, false))
          .isEqualTo("refresh_token=; HttpOnly; SameSite=Strict; Path=/api/v1/auth; Max-Age=0");
    }

    @Test
    @DisplayName("[앱 협의] 쿠키 우선, 없으면 본문")
    void cookieFirst() {
      var withCookie = new MockHttpServletRequest();
      withCookie.setCookies(new Cookie("refresh_token", "c"));
      assertThat(RefreshCookies.read(withCookie, "b")).isEqualTo("c");
      assertThat(RefreshCookies.read(new MockHttpServletRequest(), "b")).isEqualTo("b");
      var empty = new MockHttpServletRequest();
      empty.setCookies(new Cookie("refresh_token", ""));
      assertThat(RefreshCookies.read(empty, null)).isNull();
      assertThat(RefreshCookies.read(empty, "")).isNull();
    }
  }

  @Test
  @DisplayName("X-Client [앱 협의]: ios만 (공백·대소문자 무시)")
  void clientType() {
    assertThat(ClientType.isNative("ios")).isTrue();
    assertThat(ClientType.isNative(" iOS ")).isTrue();
    assertThat(ClientType.isNative("android")).isFalse();
    assertThat(ClientType.isNative(null)).isFalse();
  }

  @ParameterizedTest(name = "비밀번호 규칙 (TRD §9.2): {0} → {1}")
  @CsvSource(
      delimiter = '|',
      value = {
        "Skin!2026 | true",
        "skinskin1 | true",
        "skin!skin | true",
        "12345678! | true",
        "skinskin | false",
        "Sk1! | false",
        "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1 | false",
        "스킨케어1234 | false",
        "'skin 2026' | false"
      })
  void passwordPattern(String password, boolean valid) {
    assertThat(Pattern.matches(SignupRequest.PASSWORD, password)).isEqualTo(valid);
  }

  @Test
  @DisplayName("토큰 해시: SHA-256 hex 64자")
  void tokenHash() {
    assertThat(TokenHash.of("abc"))
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }

  @Nested
  @DisplayName("LoginRateLimiter (TRD §9.3)")
  class RateLimit {
    @Test
    @DisplayName("IP당 1분 고정 창 10회, 11번째부터 거절 + Retry-After")
    void window() {
      var limiter = new LoginRateLimiter(TestProps.defaults());
      long t0 = 1_000_000;
      for (int i = 0; i < 10; i++) assertThat(limiter.hit("1.1.1.1", t0 + i).allowed()).isTrue();
      assertThat(limiter.hit("1.1.1.1", t0 + 20_000))
          .isEqualTo(new LoginRateLimiter.Decision(false, 40));
      assertThat(limiter.hit("2.2.2.2", t0 + 20_000).allowed()).isTrue();
    }

    @Test
    void resetsAfterWindow() {
      var limiter = new LoginRateLimiter(TestProps.defaults());
      for (int i = 0; i < 11; i++) limiter.hit("1.1.1.1", 0);
      assertThat(limiter.hit("1.1.1.1", 60_000).allowed()).isTrue();
    }
  }

  @Nested
  @DisplayName("JWT (TRD §9.1)")
  class Jwt {
    private final JwtTokens tokens = new JwtTokens(TestProps.defaults());

    @Test
    void accessRoundTrip() {
      assertThat(tokens.verifyAccess(tokens.signAccess(42))).hasValue(42);
    }

    @Test
    @DisplayName("액세스·리프레시 키가 달라서 서로 검증되지 않는다")
    void keysAreSeparate() {
      assertThat(tokens.verifyRefresh(tokens.signAccess(42))).isEmpty();
      assertThat(tokens.verifyAccess(tokens.signRefresh(42))).isEmpty();
      assertThat(tokens.verifyRefresh(tokens.signRefresh(42))).hasValue(42);
    }

    @Test
    @DisplayName("리프레시 토큰은 같은 초에 발급해도 jti로 서로 다르다")
    void refreshUnique() {
      assertThat(tokens.signRefresh(1)).isNotEqualTo(tokens.signRefresh(1));
    }

    @Test
    @DisplayName("만료·위조·형식 오류는 거절")
    void rejects() {
      String expired = tokens.signAccess(1, Instant.now().minusSeconds(7200), Duration.ofHours(1));
      assertThat(tokens.verifyAccess(expired)).isEmpty();
      String valid = tokens.signAccess(1);
      String tampered =
          valid.substring(0, valid.length() - 2) + (valid.endsWith("AA") ? "BB" : "AA");
      assertThat(tokens.verifyAccess(tampered)).isEmpty();
      assertThat(tokens.verifyAccess("not-a-jwt")).isEmpty();
      assertThat(tokens.verifyAccess("")).isEmpty();
      // alg=none
      String[] parts = valid.split("\\.");
      String none =
          java.util.Base64.getUrlEncoder()
                  .withoutPadding()
                  .encodeToString("{\"alg\":\"none\"}".getBytes())
              + "."
              + parts[1]
              + ".";
      assertThat(tokens.verifyAccess(none)).isEmpty();
    }
  }
}
