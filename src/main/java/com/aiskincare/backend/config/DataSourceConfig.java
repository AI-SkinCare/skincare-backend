package com.aiskincare.backend.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import org.springframework.boot.flyway.autoconfigure.FlywayDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * DATABASE_URL({@code postgres://user:pass@host:5432/db})로 DataSource를 만든다 (TRD §6, compose·CI와 같은
 * 형식). 앱 연결은 풀 10, statement_timeout 5초. 마이그레이션(Flyway)은 타임아웃 없는 별도 연결 (TRD §8.2).
 */
@Configuration
public class DataSourceConfig {

  public record JdbcTarget(String url, String username, String password) {}

  public static JdbcTarget parse(String databaseUrl) {
    URI uri = URI.create(databaseUrl.replaceFirst("^postgres(ql)?://", "postgresql://"));
    String userInfo = uri.getRawUserInfo() == null ? "" : uri.getRawUserInfo();
    String[] credentials = userInfo.split(":", 2);
    String user = URLDecoder.decode(credentials[0], StandardCharsets.UTF_8);
    String password =
        credentials.length > 1 ? URLDecoder.decode(credentials[1], StandardCharsets.UTF_8) : "";
    int port = uri.getPort() == -1 ? 5432 : uri.getPort();
    String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
    return new JdbcTarget(
        "jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getRawPath() + query,
        user,
        password);
  }

  @Bean
  @Primary
  DataSource dataSource(AppProperties props) {
    JdbcTarget target = parse(props.databaseUrl());
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(target.url());
    config.setUsername(target.username());
    config.setPassword(target.password());
    config.setMaximumPoolSize(10);
    config.setConnectionInitSql(
        "SET statement_timeout = " + Constants.APP_STATEMENT_TIMEOUT_MS + "; SET TIME ZONE 'UTC'");
    config.setPoolName("app");
    return new HikariDataSource(config);
  }

  @Bean
  @FlywayDataSource
  DataSource flywayDataSource(AppProperties props) {
    JdbcTarget target = parse(props.databaseUrl());
    return new DriverManagerDataSource(target.url(), target.username(), target.password());
  }
}
