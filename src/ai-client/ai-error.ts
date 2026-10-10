// TRD §12.2 오류 분류
//
// | 상황                                                     | kind             |
// | fetch가 응답 전에 실패 (ECONNREFUSED, TLS 오류 등)          | connection       |
// | 상태 ≥ 500이고 본문에 error.code가 없음 (Cloudflare HTML 등) | connection       |
// | 우리 타임아웃(AbortSignal.timeout) 초과                     | timeout          |
// | 상태 ≥ 400이고 본문에 error.code 있음                       | http             |
// | 2xx인데 JSON 파싱 실패 또는 스키마 위반                       | invalid_response |

export type AiErrorKind = 'http' | 'connection' | 'timeout' | 'invalid_response';

export interface ValidationIssue {
  path: string;
  reason: string;
}

export interface AiErrorOptions {
  status?: number;
  code?: string;
  /** AI가 준 사용자 문구 (E2001/E2002에서만 그대로 씀) */
  aiMessage?: string;
  /** invalid_response: 실패한 필드 경로와 이유만 (값은 넣지 않는다) */
  issues?: ValidationIssue[];
}

export class AiError extends Error {
  readonly kind: AiErrorKind;
  readonly status?: number;
  readonly code?: string;
  readonly aiMessage?: string;
  readonly issues: ValidationIssue[];

  constructor(kind: AiErrorKind, options: AiErrorOptions = {}) {
    // 메시지에 AI 응답 본문을 넣지 않는다.
    super(
      `AI ${kind}${options.status ? ` ${options.status}` : ''}${options.code ? ` ${options.code}` : ''}`,
    );
    this.name = 'AiError';
    this.kind = kind;
    this.status = options.status;
    this.code = options.code;
    this.aiMessage = options.aiMessage;
    this.issues = options.issues ?? [];
  }

  /** 로그용 요약 — 상태·코드·이슈 경로만 */
  toLog(): Record<string, unknown> {
    return {
      kind: this.kind,
      status: this.status,
      code: this.code,
      ...(this.issues.length > 0 ? { issues: this.issues } : {}),
    };
  }
}

export function isAiError(value: unknown): value is AiError {
  return value instanceof AiError;
}
