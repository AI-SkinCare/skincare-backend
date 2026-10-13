package com.aiskincare.backend.support;

import com.aiskincare.backend.support.TestHttp.Multipart;
import com.aiskincare.backend.support.TestHttp.Res;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** 사용자·로그인·제출 도우미 */
public final class Factories {
  public static final String PASSWORD = "Skin!2026";

  /** 가입 API를 거치지 않는 사용자는 bcrypt cost 4로 빠르게 */
  private static final String FAST_HASH =
      new BCryptPasswordEncoder(BCryptPasswordEncoder.BCryptVersion.$2B, 4).encode(PASSWORD);

  private static final AtomicInteger SEQ = new AtomicInteger();
  public static final byte[] FACE = TestImages.png(1080, 1440);

  private final TestHttp http;
  private final JdbcClient jdbc;

  public Factories(TestHttp http, JdbcClient jdbc) {
    this.http = http;
    this.jdbc = jdbc;
  }

  public record User(long id, String email) {}

  public record LoggedIn(User user, String accessToken, String refreshCookie) {}

  public static String uniqueEmail() {
    return "user" + System.nanoTime() + SEQ.incrementAndGet() + "@example.com";
  }

  /** 로그인 제한(IP당 분당 10회)에 걸리지 않게 호출마다 다른 IP */
  public static String randomIp() {
    var r = ThreadLocalRandom.current();
    return "198.51." + r.nextInt(0, 255) + "." + r.nextInt(1, 255);
  }

  public User createUser() {
    String email = uniqueEmail();
    long id =
        jdbc.sql(
                """
                INSERT INTO users (email, password_hash, nickname, terms_agreed_at, privacy_agreed_at,
                                   image_consent_at, over14_confirmed_at)
                VALUES (:email, :hash, '테스터', now(), now(), now(), now()) RETURNING id
                """)
            .param("email", email)
            .param("hash", FAST_HASH)
            .query(Long.class)
            .single();
    return new User(id, email);
  }

  public LoggedIn login(User user) {
    Res res =
        http.post("/api/v1/auth/login")
            .header("CF-Connecting-IP", randomIp())
            .json(Map.of("email", user.email(), "password", PASSWORD))
            .send();
    if (res.status() != 200) throw new AssertionError("로그인 실패: " + res.raw());
    return new LoggedIn(user, res.data().get("accessToken").stringValue(), refreshCookieOf(res));
  }

  public LoggedIn userWithToken() {
    return login(createUser());
  }

  public static String refreshCookieOf(Res res) {
    for (String cookie : res.headers("Set-Cookie")) {
      if (cookie.startsWith("refresh_token="))
        return cookie.split(";")[0].substring("refresh_token=".length());
    }
    throw new AssertionError("refresh_token 쿠키가 없다");
  }

  public static Map<String, Object> defaultSurvey() {
    Map<String, Object> survey = new LinkedHashMap<>();
    survey.put("gender", "female");
    survey.put("ageBand", "20s");
    survey.put("skinType", "combination");
    survey.put("sleepHours", 6);
    survey.put("waterIntakeLevel", "low");
    survey.put("sunExposureLevel", "high");
    survey.put("stressLevel", "medium");
    survey.put("concerns", List.of("pore", "pigmentation"));
    survey.put("currentProducts", List.of("토너", "수분크림"));
    survey.put("note", "여름에 번들거림이 심해요");
    return survey;
  }

  /** POST /analyses (multipart). surveyJson이 null이면 survey 필드를 빼고, deviceType이 null이면 빼고 */
  public Res submit(
      LoggedIn who,
      byte[] image,
      String filename,
      String surveyJson,
      String deviceType,
      String scenario) {
    Multipart form = new Multipart();
    if (image != null) form.file("image", filename, "image/png", image);
    if (surveyJson != null) form.field("survey", surveyJson);
    if (deviceType != null) form.field("deviceType", deviceType);
    var req = http.post("/api/v1/analyses").bearer(who.accessToken()).multipart(form);
    if (scenario != null) req.header("X-Fixture-Scenario", scenario);
    return req.send();
  }

  public Res submit(LoggedIn who) {
    return submit(
        who,
        FACE,
        "face.png",
        TestHttp.json().writeValueAsString(defaultSurvey()),
        "smartphone",
        null);
  }

  public Res submit(LoggedIn who, String scenario) {
    return submit(
        who,
        FACE,
        "face.png",
        TestHttp.json().writeValueAsString(defaultSurvey()),
        "smartphone",
        scenario);
  }

  public String submitOk(LoggedIn who, String scenario) {
    Res res = submit(who, scenario);
    if (res.status() != 202) throw new AssertionError("제출 실패: " + res.raw());
    return res.data().get("analysisId").stringValue();
  }

  public String submitOk(LoggedIn who) {
    return submitOk(who, null);
  }
}
