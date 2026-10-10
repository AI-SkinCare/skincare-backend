import { Inject, Injectable, Logger } from '@nestjs/common';
import { SchedulerRegistry } from '@nestjs/schedule';
import { AI_CLIENT, AiClient } from '../ai-client/ai-client.interface';
import { APP_CONFIG, AppConfig } from '../config/app-config';

const INTERVAL_NAME = 'ai-health-check';

/**
 * TRD §14.1 — AI 가용성. 기동 시 1회, 이후 AI_HEALTH_INTERVAL_MS마다 GET /health(5초).
 * 첫 검사 전에는 false. 바뀔 때만 로그(끊김 WARN, 복구 INFO).
 */
@Injectable()
export class AiHealthService {
  private readonly logger = new Logger(AiHealthService.name);
  private available = false;
  private checkedAt: Date | null = null;
  private lastSkinBackend: string | undefined;
  private inFlight: Promise<boolean> | null = null;
  private started = false;

  constructor(
    @Inject(AI_CLIENT) private readonly ai: AiClient,
    @Inject(APP_CONFIG) private readonly config: AppConfig,
    private readonly scheduler: SchedulerRegistry,
  ) {}

  isAvailable(): boolean {
    return this.available;
  }

  snapshot(): { aiAvailable: boolean; checkedAt: Date | null } {
    return { aiAvailable: this.available, checkedAt: this.checkedAt };
  }

  /** 동시에 여러 번 불려도 GET /health는 한 번만 */
  check(): Promise<boolean> {
    this.inFlight ??= this.runCheck().finally(() => {
      this.inFlight = null;
    });
    return this.inFlight;
  }

  /** 첫 검사 후 주기 검사를 건다 (워커 기동 순서 ② — TRD §13.2) */
  async start(): Promise<void> {
    if (this.started) return;
    this.started = true;
    await this.check();
    const handle = setInterval(() => void this.check(), this.config.ai.healthIntervalMs);
    this.scheduler.addInterval(INTERVAL_NAME, handle);
    void this.logModelInfo();
  }

  /** 측정 중 connection 오류 → 즉시 비가용으로 두고 다음 검사를 앞당긴다 [구현 결정] */
  markUnavailable(reason: string): void {
    this.setAvailable(false, reason);
    if (this.started) void this.check();
  }

  private async runCheck(): Promise<boolean> {
    let ok = false;
    let reason = 'ok';
    try {
      const health = await this.ai.health();
      ok = health.status === 'ok';
      if (!ok) reason = `status:${health.status}`;
      if (ok && health.skinBackend !== this.lastSkinBackend) {
        this.lastSkinBackend = health.skinBackend;
        if (health.skinBackend === 'stub') {
          this.logger.log({
            msg: 'ai_skin_backend_stub',
            detail: '측정값은 데모 값 (is_demo=true)',
          });
        }
      }
    } catch (error) {
      reason = error instanceof Error ? error.message : 'unknown';
    }
    this.checkedAt = new Date();
    this.setAvailable(ok, reason);
    return ok;
  }

  private setAvailable(next: boolean, reason: string): void {
    if (next === this.available) return;
    this.available = next;
    if (next) this.logger.log({ msg: 'ai_available' });
    else this.logger.warn({ msg: 'ai_unavailable', reason });
  }

  private async logModelInfo(): Promise<void> {
    try {
      const info = await this.ai.modelInfo();
      this.logger.log({ msg: 'ai_model_info', modelInfo: info });
    } catch (error) {
      this.logger.warn({
        msg: 'ai_model_info_failed',
        reason: error instanceof Error ? error.message : 'unknown',
      });
    }
  }
}
