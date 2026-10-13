package com.aiskincare.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiskincare.backend.seed.SeedData;
import com.aiskincare.backend.support.Factories;
import com.aiskincare.backend.support.Factories.LoggedIn;
import com.aiskincare.backend.support.IntegrationTestBase;
import com.aiskincare.backend.support.TestHttp;
import com.aiskincare.backend.support.TestHttp.Res;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 인증 (TRD §9) */
class AuthIT extends IntegrationTestBase {
  private static final Map<String, Object> AGREEMENTS =
      Map.of("terms", true, "privacy", true, "imageProcessing", true, "over14", true);
  private static final String COOKIE_ATTRS =
      "HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth; Max-Age=1209600";

  @Autowired JwtTokens jwt;
  @Autowired RefreshTokenService refreshTokens;

  private Res signup(String email, String password, Map<String, Object> agreements) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("email", email);
    body.put("password", password);
    body.put("nickname", " 우주 ");
    body.put("agreements", agreements);
    return http.post("/api/v1/auth/signup").json(body).send();
  }

  private record TokenRow(long id, Instant revokedAt, Long replacedBy) {}

  private List<TokenRow> refreshTokensOf(long userId) {
    return jdbc.sql(
            "SELECT id, revoked_at, replaced_by FROM refresh_tokens WHERE user_id = :u ORDER BY id")
        .param("u", userId)
        .query(
            (rs, i) ->
                new TokenRow(
                    rs.getLong("id"),
                    rs.getTimestamp("revoked_at") == null
                        ? null
                        : rs.getTimestamp("revoked_at").toInstant(),
                    (Long) rs.getObject("replaced_by")))
        .list();
  }

  private Res refreshWithCookie(String token) {
    return http.post("/api/v1/auth/refresh").header("Cookie", "refresh_token=" + token).send();
  }

  private Res refreshWithBody(String token, boolean ios) {
    var req = http.post("/api/v1/auth/refresh").json(Map.of("refreshToken", token));
    if (ios) req.header("X-Client", "ios");
    return req.send();
  }

  @Nested
  class Signup {
    @Test
    @DisplayName("TC-AUTH-01: 201, 사용자 행과 동의 시각 4개 기록, 이메일 소문자 정규화")
    void created() {
      Res res = signup("  New.User@Example.COM ", Factories.PASSWORD, AGREEMENTS);
      assertThat(res.status()).isEqualTo(201);
      assertThat(res.body().get("success").booleanValue()).isTrue();
      long userId = res.data().get("userId").longValue();
      assertThat(res.data().size()).isEqualTo(1);
      var row =
          jdbc.sql(
                  """
                  SELECT email, nickname, terms_agreed_at IS NOT NULL AND privacy_agreed_at IS NOT NULL
                     AND image_consent_at IS NOT NULL AND over14_confirmed_at IS NOT NULL AS agreed
                  FROM users WHERE id = :id
                  """)
              .param("id", userId)
              .query()
              .singleRow();
      assertThat(row)
          .containsEntry("email", "new.user@example.com")
          .containsEntry("nickname", "우주");
      assertThat(row.get("agreed")).isEqualTo(true);
    }

    @Test
    @DisplayName("TC-SEC-02: password_hash가 $2b$12$로 시작")
    void bcryptCost() {
      long userId =
          signup(Factories.uniqueEmail(), Factories.PASSWORD, AGREEMENTS)
              .data()
              .get("userId")
              .longValue();
      String hash =
          jdbc.sql("SELECT password_hash FROM users WHERE id = :id")
              .param("id", userId)
              .query(String.class)
              .single();
      assertThat(hash).startsWith("$2b$12$");
    }

    @Test
    @DisplayName("TC-AUTH-02: 동의 하나라도 누락 → 422 E4002, 행 없음")
    void agreementMissing() {
      Map<String, Object> agreements = new LinkedHashMap<>(AGREEMENTS);
      agreements.put("over14", false);
      Res res = signup(Factories.uniqueEmail(), Factories.PASSWORD, agreements);
      assertThat(res.status()).isEqualTo(422);
      assertThat(res.errorCode()).isEqualTo("E4002");
      assertThat(res.body().get("error").get("detail").get("fields").toString())
          .isEqualTo("[{\"field\":\"agreements.over14\",\"reason\":\"invalid_value\"}]");
      assertThat(count("users")).isZero();
    }

    @Test
    @DisplayName("TC-AUTH-03: 대소문자만 다른 이메일 → 409 E1004")
    void duplicate() {
      assertThat(signup("dup@example.com", Factories.PASSWORD, AGREEMENTS).status()).isEqualTo(201);
      Res res = signup("DUP@Example.com", Factories.PASSWORD, AGREEMENTS);
      assertThat(res.status()).isEqualTo(409);
      assertThat(res.errorCode()).isEqualTo("E1004");
    }

    @Test
    @DisplayName("비밀번호 규칙 위반 → 422 E4002 (detail에 password)")
    void weakPassword() {
      Res res = signup(Factories.uniqueEmail(), "onlyletters", AGREEMENTS);
      assertThat(res.status()).isEqualTo(422);
      assertThat(res.body().get("error").get("detail").get("fields").toString())
          .contains("{\"field\":\"password\",\"reason\":\"invalid_format\"}");
    }
  }

  @Nested
  class Login {
    @Test
    @DisplayName("TC-AUTH-04: 200, 액세스 토큰, httpOnly 쿠키 속성 전부. X-Client 없으면 본문에 refreshToken 없음")
    void ok() {
      var user = factories.createUser();
      Res res =
          http.post("/api/v1/auth/login")
              .header("CF-Connecting-IP", Factories.randomIp())
              .json(Map.of("email", user.email(), "password", Factories.PASSWORD))
              .send();
      assertThat(res.status()).isEqualTo(200);
      List<String> keys = new ArrayList<>();
      res.data().propertyNames().forEach(keys::add);
      assertThat(keys).containsExactly("accessToken", "expiresIn", "user");
      assertThat(res.data().get("expiresIn").intValue()).isEqualTo(3600);
      assertThat(res.data().get("user").toString())
          .isEqualTo(
              "{\"id\":" + user.id() + ",\"email\":\"" + user.email() + "\",\"nickname\":\"테스터\"}");
      assertThat(res.headers("Set-Cookie"))
          .containsExactly("refresh_token=" + Factories.refreshCookieOf(res) + "; " + COOKIE_ATTRS);
    }

    @Test
    @DisplayName("비밀번호 불일치·없는 사용자 모두 401 E1003 (E1001과 섞지 않음), 쿠키 없음")
    void wrongCredentials() {
      var user = factories.createUser();
      for (var body :
          List.of(
              Map.of("email", user.email(), "password", "Wrong!2026"),
              Map.of("email", "ghost@example.com", "password", Factories.PASSWORD))) {
        Res res =
            http.post("/api/v1/auth/login")
                .header("CF-Connecting-IP", Factories.randomIp())
                .json(body)
                .send();
        assertThat(res.status()).isEqualTo(401);
        assertThat(res.errorCode()).isEqualTo("E1003");
        assertThat(res.headers("Set-Cookie")).isEmpty();
      }
    }

    @Test
    @DisplayName(
        "TC-SEC-04: 같은 CF-Connecting-IP 11번째 → 429 E4004 login_rate + Retry-After, 다른 IP는 정상")
    void rateLimit() {
      String ip = Factories.randomIp();
      Map<String, String> body = Map.of("email", "x@example.com", "password", "Wrong!2026");
      for (int i = 0; i < 10; i++) {
        assertThat(
                http.post("/api/v1/auth/login")
                    .header("CF-Connecting-IP", ip)
                    .json(body)
                    .send()
                    .status())
            .isEqualTo(401);
      }
      Res blocked =
          http.post("/api/v1/auth/login").header("CF-Connecting-IP", ip).json(body).send();
      assertThat(blocked.status()).isEqualTo(429);
      assertThat(blocked.errorCode()).isEqualTo("E4004");
      assertThat(blocked.body().get("error").get("detail").get("reason").stringValue())
          .isEqualTo("login_rate");
      assertThat(Integer.parseInt(blocked.header("Retry-After"))).isPositive();
      assertThat(
              http.post("/api/v1/auth/login")
                  .header("CF-Connecting-IP", Factories.randomIp())
                  .json(body)
                  .send()
                  .status())
          .isEqualTo(401);
    }
  }

  @Nested
  class RefreshAndLogout {
    @Test
    @DisplayName("TC-AUTH-05: 만료 토큰 → 401 E1001, refresh 후 원 요청 성공")
    void expiredThenRefresh() {
      LoggedIn me = factories.userWithToken();
      String expired =
          jwt.signAccess(
              me.user().id(), Instant.now().minus(Duration.ofHours(2)), Duration.ofHours(1));
      Res res = http.get("/api/v1/users/me").bearer(expired).send();
      assertThat(res.status()).isEqualTo(401);
      assertThat(res.errorCode()).isEqualTo("E1001");

      Res refreshed = refreshWithCookie(me.refreshCookie());
      assertThat(refreshed.status()).isEqualTo(200);
      assertThat(
              http.get("/api/v1/users/me")
                  .bearer(refreshed.data().get("accessToken").stringValue())
                  .send()
                  .status())
          .isEqualTo(200);
    }

    @Test
    @DisplayName("위조·형식 오류 토큰, 토큰 없음, 삭제된 사용자 → 401 E1001")
    void invalidTokens() {
      for (String header : new String[] {"Bearer abc.def.ghi", "Basic xyz", null}) {
        var req = http.get("/api/v1/users/me");
        if (header != null) req.header("Authorization", header);
        Res res = req.send();
        assertThat(res.status()).isEqualTo(401);
        assertThat(res.errorCode()).isEqualTo("E1001");
      }
      assertThat(http.get("/api/v1/users/me").bearer(jwt.signAccess(999_999)).send().errorCode())
          .isEqualTo("E1001");
    }

    @Test
    @DisplayName("refresh는 새 토큰으로 교체하고 이전 토큰에 replaced_by를 남긴다")
    void rotation() {
      LoggedIn me = factories.userWithToken();
      Res res = refreshWithCookie(me.refreshCookie());
      assertThat(res.status()).isEqualTo(200);
      assertThat(Factories.refreshCookieOf(res)).isNotEqualTo(me.refreshCookie());
      var rows = refreshTokensOf(me.user().id());
      assertThat(rows).hasSize(2);
      assertThat(rows.get(0).revokedAt()).isNotNull();
      assertThat(rows.get(0).replacedBy()).isEqualTo(rows.get(1).id());
    }

    @Test
    @DisplayName("(추가) 교체된 토큰 재사용 → 401 E1001 + 그 사용자 토큰 전부 폐기")
    void reuseDetection() {
      LoggedIn me = factories.userWithToken();
      Res rotated = refreshWithCookie(me.refreshCookie());
      Res reuse = refreshWithCookie(me.refreshCookie());
      assertThat(reuse.status()).isEqualTo(401);
      assertThat(reuse.errorCode()).isEqualTo("E1001");
      assertThat(reuse.header("Set-Cookie")).contains("Max-Age=0");
      // 새로 받은 토큰도 폐기됐다
      assertThat(refreshWithCookie(Factories.refreshCookieOf(rotated)).status()).isEqualTo(401);
      assertThat(refreshTokensOf(me.user().id())).allMatch(r -> r.revokedAt() != null);
    }

    @Test
    @DisplayName("TC-AUTH-06: 로그아웃 204 + 쿠키 삭제, 이전 토큰으로 refresh → 401 E1001")
    void logout() {
      LoggedIn me = factories.userWithToken();
      Res res =
          http.post("/api/v1/auth/logout")
              .bearer(me.accessToken())
              .header("Cookie", "refresh_token=" + me.refreshCookie())
              .send();
      assertThat(res.status()).isEqualTo(204);
      assertThat(res.raw()).isEmpty();
      assertThat(res.headers("Set-Cookie"))
          .containsExactly(
              "refresh_token=; HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth; Max-Age=0");
      Res after = refreshWithCookie(me.refreshCookie());
      assertThat(after.status()).isEqualTo(401);
      assertThat(after.errorCode()).isEqualTo("E1001");
      // 로그아웃 폐기는 재사용이 아니다 → replaced_by NULL
      assertThat(refreshTokensOf(me.user().id()).get(0).replacedBy()).isNull();
    }

    @Test
    @DisplayName("쿠키 없이 로그아웃해도 204, 액세스 토큰 없이 로그아웃은 401 E1001")
    void logoutWithoutCookie() {
      LoggedIn me = factories.userWithToken();
      assertThat(http.post("/api/v1/auth/logout").bearer(me.accessToken()).send().status())
          .isEqualTo(204);
      assertThat(http.post("/api/v1/auth/logout").send().errorCode()).isEqualTo("E1001");
    }

    @Test
    @DisplayName("refresh 쿠키·본문 모두 없음 → 401 E1001 + 쿠키 삭제 헤더")
    void refreshWithoutToken() {
      Res res = http.post("/api/v1/auth/refresh").send();
      assertThat(res.status()).isEqualTo(401);
      assertThat(res.errorCode()).isEqualTo("E1001");
      assertThat(res.header("Set-Cookie")).contains("Max-Age=0");
    }
  }

  @Nested
  @DisplayName("[앱 협의] X-Client: ios — 본문 refreshToken (TRD §9.1)")
  class NativeClient {
    private record IosLogin(Factories.User user, Res res, String refreshToken) {}

    private IosLogin iosLogin() {
      var user = factories.createUser();
      Res res =
          http.post("/api/v1/auth/login")
              .header("X-Client", "ios")
              .header("CF-Connecting-IP", Factories.randomIp())
              .json(Map.of("email", user.email(), "password", Factories.PASSWORD))
              .send();
      assertThat(res.status()).isEqualTo(200);
      return new IosLogin(user, res, res.data().get("refreshToken").stringValue());
    }

    @Test
    @DisplayName("로그인 응답 data에 refreshToken (쿠키와 같은 값), 쿠키도 그대로")
    void loginBody() {
      IosLogin login = iosLogin();
      List<String> keys = new ArrayList<>();
      login.res().data().propertyNames().forEach(keys::add);
      assertThat(keys).containsExactly("accessToken", "expiresIn", "refreshToken", "user");
      assertThat(login.refreshToken()).isEqualTo(Factories.refreshCookieOf(login.res()));
    }

    @Test
    @DisplayName("쿠키 없이 본문 토큰으로 refresh → 새 refreshToken, 이전 본문 토큰 재사용 → 401 + 전부 폐기")
    void bodyRotation() {
      IosLogin login = iosLogin();
      Res res = refreshWithBody(login.refreshToken(), true);
      assertThat(res.status()).isEqualTo(200);
      String next = res.data().get("refreshToken").stringValue();
      assertThat(next).isNotBlank().isNotEqualTo(login.refreshToken());
      assertThat(refreshWithBody(login.refreshToken(), false).status()).isEqualTo(401);
      assertThat(refreshWithBody(next, false).status()).isEqualTo(401);
      assertThat(refreshTokensOf(login.user().id())).allMatch(r -> r.revokedAt() != null);
    }

    @Test
    @DisplayName("X-Client 없이 본문 토큰으로 refresh하면 동작하되 응답에 refreshToken 없음")
    void bodyWithoutHeader() {
      Res res = refreshWithBody(iosLogin().refreshToken(), false);
      assertThat(res.status()).isEqualTo(200);
      assertThat(res.data().has("refreshToken")).isFalse();
    }

    @Test
    @DisplayName("쿠키·본문 둘 다 있으면 쿠키 우선")
    void cookieWins() {
      IosLogin a = iosLogin();
      IosLogin b = iosLogin();
      Res res =
          http.post("/api/v1/auth/refresh")
              .header("Cookie", "refresh_token=" + a.refreshToken())
              .json(Map.of("refreshToken", b.refreshToken()))
              .send();
      assertThat(res.status()).isEqualTo(200);
      assertThat(res.data().get("user").get("id").longValue()).isEqualTo(a.user().id());
    }

    @Test
    @DisplayName("본문 토큰으로 로그아웃 → 그 토큰 폐기")
    void bodyLogout() {
      IosLogin login = iosLogin();
      Res res =
          http.post("/api/v1/auth/logout")
              .bearer(login.res().data().get("accessToken").stringValue())
              .json(Map.of("refreshToken", login.refreshToken()))
              .send();
      assertThat(res.status()).isEqualTo(204);
      assertThat(refreshWithBody(login.refreshToken(), false).status()).isEqualTo(401);
    }

    @Test
    @DisplayName("본문 refreshToken이 문자열이 아니면 422 E4002")
    void bodyNotString() {
      Res res = http.post("/api/v1/auth/refresh").json(Map.of("refreshToken", 123)).send();
      assertThat(res.status()).isEqualTo(422);
      assertThat(res.errorCode()).isEqualTo("E4002");
    }
  }

  @Test
  @DisplayName("토큰 정리 배치(TRD §8.5): 만료 후 7일 지난 리프레시 토큰만 삭제")
  void cleanup() {
    LoggedIn me = factories.userWithToken();
    for (var entry : Map.of("a", "-8 days", "b", "-6 days").entrySet()) {
      jdbc.sql(
              "INSERT INTO refresh_tokens (user_id, token_hash, expires_at)"
                  + " VALUES (:u, :h, now() + CAST(:i AS interval))")
          .param("u", me.user().id())
          .param("h", (entry.getKey() + "0".repeat(64)).substring(0, 64))
          .param("i", entry.getValue())
          .update();
    }
    assertThat(refreshTokens.cleanupExpired()).isEqualTo(1);
    // 로그인 때 받은 토큰 + 6일 지난 토큰
    assertThat(refreshTokensOf(me.user().id())).hasSize(2);
  }

  @Nested
  class Users {
    @Test
    @DisplayName("GET /users/me")
    void me() {
      LoggedIn me = factories.userWithToken();
      SeedData.insertDoneAnalyses(jdbc, TestHttp.json(), me.user().id(), 2, Instant.now());
      Res res = http.get("/api/v1/users/me").bearer(me.accessToken()).send();
      assertThat(res.status()).isEqualTo(200);
      assertThat(res.data().get("id").longValue()).isEqualTo(me.user().id());
      assertThat(res.data().get("email").stringValue()).isEqualTo(me.user().email());
      assertThat(res.data().get("nickname").stringValue()).isEqualTo("테스터");
      assertThat(res.data().get("createdAt").stringValue())
          .matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$");
      assertThat(res.data().get("analysisCount").intValue()).isEqualTo(2);
    }

    @Test
    @DisplayName("TC-AUTH-07: 분석 3건 계정 탈퇴 → 6개 테이블 모두 0건, 남은 토큰은 401 E1001")
    void withdraw() {
      LoggedIn me = factories.userWithToken();
      SeedData.insertDoneAnalyses(jdbc, TestHttp.json(), me.user().id(), 3, Instant.now());
      Res res = http.delete("/api/v1/users/me").bearer(me.accessToken()).send();
      assertThat(res.status()).isEqualTo(204);
      assertThat(res.header("Set-Cookie")).contains("Max-Age=0");
      for (String table :
          List.of(
              "users",
              "refresh_tokens",
              "analyses",
              "skin_metrics",
              "surveys",
              "recommendations")) {
        assertThat(count(table)).as(table).isZero();
      }
      Res after = http.get("/api/v1/users/me").bearer(me.accessToken()).send();
      assertThat(after.status()).isEqualTo(401);
      assertThat(after.errorCode()).isEqualTo("E1001");
    }

    @Test
    @DisplayName("다른 사용자 데이터는 보존 (탈퇴 범위 확인)")
    void otherUserKept() {
      LoggedIn a = factories.userWithToken();
      LoggedIn b = factories.userWithToken();
      assertThat(http.delete("/api/v1/users/me").bearer(a.accessToken()).send().status())
          .isEqualTo(204);
      assertThat(http.get("/api/v1/users/me").bearer(b.accessToken()).send().status())
          .isEqualTo(200);
    }
  }
}
