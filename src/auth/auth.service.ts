import { randomBytes } from 'node:crypto';
import { Inject, Injectable, Logger } from '@nestjs/common';
import { JwtService } from '@nestjs/jwt';
import { InjectRepository } from '@nestjs/typeorm';
import * as bcrypt from 'bcrypt';
import { QueryFailedError, Repository } from 'typeorm';
import { ApiException } from '../common/errors/api.exception';
import { ErrorCode } from '../common/errors/error-codes';
import { maskEmail } from '../common/logging/mask-email';
import { APP_CONFIG, AppConfig } from '../config/app-config';
import { ACCESS_TOKEN_TTL_SEC, BCRYPT_COST } from '../config/constants';
import { User } from '../users/user.entity';
import { LoginDto, SignupDto } from './dto/auth-request.dto';
import { RefreshTokenService } from './refresh-token.service';

export interface Session {
  accessToken: string;
  expiresIn: number;
  refreshToken: string;
  user: { id: number; email: string; nickname: string };
}

const UNIQUE_VIOLATION = '23505';

@Injectable()
export class AuthService {
  private readonly logger = new Logger(AuthService.name);
  /** 사용자가 없어도 bcrypt.compare를 돌려 응답 시간을 맞춘다 [구현 결정] */
  private readonly dummyHash: Promise<string>;

  constructor(
    @InjectRepository(User) private readonly users: Repository<User>,
    private readonly refreshTokens: RefreshTokenService,
    private readonly jwt: JwtService,
    @Inject(APP_CONFIG) private readonly config: AppConfig,
  ) {
    this.dummyHash = bcrypt.hash(randomBytes(16).toString('hex'), BCRYPT_COST);
  }

  /** TRD §9.2 — bcrypt(cost 12), 동의 시각 4개 = 현재 시각, 이메일 중복 409 E1004 */
  async signup(dto: SignupDto): Promise<{ userId: number }> {
    if (await this.users.existsBy({ email: dto.email })) throw new ApiException(ErrorCode.E1004);
    const passwordHash = await bcrypt.hash(dto.password, BCRYPT_COST);
    const now = new Date();
    try {
      const user = await this.users.save(
        this.users.create({
          email: dto.email,
          passwordHash,
          nickname: dto.nickname,
          termsAgreedAt: now,
          privacyAgreedAt: now,
          imageConsentAt: now,
          over14ConfirmedAt: now,
        }),
      );
      this.logger.log({ msg: 'user_signed_up', userId: user.id, email: maskEmail(user.email) });
      return { userId: user.id };
    } catch (error) {
      if (
        error instanceof QueryFailedError &&
        (error.driverError as { code?: string }).code === UNIQUE_VIOLATION
      ) {
        throw new ApiException(ErrorCode.E1004);
      }
      throw error;
    }
  }

  /** TRD §9.3 — 불일치·사용자 없음 모두 401 E1003 (어느 쪽인지 구분하지 않음) */
  async login(dto: LoginDto): Promise<Session> {
    const user = await this.users.findOneBy({ email: dto.email });
    const matches = await bcrypt.compare(
      dto.password,
      user?.passwordHash ?? (await this.dummyHash),
    );
    if (!user || !matches) throw new ApiException(ErrorCode.E1003);
    const { token } = await this.refreshTokens.issue(user.id);
    return this.session(user, token);
  }

  /** TRD §9.4 — 교체 후 새 액세스 토큰 */
  async refresh(refreshToken: string | undefined): Promise<Session> {
    if (!refreshToken) throw new ApiException(ErrorCode.E1001);
    const rotated = await this.refreshTokens.rotate(refreshToken);
    const user = await this.users.findOneBy({ id: rotated.userId });
    if (!user) throw new ApiException(ErrorCode.E1001);
    return this.session(user, rotated.token);
  }

  /** TRD §9.5 — 토큰이 없어도 성공 */
  async logout(userId: number, refreshToken: string | undefined): Promise<void> {
    if (refreshToken) await this.refreshTokens.revokeIfOwned(refreshToken, userId);
  }

  private async session(user: User, refreshToken: string): Promise<Session> {
    const accessToken = await this.jwt.signAsync(
      { sub: user.id },
      { secret: this.config.jwt.accessSecret, expiresIn: ACCESS_TOKEN_TTL_SEC, algorithm: 'HS256' },
    );
    return {
      accessToken,
      expiresIn: ACCESS_TOKEN_TTL_SEC,
      refreshToken,
      user: { id: user.id, email: user.email, nickname: user.nickname },
    };
  }
}
