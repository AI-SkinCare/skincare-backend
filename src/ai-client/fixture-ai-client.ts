import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { FixtureScenario } from '../common/enums';
import type { Sleep } from '../common/utils/sleep';
import type {
  AiCallContext,
  AiClient,
  AiHealth,
  MeasureSkinInput,
  RecommendRequest,
  RecommendResult,
  SkinMetricsResult,
} from './ai-client.interface';
import { AiError } from './ai-error';
import { parseRecommendResponse, parseSkinMetricsResponse } from './ai-response.schemas';
import { toRecommendResult } from './ai-transform';

/** src/ai-client · dist/ai-client 어디서 돌든 레포 루트의 test/fixtures/ai */
export const DEFAULT_FIXTURE_DIR = join(__dirname, '..', '..', 'test', 'fixtures', 'ai');

export const FIXTURE_REC_DELAY_MS = 1000;
export const FIXTURE_REC_SLOW_DELAY_MS = 60_000;
const RECORDED_REQUEST_LIMIT = 50;

/**
 * AI 서버가 꺼져 있어도 개발·테스트할 수 있게 하는 클라이언트 (TRD §12.7).
 *
 * | 시나리오     | 측정                | 추천                    | health |
 * | ok         | skin-metrics.ok    | recommend.ok, 1초 지연   | ok     |
 * | no-face    | 422 E2001          | —                       | ok     |
 * | rec-timeout| 정상                | 504 E3002 즉시           | ok     |
 * | rec-slow   | 정상                | 60초 지연 후 정상         | ok     |
 * | ai-down    | connection         | connection              | 실패    |
 *
 * 응답은 HttpAiClient와 같은 검증·변환 코드를 거친다 (계약 테스트 겸용).
 */
export class FixtureAiClient implements AiClient {
  /** 받은 추천 요청 (TC-ANA-09: 데모 측정이면 metrics: null). 최근 것만 보관 */
  readonly recommendRequests: RecommendRequest[] = [];

  private readonly skinMetricsBody: string;
  private readonly recommendBody: string;

  constructor(
    private readonly defaultScenario: FixtureScenario,
    private readonly sleep: Sleep,
    fixtureDir: string = DEFAULT_FIXTURE_DIR,
  ) {
    this.skinMetricsBody = readFileSync(join(fixtureDir, 'skin-metrics.ok.json'), 'utf8');
    this.recommendBody = readFileSync(join(fixtureDir, 'recommend.ok.json'), 'utf8');
  }

  /** health는 env 시나리오만 따른다 */
  async health(): Promise<AiHealth> {
    if (this.defaultScenario === 'ai-down') throw new AiError('connection');
    return {
      status: 'ok',
      recLoaded: true,
      skinLoaded: true,
      recBackend: 'fixture',
      skinBackend: 'stub',
    };
  }

  async modelInfo(): Promise<unknown> {
    if (this.defaultScenario === 'ai-down') throw new AiError('connection');
    return { mode: 'fixture' };
  }

  async measureSkin(_input: MeasureSkinInput, ctx: AiCallContext): Promise<SkinMetricsResult> {
    switch (ctx.scenario ?? this.defaultScenario) {
      case 'no-face':
        throw new AiError('http', {
          status: 422,
          code: 'E2001',
          aiMessage: '얼굴을 찾지 못했어요. 정면에서 다시 찍어 주세요.',
        });
      case 'ai-down':
        throw new AiError('connection');
      default:
        return parseSkinMetricsResponse(JSON.parse(this.skinMetricsBody));
    }
  }

  async recommend(input: RecommendRequest, ctx: AiCallContext): Promise<RecommendResult> {
    this.recommendRequests.push(structuredClone(input));
    if (this.recommendRequests.length > RECORDED_REQUEST_LIMIT) this.recommendRequests.shift();

    switch (ctx.scenario ?? this.defaultScenario) {
      case 'rec-timeout':
        throw new AiError('http', {
          status: 504,
          code: 'E3002',
          aiMessage: '추천을 만들지 못했어요. 다시 시도해 주세요.',
        });
      case 'ai-down':
        throw new AiError('connection');
      case 'rec-slow':
        await this.sleep(FIXTURE_REC_SLOW_DELAY_MS);
        break;
      default:
        await this.sleep(FIXTURE_REC_DELAY_MS);
    }
    return toRecommendResult(parseRecommendResponse(JSON.parse(this.recommendBody)));
  }
}
