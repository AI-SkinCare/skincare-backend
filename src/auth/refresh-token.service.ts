import { randomBytes } from 'node:crypto';
import { Inject, Injectable, Logger } from '@nestjs/common';
import { JwtService } from '@nestjs/jwt';
import { Cron } from '@nestjs/schedule';
import { InjectRepository } from '@nestjs/typeorm';
import { DataSource, EntityManager, Repository } from 'typeorm';
import { ApiException } from '../common/errors/api.exception';
import { ErrorCode } from '../common/errors/error-codes';
import { APP_CONFIG, AppConfig } from '../config/app-config';
import { REFRESH_TOKEN_TTL_SEC } from '../config/constants';
import { rowsAndCount, toNumber } from '../database/raw-query';
import { RefreshToken } from './refresh-token.entity';
import { hashToken } from './token-hash';

class ReuseDetected extends Error {}

/**
 * 리프레시 토큰 — 해시 저장, 사용 시 교체, 재사용 감지 시 그 사용자 토큰 전부 폐기 (TRD §9.4).
 * 쿠키로 오든 본문으로 오든 [앱 협의] 같은 경로로 검증한다.
 */
@Injectable()
export class RefreshTokenService {
  private readonly logger = new Logger(RefreshTokenService.name);

  constructor(
    private readonly dataSource: DataSource,
    @InjectRepository(RefreshToken) private readonly tokens: Repository<RefreshToken>,
    private readonly jwt: JwtService,
    @Inject(APP_CONFIG) private readonly config: AppConfig,
  ) {}

  /** 새 토큰 발급 + 해시 INSERT */
  async issue(
    userId: number,
    manager: EntityManager = this.dataSource.manager,
  ): Promise<{ token: string; id: number }> {
    const token = await this.jwt.signAsync(
      { sub: userId, jti: randomBytes(16).toString('hex') },
      {
        secret: this.config.jwt.refreshSecret,
        expiresIn: REFRESH_TOKEN_TTL_SEC,
        algorithm: 'HS256',
      },
    );
    const rows: { id: string }[] = await manager.query(
      `INSERT INTO refresh_tokens (user_id, token_hash, expires_at)
       VALUES ($1, $2, now() + make_interval(secs => $3)) RETURNING id`,
      [userId, hashToken(token), REFRESH_TOKEN_TTL_SEC],
    );
    return { token, id: toNumber(rows[0].id) };
  }

  /**
   * 1. JWT 서명·만료 검증 실패 → E1001
   * 2. 해시로 행 조회: 없음 → E1001
   *    revoked + replaced_by 있음 → 재사용 감지: 그 사용자 토큰 전부 폐기 → E1001
   *    revoked (로그아웃) → E1001 / 만료 → E1001
   * 3. 트랜잭션: 새 토큰 INSERT → 이전 토큰 revoked·replaced_by 갱신.
   *    영향 0행(동시에 다른 요청이 먼저 교체) = 재사용으로 본다.
   */
  async rotate(token: string): Promise<{ userId: number; token: string }> {
    const sub = await this.verifySignature(token);
    const row = await this.tokens.findOneBy({ tokenHash: hashToken(token) });
    if (!row || row.userId !== sub) throw new ApiException(ErrorCode.E1001);
    if (row.revokedAt) {
      if (row.replacedBy !== null) await this.handleReuse(row.userId);
      throw new ApiException(ErrorCode.E1001);
    }
    if (row.expiresAt.getTime() < Date.now()) throw new ApiException(ErrorCode.E1001);

    try {
      const issued = await this.dataSource.transaction(async (manager) => {
        const next = await this.issue(row.userId, manager);
        const { count } = rowsAndCount(
          await manager.query(
            `UPDATE refresh_tokens SET revoked_at = now(), replaced_by = $2
             WHERE id = $1 AND revoked_at IS NULL`,
            [row.id, next.id],
          ),
        );
        if (count === 0) throw new ReuseDetected();
        return next;
      });
      return { userId: row.userId, token: issued.token };
    } catch (error) {
      if (error instanceof ReuseDetected) {
        await this.handleReuse(row.userId);
        throw new ApiException(ErrorCode.E1001);
      }
      throw error;
    }
  }

  /** 로그아웃: 그 사용자의 토큰이면 revoked_at만 (replaced_by는 NULL 유지) */
  async revokeIfOwned(token: string, userId: number): Promise<void> {
    await this.dataSource.query(
      `UPDATE refresh_tokens SET revoked_at = now()
       WHERE token_hash = $1 AND user_id = $2 AND revoked_at IS NULL`,
      [hashToken(token), userId],
    );
  }

  /** 매일 03:30 KST — 만료 후 7일 지난 토큰 삭제 (TRD §8.5) */
  @Cron('30 3 * * *', { name: 'refresh-token-cleanup', timeZone: 'Asia/Seoul' })
  async cleanupExpired(): Promise<number> {
    try {
      const { count } = rowsAndCount(
        await this.dataSource.query(
          `DELETE FROM refresh_tokens WHERE expires_at < now() - interval '7 days'`,
        ),
      );
      this.logger.log({ msg: 'refresh_token_cleanup', deleted: count });
      return count;
    } catch (error) {
      this.logger.error({ msg: 'refresh_token_cleanup_failed', err: error });
      return 0;
    }
  }

  private async verifySignature(token: string): Promise<number> {
    try {
      const payload = await this.jwt.verifyAsync<{ sub?: unknown }>(token, {
        secret: this.config.jwt.refreshSecret,
        algorithms: ['HS256'],
      });
      if (typeof payload.sub === 'number' && Number.isSafeInteger(payload.sub)) return payload.sub;
    } catch {
      // 서명·만료 실패
    }
    throw new ApiException(ErrorCode.E1001);
  }

  private async handleReuse(userId: number): Promise<void> {
    await this.dataSource.query(
      `UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL`,
      [userId],
    );
    this.logger.warn({ msg: 'refresh_token_reuse_detected', userId });
  }
}
