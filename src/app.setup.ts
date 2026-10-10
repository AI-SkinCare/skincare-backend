import type { NestExpressApplication } from '@nestjs/platform-express';
import { DocumentBuilder, SwaggerModule } from '@nestjs/swagger';
import cookieParser from 'cookie-parser';
import helmet from 'helmet';
import { requestIdMiddleware } from './common/middleware/request-id.middleware';
import type { AppConfig } from './config/app-config';
import { API_PREFIX, REFRESH_COOKIE_NAME } from './config/constants';

/**
 * main.ts와 e2e 테스트가 같이 쓰는 HTTP 설정 (app.init() 전에 호출).
 * X-Request-Id 미들웨어를 가장 먼저 등록해 모든 응답(에러 포함)에 헤더가 붙게 한다.
 */
export function configureApp(app: NestExpressApplication, config: AppConfig): void {
  app.use(requestIdMiddleware);
  // nginx·터널 뒤 — 클라이언트 IP는 CF-Connecting-IP 우선 (common/utils/client-ip.ts)
  app.set('trust proxy', config.trustProxy);
  // JSON API라 CSP는 의미가 적고 Swagger UI를 막으므로 끈다
  app.use(helmet({ contentSecurityPolicy: false }));
  app.use(cookieParser());
  if (config.corsOrigin) {
    // iOS 앱은 Origin을 보내지 않아 CORS와 무관. 웹에서 부를 때만 의미가 있다.
    app.enableCors({ origin: config.corsOrigin, credentials: true });
  }
  app.setGlobalPrefix(API_PREFIX);
  setupSwagger(app);
}

/** Swagger: /api/docs, JSON /api/docs-json (prefix 밖). 앱이 이 JSON으로 타입을 만든다 */
export function setupSwagger(app: NestExpressApplication): void {
  const document = SwaggerModule.createDocument(
    app,
    new DocumentBuilder()
      .setTitle('skincare-backend API')
      .setDescription(
        '피부 지표 측정·성분 추천 서비스 백엔드. 응답은 항상 `{ success, data }` / `{ success: false, error }` 봉투. 명세: skincare-ai/docs/project/03-api-spec.md, docs/TRD.md',
      )
      .setVersion('0.1.0')
      .addServer('/', '현재 서버')
      .addBearerAuth()
      .addCookieAuth(REFRESH_COOKIE_NAME)
      .build(),
  );
  SwaggerModule.setup('api/docs', app, document, { jsonDocumentUrl: 'api/docs-json' });
}
