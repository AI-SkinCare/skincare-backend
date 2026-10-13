package com.aiskincare.backend.users;

import com.aiskincare.backend.common.error.ApiException;
import com.aiskincare.backend.common.error.ErrorCode;
import com.aiskincare.backend.common.util.TimeUtil;
import com.aiskincare.backend.users.dto.UserMeResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class UsersService {
  private static final Logger log = LoggerFactory.getLogger(UsersService.class);
  private final UserRepository users;

  public UsersService(UserRepository users) {
    this.users = users;
  }

  public UserMeResponse me(long userId) {
    var user = users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.E1001));
    return new UserMeResponse(
        user.id(),
        user.email(),
        user.nickname(),
        TimeUtil.toIsoUtc(user.createdAt()),
        users.countAnalyses(userId));
  }

  /** 진행 중 추천은 워커가 결과 저장 시점에 행이 없어 버린다 (TRD §13.7) */
  public void delete(long userId) {
    users.deleteById(userId);
    log.info("user_deleted"); // userId는 MDC에 있다
  }
}
