package com.aiskincare.backend.auth;

import com.aiskincare.backend.common.error.ApiException;
import com.aiskincare.backend.common.error.ErrorCode;
import com.aiskincare.backend.config.Constants;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 리프레시 토큰 — 해시 저장, 사용 시 교체, 재사용 감지 시 그 사용자 토큰 전부 폐기 (TRD §9.4). 쿠키로 오든 본문으로 오든 [앱 협의] 같은 경로로 검증한다.
 */
@Service
public class RefreshTokenService {
  private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

  public record Rotated(long userId, String token) {}

  private record TokenRow(
      long id, long userId, Instant expiresAt, Instant revokedAt, Long replacedBy) {}

  private static final class ReuseDetected extends RuntimeException {}

  private final JdbcClient jdbc;
  private final JwtTokens tokens;
  private final TransactionTemplate tx;

  public RefreshTokenService(JdbcClient jdbc, JwtTokens tokens, TransactionTemplate tx) {
    this.jdbc = jdbc;
    this.tokens = tokens;
    this.tx = tx;
  }

  /** 새 토큰 발급 + 해시 INSERT. 토큰과 행 id */
  public record Issued(String token, long id) {}

  public Issued issue(long userId) {
    String token = tokens.signRefresh(userId);
    long id =
        jdbc.sql(
                """
                INSERT INTO refresh_tokens (user_id, token_hash, expires_at)
                VALUES (:userId, :hash, now() + make_interval(secs => :ttl)) RETURNING id
                """)
            .param("userId", userId)
            .param("hash", TokenHash.of(token))
            .param("ttl", Constants.REFRESH_TOKEN_TTL_SEC)
            .query(Long.class)
            .single();
    return new Issued(token, id);
  }

  /**
   * 1. JWT 서명·만료 검증 실패 → E1001 2. 해시로 행 조회: 없음 → E1001 / revoked + replaced_by 있음 → 재사용 감지: 그 사용자
   * 토큰 전부 폐기 → E1001 / revoked(로그아웃)·만료 → E1001 3. 트랜잭션: 새 토큰 INSERT → 이전 토큰 revoked·replaced_by
   * 갱신. 영향 0행(동시에 다른 요청이 먼저 교체) = 재사용으로 본다.
   */
  public Rotated rotate(String token) {
    OptionalLong sub = tokens.verifyRefresh(token);
    if (sub.isEmpty()) throw new ApiException(ErrorCode.E1001);
    TokenRow row = find(TokenHash.of(token)).orElseThrow(() -> new ApiException(ErrorCode.E1001));
    if (row.userId() != sub.getAsLong()) throw new ApiException(ErrorCode.E1001);
    if (row.revokedAt() != null) {
      if (row.replacedBy() != null) handleReuse(row.userId());
      throw new ApiException(ErrorCode.E1001);
    }
    if (row.expiresAt().isBefore(Instant.now())) throw new ApiException(ErrorCode.E1001);

    try {
      Issued next =
          tx.execute(
              status -> {
                Issued issued = issue(row.userId());
                int updated =
                    jdbc.sql(
                            """
                            UPDATE refresh_tokens SET revoked_at = now(), replaced_by = :next
                            WHERE id = :id AND revoked_at IS NULL
                            """)
                        .param("next", issued.id())
                        .param("id", row.id())
                        .update();
                if (updated == 0) throw new ReuseDetected();
                return issued;
              });
      return new Rotated(row.userId(), next.token());
    } catch (ReuseDetected e) {
      handleReuse(row.userId());
      throw new ApiException(ErrorCode.E1001);
    }
  }

  /** 로그아웃: 그 사용자의 토큰이면 revoked_at만 (replaced_by는 NULL 유지) */
  public void revokeIfOwned(String token, long userId) {
    jdbc.sql(
            """
            UPDATE refresh_tokens SET revoked_at = now()
            WHERE token_hash = :hash AND user_id = :userId AND revoked_at IS NULL
            """)
        .param("hash", TokenHash.of(token))
        .param("userId", userId)
        .update();
  }

  /** 매일 03:30 KST — 만료 후 7일 지난 토큰 삭제 (TRD §8.5) */
  @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Seoul")
  public int cleanupExpired() {
    try {
      int deleted =
          jdbc.sql("DELETE FROM refresh_tokens WHERE expires_at < now() - interval '7 days'")
              .update();
      log.atInfo().addKeyValue("deleted", deleted).log("refresh_token_cleanup");
      return deleted;
    } catch (RuntimeException e) {
      log.error("refresh_token_cleanup_failed", e);
      return 0;
    }
  }

  private Optional<TokenRow> find(String hash) {
    return jdbc.sql(
            """
            SELECT id, user_id, expires_at, revoked_at, replaced_by
            FROM refresh_tokens WHERE token_hash = :hash
            """)
        .param("hash", hash)
        .query(
            (rs, i) -> {
              OffsetDateTime revoked = rs.getObject("revoked_at", OffsetDateTime.class);
              long replaced = rs.getLong("replaced_by");
              return new TokenRow(
                  rs.getLong("id"),
                  rs.getLong("user_id"),
                  rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                  revoked == null ? null : revoked.toInstant(),
                  rs.wasNull() ? null : replaced);
            })
        .optional();
  }

  private void handleReuse(long userId) {
    jdbc.sql(
            "UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = :userId AND revoked_at IS NULL")
        .param("userId", userId)
        .update();
    log.atWarn().addKeyValue("userId", userId).log("refresh_token_reuse_detected");
  }
}
