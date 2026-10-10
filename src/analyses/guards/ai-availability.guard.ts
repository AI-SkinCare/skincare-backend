import { CanActivate, Injectable } from '@nestjs/common';
import { ApiException } from '../../common/errors/api.exception';
import { ErrorCode } from '../../common/errors/error-codes';
import { AiHealthService } from '../../system/ai-health.service';

/** TRD §10.1 처리 순서 4 — aiAvailable=false면 파일을 받기 전에 503 E3001 */
@Injectable()
export class AiAvailabilityGuard implements CanActivate {
  constructor(private readonly aiHealth: AiHealthService) {}

  canActivate(): boolean {
    if (!this.aiHealth.isAvailable()) throw new ApiException(ErrorCode.E3001);
    return true;
  }
}
