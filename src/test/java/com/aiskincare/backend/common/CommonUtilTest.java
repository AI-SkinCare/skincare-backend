package com.aiskincare.backend.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiskincare.backend.common.util.MaskEmail;
import com.aiskincare.backend.common.util.TimeUtil;
import com.aiskincare.backend.common.util.Ulid;
import com.aiskincare.backend.common.web.ClientIp;
import com.aiskincare.backend.common.web.RequestIdFilter;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

class CommonUtilTest {
  @Nested
  @DisplayName("time (TRD §7.4)")
  class Time {
    @Test
    void toIsoUtcDropsMillis() {
      assertThat(TimeUtil.toIsoUtc(Instant.parse("2026-10-15T09:12:33.987Z")))
          .isEqualTo("2026-10-15T09:12:33Z");
      assertThat(TimeUtil.toIsoUtc(null)).isNull();
    }

    @Test
    void kstDateIsUtcPlus9() {
      assertThat(TimeUtil.kstDate(Instant.parse("2026-10-15T14:59:59Z"))).isEqualTo("2026-10-15");
      assertThat(TimeUtil.kstDate(Instant.parse("2026-10-15T15:00:00Z"))).isEqualTo("2026-10-16");
    }

    @Test
    @DisplayName("일일 한도 기준: KST 00:00 = 전날 UTC 15:00")
    void startOfKstDay() {
      assertThat(TimeUtil.startOfKstDay(Instant.parse("2026-10-15T03:00:00Z")))
          .isEqualTo(Instant.parse("2026-10-14T15:00:00Z"));
      assertThat(TimeUtil.startOfKstDay(Instant.parse("2026-10-15T15:00:00Z")))
          .isEqualTo(Instant.parse("2026-10-15T15:00:00Z"));
    }

    @Test
    @DisplayName("Retry-After = 다음 KST 00:00까지 초")
    void secondsUntilMidnight() {
      assertThat(TimeUtil.secondsUntilNextKstMidnight(Instant.parse("2026-10-15T14:59:00Z")))
          .isEqualTo(60);
      assertThat(TimeUtil.secondsUntilNextKstMidnight(Instant.parse("2026-10-15T15:00:00Z")))
          .isEqualTo(86400);
    }
  }

  @Nested
  @DisplayName("maskEmail (TRD §7.6)")
  class Mask {
    @Test
    void firstCharAndDomain() {
      assertThat(MaskEmail.mask("dohee@gmail.com")).isEqualTo("d***@gmail.com");
      assertThat(MaskEmail.mask("a@b.co")).isEqualTo("a***@b.co");
    }

    @Test
    void notEmail() {
      assertThat(MaskEmail.mask("nope")).isEqualTo("***");
    }
  }

  @Nested
  @DisplayName("ULID")
  class UlidTest {
    @Test
    void generatedIsValidAndTimeOrdered() {
      String a = Ulid.generate(1_760_000_000_000L);
      String b = Ulid.generate(1_760_000_000_001L);
      assertThat(Ulid.isValid(a)).isTrue();
      assertThat(a.substring(0, 10)).isLessThan(b.substring(0, 10));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
        strings = {
          "01J9Z3K5V8Q2M4N6P8R0S2T4V",
          "01J9Z3K5V8Q2M4N6P8R0S2T4VI",
          "01j9z3k5v8q2m4n6p8r0s2t4v6",
          "123"
        })
    void invalid(String value) {
      assertThat(Ulid.isValid(value)).isFalse();
    }
  }

  @Nested
  @DisplayName("clientIp (TRD §15.2)")
  class ClientIpTest {
    @Test
    void cfConnectingIpFirst() {
      var request = new MockHttpServletRequest();
      request.setRemoteAddr("10.0.0.1");
      request.addHeader("CF-Connecting-IP", " 203.0.113.7 ");
      assertThat(ClientIp.of(request, false)).isEqualTo("203.0.113.7");
    }

    @Test
    void forwardedOnlyWhenTrusted() {
      var request = new MockHttpServletRequest();
      request.setRemoteAddr("10.0.0.1");
      request.addHeader("X-Forwarded-For", "198.51.100.1, 10.0.0.2");
      assertThat(ClientIp.of(request, false)).isEqualTo("10.0.0.1");
      assertThat(ClientIp.of(request, true)).isEqualTo("198.51.100.1");
    }
  }

  @Nested
  @DisplayName("X-Request-Id (TRD §7.5)")
  class RequestId {
    @Test
    void keepsValid() {
      assertThat(RequestIdFilter.resolve("ios-01J9Z3K5.abc_1")).isEqualTo("ios-01J9Z3K5.abc_1");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"has space", "한글", "a,b"})
    void replacesInvalidWithUuid(String value) {
      assertThat(RequestIdFilter.resolve(value)).matches("^[0-9a-f-]{36}$");
    }

    @Test
    void tooLong() {
      assertThat(RequestIdFilter.resolve("a".repeat(65))).matches("^[0-9a-f-]{36}$");
    }
  }
}
