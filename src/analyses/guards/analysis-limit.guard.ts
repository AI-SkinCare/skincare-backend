import { CanActivate, ExecutionContext, Inject, Injectable } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import type { Response } from 'express';
import { In, MoreThanOrEqual, Repository } from 'typeorm';
import { ApiException } from '../../common/errors/api.exception';
import type { AppRequest } from '../../common/http/app-request';
import { secondsUntilNextKstMidnight, startOfKstDay } from '../../common/utils/time';
import { APP_CONFIG, AppConfig } from '../../config/app-config';
import { IN_PROGRESS_RETRY_AFTER_SEC } from '../../config/constants';
import { Analysis } from '../entities/analysis.entity';
import { SubmissionLock } from './submission-lock';

/**
 * TRD §10.1 처리 순서 2 — 요청 제한.
 * 진행 중 분석(queued·recommending) 또는 처리 중 제출이 있음 → 429 E4004 in_progress
 * 오늘(KST) 생성한 분석 수 ≥ ANALYSIS_DAILY_LIMIT → 429 E4004 daily_limit
 * 통과하면 처리 중 제출 잠금을 걸고 응답이 끝나면 푼다.
 */
@Injectable()
export class AnalysisLimitGuard implements CanActivate {
  constructor(
    @InjectRepository(Analysis) private readonly analyses: Repository<Analysis>,
    @Inject(APP_CONFIG) private readonly config: AppConfig,
    private readonly lock: SubmissionLock,
  ) {}

  async canActivate(context: ExecutionContext): Promise<boolean> {
    const http = context.switchToHttp();
    const req = http.getRequest<AppRequest>();
    const res = http.getResponse<Response>();
    const userId = req.user?.id;
    if (userId === undefined) throw new Error('AnalysisLimitGuard는 JwtAuthGuard 뒤에 둔다');

    const inProgress = await this.analyses.existsBy({
      userId,
      status: In(['queued', 'recommending']),
    });
    if (inProgress || !this.lock.tryAcquire(userId)) {
      throw ApiException.rateLimited('in_progress', IN_PROGRESS_RETRY_AFTER_SEC);
    }
    res.once('close', () => this.lock.release(userId));

    const now = new Date();
    const todayCount = await this.analyses.countBy({
      userId,
      createdAt: MoreThanOrEqual(startOfKstDay(now)),
    });
    if (todayCount >= this.config.analysisDailyLimit) {
      throw ApiException.rateLimited('daily_limit', secondsUntilNextKstMidnight(now));
    }
    return true;
  }
}
