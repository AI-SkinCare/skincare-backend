import { Injectable } from '@nestjs/common';
import { AuthGuard } from '@nestjs/passport';
import { ApiException } from '../../common/errors/api.exception';
import { ErrorCode } from '../../common/errors/error-codes';
import type { AuthUser } from '../../common/http/app-request';

/** 토큰 없음·만료·위조·탈퇴한 사용자 모두 401 E1001 (앱은 E1001에서만 refresh한다) */
@Injectable()
export class JwtAuthGuard extends AuthGuard('jwt') {
  handleRequest<TUser = AuthUser>(err: unknown, user: TUser | false): TUser {
    if (err || !user) throw new ApiException(ErrorCode.E1001);
    return user;
  }
}
