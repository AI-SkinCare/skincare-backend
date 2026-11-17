package com.aiskincare.backend.users;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

  public record UserRow(
      long id, String email, String passwordHash, String nickname, Instant createdAt) {}

  private final JdbcClient jdbc;

  public UserRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** 동의 시각 4개 = 현재 시각 (TRD §9.2) */
  public long insert(String email, String passwordHash, String nickname) {
    return jdbc.sql(
            """
            INSERT INTO users (email, password_hash, nickname, terms_agreed_at, privacy_agreed_at,
                               image_consent_at, over14_confirmed_at)
            VALUES (:email, :hash, :nickname, now(), now(), now(), now())
            RETURNING id
            """)
        .param("email", email)
        .param("hash", passwordHash)
        .param("nickname", nickname)
        .query(Long.class)
        .single();
  }

  public boolean existsByEmail(String email) {
    return jdbc.sql("SELECT EXISTS(SELECT 1 FROM users WHERE email = :email)")
        .param("email", email)
        .query(Boolean.class)
        .single();
  }

  public boolean existsById(long id) {
    return jdbc.sql("SELECT EXISTS(SELECT 1 FROM users WHERE id = :id)")
        .param("id", id)
        .query(Boolean.class)
        .single();
  }

  public Optional<UserRow> findByEmail(String email) {
    return jdbc.sql(
            "SELECT id, email, password_hash, nickname, created_at FROM users WHERE email = :email")
        .param("email", email)
        .query(UserRepository::map)
        .optional();
  }

  public Optional<UserRow> findById(long id) {
    return jdbc.sql(
            "SELECT id, email, password_hash, nickname, created_at FROM users WHERE id = :id")
        .param("id", id)
        .query(UserRepository::map)
        .optional();
  }

  public long countAnalyses(long userId) {
    return jdbc.sql("SELECT COUNT(*) FROM analyses WHERE user_id = :id")
        .param("id", userId)
        .query(Long.class)
        .single();
  }

  /** CASCADE로 분석·지표·설문·추천·리프레시 토큰 전부 즉시 삭제 (TRD §9.7) */
  public void deleteById(long id) {
    jdbc.sql("DELETE FROM users WHERE id = :id").param("id", id).update();
  }

  private static UserRow map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    return new UserRow(
        rs.getLong("id"),
        rs.getString("email"),
        rs.getString("password_hash"),
        rs.getString("nickname"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }
}
