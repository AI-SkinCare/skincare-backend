package com.aiskincare.backend.auth;

import com.aiskincare.backend.config.AppProperties;
import com.aiskincare.backend.config.Constants;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.OptionalLong;
import org.springframework.stereotype.Component;

/** TRD §9.1 — 액세스(1시간)·리프레시(14일) 모두 JWT HS256, 서로 다른 비밀키. 리프레시에는 랜덤 jti. */
@Component
public class JwtTokens {
  private static final SecureRandom RANDOM = new SecureRandom();
  private final byte[] accessSecret;
  private final byte[] refreshSecret;

  public JwtTokens(AppProperties props) {
    this.accessSecret = props.jwt().accessSecret().getBytes(StandardCharsets.UTF_8);
    this.refreshSecret = props.jwt().refreshSecret().getBytes(StandardCharsets.UTF_8);
  }

  public String signAccess(long userId) {
    return signAccess(userId, Instant.now(), Duration.ofSeconds(Constants.ACCESS_TOKEN_TTL_SEC));
  }

  /** 테스트에서 만료 토큰을 만들 때도 쓴다 */
  public String signAccess(long userId, Instant issuedAt, Duration ttl) {
    return sign(accessSecret, userId, issuedAt, ttl, null);
  }

  public String signRefresh(long userId) {
    byte[] jti = new byte[16];
    RANDOM.nextBytes(jti);
    return sign(
        refreshSecret,
        userId,
        Instant.now(),
        Duration.ofSeconds(Constants.REFRESH_TOKEN_TTL_SEC),
        HexFormat.of().formatHex(jti));
  }

  /** 서명·만료가 맞으면 userId. 없음·만료·위조는 빈 값 */
  public OptionalLong verifyAccess(String token) {
    return verify(accessSecret, token);
  }

  public OptionalLong verifyRefresh(String token) {
    return verify(refreshSecret, token);
  }

  private static String sign(
      byte[] secret, long userId, Instant issuedAt, Duration ttl, String jti) {
    try {
      JWTClaimsSet.Builder claims =
          new JWTClaimsSet.Builder()
              .subject(Long.toString(userId))
              .issueTime(Date.from(issuedAt))
              .expirationTime(Date.from(issuedAt.plus(ttl)));
      if (jti != null) claims.jwtID(jti);
      SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
      jwt.sign(new MACSigner(secret));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException("JWT 서명 실패", e);
    }
  }

  private static OptionalLong verify(byte[] secret, String token) {
    if (token == null || token.isBlank()) return OptionalLong.empty();
    try {
      SignedJWT jwt = SignedJWT.parse(token);
      if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())) return OptionalLong.empty();
      if (!jwt.verify(new MACVerifier(secret))) return OptionalLong.empty();
      JWTClaimsSet claims = jwt.getJWTClaimsSet();
      Date exp = claims.getExpirationTime();
      if (exp == null || !exp.toInstant().isAfter(Instant.now())) return OptionalLong.empty();
      long userId = Long.parseLong(claims.getSubject());
      return userId > 0 ? OptionalLong.of(userId) : OptionalLong.empty();
    } catch (ParseException | JOSEException | NumberFormatException | NullPointerException e) {
      return OptionalLong.empty();
    }
  }
}
