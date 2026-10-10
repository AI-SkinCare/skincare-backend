import { Injectable, Logger } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { DataSource, Repository } from 'typeorm';
import { ApiException } from '../common/errors/api.exception';
import { ErrorCode } from '../common/errors/error-codes';
import { toIsoUtc } from '../common/utils/time';
import { toNumber } from '../database/raw-query';
import { UserMeDto } from './dto/user-me.dto';
import { User } from './user.entity';

@Injectable()
export class UsersService {
  private readonly logger = new Logger(UsersService.name);

  constructor(
    private readonly dataSource: DataSource,
    @InjectRepository(User) private readonly users: Repository<User>,
  ) {}

  async me(userId: number): Promise<UserMeDto> {
    const user = await this.users.findOneBy({ id: userId });
    if (!user) throw new ApiException(ErrorCode.E1001);
    const rows: { count: string }[] = await this.dataSource.query(
      `SELECT COUNT(*) AS count FROM analyses WHERE user_id = $1`,
      [userId],
    );
    return {
      id: user.id,
      email: user.email,
      nickname: user.nickname,
      createdAt: toIsoUtc(user.createdAt),
      analysisCount: toNumber(rows[0].count),
    };
  }

  /**
   * TRD §9.7 — CASCADE로 분석·지표·설문·추천·리프레시 토큰 전부 즉시 삭제.
   * 진행 중 추천은 워커가 결과 저장 시점에 행이 없어 버린다 (§13.7).
   */
  async remove(userId: number): Promise<void> {
    await this.users.delete({ id: userId });
    this.logger.log({ msg: 'user_deleted', userId });
  }
}
