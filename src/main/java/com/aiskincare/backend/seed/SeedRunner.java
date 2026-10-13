package com.aiskincare.backend.seed;

import com.aiskincare.backend.auth.AuthService;
import com.aiskincare.backend.config.AppProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code --seed}로 실행하면: test01–test40@skincare.dev (비밀번호 SEED_PASSWORD), test01에 완료 분석 25건 (TRD
 * §8.4). production이면 거부. 다시 실행해도 안전하다(시드 계정을 지우고 다시 만든다).
 */
@Component
public class SeedRunner implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);
  private static final int ACCOUNT_COUNT = 40;
  private static final int TEST01_ANALYSES = 25;

  private final JdbcClient jdbc;
  private final JsonMapper json;
  private final TransactionTemplate tx;
  private final AppProperties props;

  public SeedRunner(JdbcClient jdbc, JsonMapper json, TransactionTemplate tx, AppProperties props) {
    this.jdbc = jdbc;
    this.json = json;
    this.tx = tx;
    this.props = props;
  }

  public static String seedEmail(int n) {
    return String.format("test%02d@skincare.dev", n);
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!args.containsOption("seed")) return;
    if (props.isProduction()) {
      throw new IllegalStateException("production에서는 시드를 실행하지 않습니다");
    }
    if (props.seedPassword() == null || props.seedPassword().isBlank()) {
      throw new IllegalStateException("SEED_PASSWORD가 필요합니다");
    }
    String hash = AuthService.PASSWORD_ENCODER.encode(props.seedPassword());
    tx.executeWithoutResult(
        status -> {
          jdbc.sql("DELETE FROM users WHERE email ~ '^test[0-9]{2}@skincare\\.dev$'").update();
          List<Long> ids = new ArrayList<>();
          for (int n = 1; n <= ACCOUNT_COUNT; n++) {
            ids.add(
                jdbc.sql(
                        """
                        INSERT INTO users (email, password_hash, nickname, terms_agreed_at,
                          privacy_agreed_at, image_consent_at, over14_confirmed_at)
                        VALUES (:email, :hash, :nickname, now(), now(), now(), now()) RETURNING id
                        """)
                    .param("email", seedEmail(n))
                    .param("hash", hash)
                    .param("nickname", String.format("테스트%02d", n))
                    .query(Long.class)
                    .single());
          }
          SeedData.insertDoneAnalyses(jdbc, json, ids.get(0), TEST01_ANALYSES, Instant.now());
        });
    log.atInfo()
        .addKeyValue("accounts", ACCOUNT_COUNT)
        .addKeyValue("test01Analyses", TEST01_ANALYSES)
        .log("seed_completed");
  }
}
