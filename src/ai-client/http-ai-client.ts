import { AI_HEALTH_TIMEOUT_MS } from '../config/constants';
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
import {
  parseHealthResponse,
  parseRecommendResponse,
  parseSkinMetricsResponse,
} from './ai-response.schemas';
import { toRecommendResult } from './ai-transform';

export interface HttpAiClientOptions {
  baseUrl: string;
  internalToken: string;
  skinTimeoutMs: number;
  recTimeoutMs: number;
}

/** Cloudflare Error 524 — A timeout occurred (원 서버 응답 시간 초과) */
const CLOUDFLARE_ORIGIN_TIMEOUT = 524;

interface RequestOptions {
  method: 'GET' | 'POST';
  body?: FormData | string;
  contentType?: string;
  timeoutMs: number;
  /** /health는 내부 토큰 없이 */
  authenticated: boolean;
  requestId?: string;
}

/**
 * AI 서버 HTTP 클라이언트 (TRD §12.2). Node 내장 fetch + AbortSignal.timeout.
 * 재시도는 하지 않는다 — 재시도 정책은 호출하는 쪽(측정 경로·워커)이 §12.5로 정한다.
 */
export class HttpAiClient implements AiClient {
  constructor(private readonly options: HttpAiClientOptions) {}

  async health(): Promise<AiHealth> {
    const body = await this.request('/health', {
      method: 'GET',
      timeoutMs: AI_HEALTH_TIMEOUT_MS,
      authenticated: false,
    });
    return parseHealthResponse(body);
  }

  async modelInfo(): Promise<unknown> {
    return this.request('/model-info', {
      method: 'GET',
      timeoutMs: AI_HEALTH_TIMEOUT_MS,
      authenticated: true,
    });
  }

  async measureSkin(input: MeasureSkinInput, ctx: AiCallContext): Promise<SkinMetricsResult> {
    const form = new FormData();
    const filename = input.mimeType === 'image/png' ? 'image.png' : 'image.jpg';
    form.append('image', new Blob([input.image], { type: input.mimeType }), filename);
    form.append('device_type', input.deviceType);
    const body = await this.request('/infer/skin-metrics', {
      method: 'POST',
      body: form,
      timeoutMs: this.options.skinTimeoutMs,
      authenticated: true,
      requestId: ctx.requestId,
    });
    return parseSkinMetricsResponse(body);
  }

  async recommend(input: RecommendRequest, ctx: AiCallContext): Promise<RecommendResult> {
    const body = await this.request('/infer/recommend', {
      method: 'POST',
      body: JSON.stringify(input),
      contentType: 'application/json',
      timeoutMs: this.options.recTimeoutMs,
      authenticated: true,
      requestId: ctx.requestId,
    });
    return toRecommendResult(parseRecommendResponse(body));
  }

  private async request(path: string, options: RequestOptions): Promise<unknown> {
    const headers: Record<string, string> = { Accept: 'application/json' };
    if (options.authenticated) headers['X-Internal-Token'] = this.options.internalToken;
    if (options.requestId) headers['X-Request-Id'] = options.requestId;
    if (options.contentType) headers['Content-Type'] = options.contentType;

    const signal = AbortSignal.timeout(options.timeoutMs);
    let status: number;
    let text: string;
    try {
      const res = await fetch(`${this.options.baseUrl}${path}`, {
        method: options.method,
        headers,
        body: options.body,
        signal,
      });
      status = res.status;
      text = await res.text();
    } catch (error) {
      throw classifyFetchFailure(error);
    }

    const json = parseJson(text);
    if (status >= 400) {
      const aiError = extractAiError(json);
      if (aiError) {
        throw new AiError('http', { status, code: aiError.code, aiMessage: aiError.message });
      }
      // Cloudflare 524 = 원 서버(AI)가 연결은 받았지만 125초 안에 응답하지 않음. AI는 GPU 생성을 계속하므로
      // 504와 같이 재시도하면 안 된다 → timeout으로 분류 (워커: 재시도 없이 E3002) [구현 결정]
      if (status === CLOUDFLARE_ORIGIN_TIMEOUT) throw new AiError('timeout', { status });
      // 본문에 error.code가 없는 5xx = Cloudflare HTML(502/521/522/523/530 등) → 연결 실패로 본다.
      // Cloudflare의 503을 AI의 503 E3003으로 읽으면 "요청이 많아요"가 잘못 뜬다.
      if (status >= 500) throw new AiError('connection', { status });
      throw new AiError('http', { status });
    }
    if (json === undefined) {
      throw new AiError('invalid_response', {
        status,
        issues: [{ path: '$', reason: 'invalid_json' }],
      });
    }
    return json;
  }
}

function classifyFetchFailure(error: unknown): AiError {
  // AbortSignal.timeout은 DOMException(TimeoutError)을 던진다. DOMException은 실행 영역(realm)에 따라
  // instanceof Error가 거짓일 수 있어 name 속성으로 판별한다.
  const name =
    typeof error === 'object' && error !== null && 'name' in error ? String(error.name) : '';
  if (name === 'TimeoutError' || name === 'AbortError') return new AiError('timeout');
  return new AiError('connection');
}

function parseJson(text: string): unknown {
  if (text.length === 0) return undefined;
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return undefined;
  }
}

/** 실패 본문 `{ success: false, error: { code, message, detail } }`에서 code가 있을 때만 */
function extractAiError(json: unknown): { code: string; message?: string } | undefined {
  if (typeof json !== 'object' || json === null) return undefined;
  const error = (json as { error?: unknown }).error;
  if (typeof error !== 'object' || error === null) return undefined;
  const { code, message } = error as { code?: unknown; message?: unknown };
  if (typeof code !== 'string' || code.length === 0) return undefined;
  return { code, message: typeof message === 'string' ? message : undefined };
}
