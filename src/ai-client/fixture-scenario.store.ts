import { Inject, Injectable } from '@nestjs/common';
import { FixtureScenario, isFixtureScenario } from '../common/enums';
import { APP_CONFIG, AppConfig } from '../config/app-config';

/**
 * 워커는 요청 헤더를 볼 수 없으므로 POST /analyses·retry에서 받은 `X-Fixture-Scenario`를
 * 분석 id별로 기억해 둔다 (TRD §12.7). 인스턴스 메모리라 재시작하면 사라져도 된다.
 * production에서는 헤더를 무시한다.
 */
@Injectable()
export class FixtureScenarioStore {
  private readonly byAnalysis = new Map<string, FixtureScenario>();

  constructor(@Inject(APP_CONFIG) private readonly config: AppConfig) {}

  /** 헤더 값 → 시나리오. production이거나 모르는 값이면 undefined (env 기본값 사용) */
  fromHeader(value: unknown): FixtureScenario | undefined {
    if (this.config.isProduction) return undefined;
    return isFixtureScenario(value) ? value : undefined;
  }

  /** 요청마다 갱신: 헤더가 없으면 지워서 env 값을 쓰게 한다 */
  remember(analysisId: string, scenario: FixtureScenario | undefined): void {
    if (scenario) this.byAnalysis.set(analysisId, scenario);
    else this.byAnalysis.delete(analysisId);
  }

  get(analysisId: string): FixtureScenario | undefined {
    return this.byAnalysis.get(analysisId);
  }

  forget(analysisId: string): void {
    this.byAnalysis.delete(analysisId);
  }
}
