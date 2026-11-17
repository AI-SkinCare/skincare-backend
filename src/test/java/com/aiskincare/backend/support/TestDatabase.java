package com.aiskincare.backend.support;

import com.aiskincare.backend.config.DataSourceConfig;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * 통합 테스트 DB. CI는 DATABASE_URL을 직접 준다. 로컬은 .env의 DATABASE_URL에서 DB 이름 뒤에 {@code _test}를 붙인다(개발 DB를
 * TRUNCATE하지 않도록). DB 이름에 test가 없으면 실행을 거부한다. 없으면 만든다 (테이블은 Flyway가).
 */
public final class TestDatabase {
  private TestDatabase() {}

  private static volatile String url;

  public static synchronized String url() {
    if (url != null) return url;
    String resolved = System.getenv("DATABASE_URL");
    if (resolved == null || resolved.isBlank()) resolved = fromDotEnv();
    if (resolved == null)
      resolved = "postgres://skincare:local-dev-password@localhost:5432/skincare_test";
    String dbName =
        URI.create(resolved.replaceFirst("^postgres(ql)?://", "postgresql://"))
            .getPath()
            .substring(1);
    if (!dbName.contains("test")) {
      throw new IllegalStateException(
          "통합 테스트는 이름에 test가 들어간 DB에서만 돈다 (현재: " + dbName + ") — 테이블을 TRUNCATE하기 때문");
    }
    ensureExists(resolved, dbName);
    url = resolved;
    return url;
  }

  private static String fromDotEnv() {
    Path env = Path.of(".env");
    if (!Files.exists(env)) return null;
    try (var reader = Files.newBufferedReader(env)) {
      Properties props = new Properties();
      props.load(reader);
      String dev = props.getProperty("DATABASE_URL");
      if (dev == null || dev.isBlank()) return null;
      int query = dev.indexOf('?');
      return query < 0 ? dev + "_test" : dev.substring(0, query) + "_test" + dev.substring(query);
    } catch (IOException e) {
      return null;
    }
  }

  private static void ensureExists(String databaseUrl, String dbName) {
    var target = DataSourceConfig.parse(databaseUrl);
    String adminUrl = target.url().replace("/" + dbName, "/postgres");
    try (var conn = DriverManager.getConnection(adminUrl, target.username(), target.password());
        var check = conn.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")) {
      check.setString(1, dbName);
      if (!check.executeQuery().next()) {
        try (var create = conn.createStatement()) {
          create.execute("CREATE DATABASE \"" + dbName.replace("\"", "") + "\"");
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("테스트 DB를 준비하지 못했습니다: " + e.getMessage(), e);
    }
  }
}
