package com.aiskincare.backend.users;

import com.aiskincare.backend.auth.RefreshCookies;
import com.aiskincare.backend.common.web.ApiEnvelope;
import com.aiskincare.backend.common.web.AuthUser;
import com.aiskincare.backend.common.web.CurrentUser;
import com.aiskincare.backend.config.AppProperties;
import com.aiskincare.backend.users.dto.UserMeResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "users")
@RestController
@RequestMapping("/users")
public class UsersController {
  private final UsersService users;
  private final AppProperties props;

  public UsersController(UsersService users, AppProperties props) {
    this.users = users;
    this.props = props;
  }

  @Operation(summary = "내 정보")
  @GetMapping("/me")
  public ApiEnvelope<UserMeResponse> me(@CurrentUser AuthUser user) {
    return ApiEnvelope.ok(users.me(user.id()));
  }

  @Operation(summary = "회원 탈퇴 — 모든 데이터 즉시 삭제 (FR-AUTH-04)")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @DeleteMapping("/me")
  public void delete(@CurrentUser AuthUser user, HttpServletResponse response) {
    users.delete(user.id());
    RefreshCookies.clear(response, props.secureCookie());
  }
}
