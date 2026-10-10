import type { IncomingMessage, ServerResponse } from 'node:http';
import type { Params } from 'nestjs-pino';
import type { AppConfig } from '../../config/app-config';
import type { AppRequest } from '../http/app-request';

/**
 * TRD §7.6 — 절대 기록하지 않음: 이미지, 설문 원문, 비밀번호, 토큰, 쿠키, X-Internal-Token,
 * 요청/응답 본문. 직렬화 단계에서 헤더·본문을 아예 빼고, redact는 이중 안전장치로 둔다.
 */
export const REDACT_PATHS = [
  'req.headers.authorization',
  'req.headers.cookie',
  'req.headers["x-internal-token"]',
  'res.headers["set-cookie"]',
  'password',
  'accessToken',
  'refreshToken',
  'token',
  'survey',
  'note',
  'currentProducts',
  'image',
  'buffer',
  '*.password',
  '*.accessToken',
  '*.refreshToken',
  '*.token',
  '*.survey',
  '*.note',
  '*.currentProducts',
  '*.image',
  '*.buffer',
];

interface SerializedReq {
  method?: string;
  url?: string;
}

export function buildLoggerParams(
  config: AppConfig,
  destination: NodeJS.WritableStream = process.stdout,
): Params {
  return {
    pinoHttp: [
      {
        level: config.logLevel,
        genReqId: (req: IncomingMessage) => (req as AppRequest).id,
        quietReqLogger: true,
        customAttributeKeys: { reqId: 'requestId', responseTime: 'durationMs' },
        serializers: {
          req: (req: SerializedReq) => ({ method: req.method, url: req.url }),
          res: (res: { statusCode?: number }) => ({ status: res.statusCode }),
        },
        // 요청 시작·완료 때 두 번 불린다. route는 라우팅이 끝난 완료 시점에만 정확하다.
        customProps: (req: IncomingMessage, res: ServerResponse) => {
          const appReq = req as AppRequest;
          const route: unknown = res.headersSent ? appReq.route?.path : undefined;
          return {
            ...(appReq.user ? { userId: appReq.user.id } : {}),
            ...(typeof route === 'string' ? { route } : {}),
          };
        },
        // 500(미처리)만 error. 503·504는 AI 비가용·대기열 등 예상된 상태라 warn
        customLogLevel: (_req: IncomingMessage, res: ServerResponse, err?: Error) =>
          err || res.statusCode === 500 ? 'error' : res.statusCode >= 500 ? 'warn' : 'info',
        // pino-http가 5xx에 붙이는 합성 err(의미 없는 스택)는 뺀다. 실제 예외는 전역 필터가 남긴다.
        customErrorObject: (
          _req: IncomingMessage,
          _res: ServerResponse,
          _err: Error,
          val: Record<string, unknown>,
        ) => {
          const { err: _synthetic, ...rest } = val;
          return rest;
        },
        redact: { paths: REDACT_PATHS, censor: '[REDACTED]' },
      },
      destination,
    ],
  };
}
