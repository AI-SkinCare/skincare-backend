import { Inject, Injectable } from '@nestjs/common';
import { PassportStrategy } from '@nestjs/passport';
import { InjectRepository } from '@nestjs/typeorm';
import { ExtractJwt, Strategy } from 'passport-jwt';
import { Repository } from 'typeorm';
import { ApiException } from '../common/errors/api.exception';
import { ErrorCode } from '../common/errors/error-codes';
import type { AuthUser } from '../common/http/app-request';
import { APP_CONFIG, AppConfig } from '../config/app-config';
import { User } from '../users/user.entity';

/** 서명·만료 검증 후 사용자가 아직 있는지 확인 — 탈퇴 후 남은 토큰은 401 E1001 (TRD §9.1) */
@Injectable()
export class JwtStrategy extends PassportStrategy(Strategy, 'jwt') {
  constructor(
    @Inject(APP_CONFIG) config: AppConfig,
    @InjectRepository(User) private readonly users: Repository<User>,
  ) {
    super({
      jwtFromRequest: ExtractJwt.fromAuthHeaderAsBearerToken(),
      secretOrKey: config.jwt.accessSecret,
      algorithms: ['HS256'],
      ignoreExpiration: false,
    });
  }

  async validate(payload: unknown): Promise<AuthUser> {
    const sub =
      typeof payload === 'object' && payload !== null
        ? (payload as { sub?: unknown }).sub
        : undefined;
    if (typeof sub !== 'number' || !Number.isSafeInteger(sub) || sub <= 0) {
      throw new ApiException(ErrorCode.E1001);
    }
    if (!(await this.users.existsBy({ id: sub }))) throw new ApiException(ErrorCode.E1001);
    return { id: sub };
  }
}
