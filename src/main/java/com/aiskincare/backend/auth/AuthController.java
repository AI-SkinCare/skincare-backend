package com.aiskincare.backend.auth;

import com.aiskincare.backend.auth.dto.LoginRequest;
import com.aiskincare.backend.auth.dto.RefreshRequest;
import com.aiskincare.backend.auth.dto.SignupRequest;
import com.aiskincare.backend.auth.dto.SignupResponse;
import com.aiskincare.backend.auth.dto.TokenResponse;
import com.aiskincare.backend.common.error.ApiException;
import com.aiskincare.backend.common.error.ErrorCode;
import com.aiskincare.backend.common.web.ApiEnvelope;
import com.aiskincare.backend.common.web.ApiErrorCodes;
import com.aiskincare.backend.common.web.AuthUser;
import com.aiskincare.backend.common.web.CurrentUser;
import com.aiskincare.backend.common.web.PublicApi;
import com.aiskincare.backend.config.AppProperties;
import com.aiskincare.backend.config.Constants;
import com.aiskincare.backend.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "auth")
@RestController
@RequestMapping("/auth")
public class AuthController {
  private final AuthService auth;
  private final AppProperties props;

  public AuthController(AuthService auth, AppProperties props) {
    this.auth = auth;
    this.props = props;
  }

  @Operation(summary = "회원가입 — 동의 4종 필수 (FR-AUTH-01)")
  @PublicApi
  @ApiErrorCodes({ErrorCode.E4002, ErrorCode.E1004})
  @ResponseStatus(HttpStatus.CREATED)
  @PostMapping("/signup")
  public ApiEnvelope<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
    return ApiEnvelope.ok(new SignupResponse(auth.signup(request)));
  }

  @Operation(summary = "로그인 — 리프레시 토큰은 httpOnly 쿠키 (+ X-Client: ios면 본문) (FR-AUTH-02)")
  @PublicApi
  @LoginRateLimited
  @ApiErrorCodes({ErrorCode.E4004, ErrorCode.E4002, ErrorCode.E1003})
  @PostMapping("/login")
  public ApiEnvelope<TokenResponse> login(
      @Valid @RequestBody LoginRequest request,
      @Parameter(in = ParameterIn.HEADER, description = "[앱 협의] ios면 응답 data.refreshToken 포함")
          @RequestHeader(value = ClientType.HEADER, required = false)
          String client,
      HttpServletResponse response) {
    var session = auth.login(request);
    RefreshCookies.set(response, session.refreshToken(), props.secureCookie());
    return ApiEnvelope.ok(present(session, client));
  }

  /** 쿠키 우선, 없으면 본문 refreshToken [앱 협의]. 실패 응답에는 항상 쿠키 삭제 헤더 */
  @Operation(summary = "토큰 재발급 — 리프레시 토큰 교체 (FR-AUTH-02)")
  @PublicApi
  @SecurityRequirement(name = OpenApiConfig.REFRESH_COOKIE)
  @ApiErrorCodes({ErrorCode.E1001, ErrorCode.E4002})
  @PostMapping("/refresh")
  public ApiEnvelope<TokenResponse> refresh(
      @Valid @RequestBody(required = false) RefreshRequest body,
      @Parameter(in = ParameterIn.HEADER, description = "[앱 협의] ios면 응답 data.refreshToken 포함")
          @RequestHeader(value = ClientType.HEADER, required = false)
          String client,
      HttpServletRequest request,
      HttpServletResponse response) {
    try {
      var session =
          auth.refresh(RefreshCookies.read(request, body == null ? null : body.refreshToken()));
      RefreshCookies.set(response, session.refreshToken(), props.secureCookie());
      return ApiEnvelope.ok(present(session, client));
    } catch (ApiException e) {
      RefreshCookies.clear(response, props.secureCookie());
      throw e;
    }
  }

  @Operation(summary = "로그아웃 — 리프레시 토큰 폐기 (FR-AUTH-03)")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PostMapping("/logout")
  public void logout(
      @CurrentUser AuthUser user,
      @Valid @RequestBody(required = false) RefreshRequest body,
      HttpServletRequest request,
      HttpServletResponse response) {
    auth.logout(user.id(), RefreshCookies.read(request, body == null ? null : body.refreshToken()));
    RefreshCookies.clear(response, props.secureCookie());
  }

  private static TokenResponse present(AuthService.Session session, String client) {
    var user = session.user();
    return new TokenResponse(
        session.accessToken(),
        Constants.ACCESS_TOKEN_TTL_SEC,
        ClientType.isNative(client) ? session.refreshToken() : null,
        new TokenResponse.SessionUser(user.id(), user.email(), user.nickname()));
  }
}
