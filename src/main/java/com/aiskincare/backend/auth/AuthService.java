package com.aiskincare.backend.auth;

import com.aiskincare.backend.auth.dto.LoginRequest;
import com.aiskincare.backend.auth.dto.SignupRequest;
import com.aiskincare.backend.common.error.ApiException;
import com.aiskincare.backend.common.error.ErrorCode;
import com.aiskincare.backend.common.util.MaskEmail;
import com.aiskincare.backend.config.Constants;
import com.aiskincare.backend.users.UserRepository;
import com.aiskincare.backend.users.UserRepository.UserRow;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
  private static final Logger log = LoggerFactory.getLogger(AuthService.class);

  /** 로그인·재발급 결과. refreshToken은 쿠키와 (X-Client: ios면) 본문에 실린다 */
  public record Session(String accessToken, String refreshToken, UserRow user) {}

  /** bcrypt cost 12, {@code $2b$} 접두 (TC-SEC-02) */
  public static final BCryptPasswordEncoder PASSWORD_ENCODER =
      new BCryptPasswordEncoder(BCryptPasswordEncoder.BCryptVersion.$2B, Constants.BCRYPT_COST);

  private final UserRepository users;
  private final RefreshTokenService refreshTokens;
  private final JwtTokens tokens;

  /** 사용자가 없어도 bcrypt 비교를 돌려 응답 시간을 맞춘다 [구현 결정] */
  private final String dummyHash = PASSWORD_ENCODER.encode(UUID.randomUUID().toString());

  public AuthService(UserRepository users, RefreshTokenService refreshTokens, JwtTokens tokens) {
    this.users = users;
    this.refreshTokens = refreshTokens;
    this.tokens = tokens;
  }

  /** TRD §9.2 — 이메일 중복은 사전 조회 + UNIQUE 위반 둘 다 409 E1004 */
  public long signup(SignupRequest request) {
    if (users.existsByEmail(request.email())) throw new ApiException(ErrorCode.E1004);
    String hash = PASSWORD_ENCODER.encode(request.password());
    try {
      long userId = users.insert(request.email(), hash, request.nickname());
      log.atInfo()
          .addKeyValue("userId", userId)
          .addKeyValue("email", MaskEmail.mask(request.email()))
          .log("user_signed_up");
      return userId;
    } catch (DuplicateKeyException e) {
      throw new ApiException(ErrorCode.E1004);
    }
  }

  /** TRD §9.3 — 불일치·사용자 없음 모두 401 E1003 (어느 쪽인지 구분하지 않음) */
  public Session login(LoginRequest request) {
    var user = users.findByEmail(request.email());
    boolean matches =
        PASSWORD_ENCODER.matches(
            request.password(), user.map(UserRow::passwordHash).orElse(dummyHash));
    if (user.isEmpty() || !matches) throw new ApiException(ErrorCode.E1003);
    return session(user.get(), refreshTokens.issue(user.get().id()).token());
  }

  /** TRD §9.4 — 교체 후 새 액세스 토큰 */
  public Session refresh(String refreshToken) {
    if (refreshToken == null) throw new ApiException(ErrorCode.E1001);
    var rotated = refreshTokens.rotate(refreshToken);
    UserRow user =
        users.findById(rotated.userId()).orElseThrow(() -> new ApiException(ErrorCode.E1001));
    return session(user, rotated.token());
  }

  /** TRD §9.5 — 토큰이 없어도 성공 */
  public void logout(long userId, String refreshToken) {
    if (refreshToken != null) refreshTokens.revokeIfOwned(refreshToken, userId);
  }

  private Session session(UserRow user, String refreshToken) {
    return new Session(tokens.signAccess(user.id()), refreshToken, user);
  }
}
