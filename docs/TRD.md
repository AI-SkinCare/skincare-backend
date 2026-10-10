# skincare-backend 기술 요구사항 명세서 (TRD)

| 항목 | 내용 |
|---|---|
| 대상 | `skincare-backend` (NestJS API · PostgreSQL · 추천 작업 큐 · Docker Compose) |
| 근거 문서 | SC-01 요구사항 정의서, SC-02 시스템 설계서, SC-03 API 명세서, SC-04 DB 설계서, SC-05 화면 설계서, SC-06 협업 규칙, SC-07 WBS, SC-08 테스트 계획서 (모두 v1.0, 2026-10-01, 원본 `skincare-ai/docs/project/`) |
| 작성 | 2026-10-10 · 구현 담당 이현승 · 리뷰 정도희 |
| 용도 | Claude Code가 이 문서만 보고 백엔드를 구현·테스트할 수 있도록 팀 문서의 백엔드 관련 내용을 한곳에 모으고, 구현에 필요한 세부 결정을 보탠 문서. |

**표기**

- 표시 없음 — 팀 문서(SC-0x) 그대로
- **[구현 결정]** — 팀 문서에 없거나 모호해서 이 문서가 정한 것. API 계약(경로·필드·코드)에는 영향 없음
- **[앱 협의]** — iOS 앱 담당(정도희)과 합의해 API 계약에 **더한** 것. 기존 동작은 그대로 두는 추가 변경이며, SC-03에도 반영해야 한다(v1.0.1 단순 추가, §18.3)

**우선순위**: 팀 문서 > 이 문서 > 코드에서의 추측. 이 문서와 팀 문서가 다르면 팀 문서를 따르고 이 문서를 고친다.

**클라이언트**: 팀 문서에는 React 웹으로 적혀 있지만 실제 클라이언트는 **Swift iOS 앱**이다. 백엔드 API·인증은 팀 문서 그대로이고, 네이티브 앱용 리프레시 토큰 본문 전달(`X-Client`, §9.1)만 [앱 협의]로 더했다. React·Vite 관련 내용(Vite proxy, openapi-typescript, 브라우저 저장소 등)은 프론트 쪽 사항이라 백엔드와 무관하다. 앱은 `Origin`을 보내지 않으므로 CORS와도 무관하다.

**개정 이력**

| 날짜 | 내용 |
|---|---|
| 2026-10-10 | 최초 작성 |
| 2026-10-10 | [앱 협의] §9 네이티브 앱 리프레시 토큰 본문 전달(`X-Client: ios`) 추가 — SC-03 반영 필요 |
| 2026-10-10 | §12.7·부록 B: fixture를 `skincare-ai/docs/fixtures/` 실제 출력으로 교체 |
| 2026-10-10 | §8.4 배포 서버 시드 절차, §16.1 실제 AI 계약 테스트·스모크, §17.4 스모크 단계, §18.1 Phase 7 |

---

## 1. 개요

### 1.1 서비스

스마트폰으로 촬영한 얼굴 사진 1장 + 설문으로 부위별 피부 지표 4종(수분·탄력·색소침착·모공)을 0–100으로 보여주고, 그 결과와 설문을 근거로 스킨케어 성분·루틴을 추천 이유와 함께 제시하는 모바일 웹 서비스.

질환 진단, 치료·처방·의약품 안내, 제품 판매·광고는 **범위 밖**이다(식약처 웰니스 판단기준, 화장품법).

### 1.2 백엔드의 책임

| 책임 | 내용 |
|---|---|
| 인증 | 가입(동의 4종), 로그인, 액세스/리프레시 토큰, 로그아웃, 탈퇴 |
| 분석 API | 업로드 수신 → AI 측정(동기, 약 3초) → 저장 → 추천 대기열 등록 → 결과·이력·추이·삭제 |
| 추천 작업 큐 | PostgreSQL 기반 큐, 워커 1개가 GPU 추천(약 50초)을 한 건씩 요청 |
| AI 서버 중계 | 클라이언트는 AI 서버를 모른다. 주소·토큰은 백엔드 환경변수에만 있다 |
| 검증·변환 | AI 응답의 열거형 검증, 0–1 → 0–100 변환, 점수·등급 계산, snake_case → camelCase |
| 상태 | AI 가용성(30초 헬스체크), 대기열 길이 |
| 배포 구성 | Dockerfile, docker-compose.yml(web·backend·db·cloudflared), DB 마이그레이션, CI |

### 1.3 백엔드가 하지 않는 것

iOS 앱(Swift, 정도희), nginx 설정 세부(정도희), AI 추론·금칙 필터·면책 문구 생성(AI 서버, 김우주), Cloudflare 터널 운영, DB 백업 cron(정도희, WBS 6.2).

### 1.4 일정 (오늘 10/10 기준)

| 날짜 | 이벤트 | 백엔드 기준 |
|---|---|---|
| 10/12 | **M2 골격** | fixture 모드로 가입 → 촬영 → 설문 → 결과 화면까지 로컬 동작 |
| 10/13~ | **명세 동결** | 호환이 깨지는 API·DB 변경 금지 |
| 10/18 | **M3 v0.1.0** | 배포 서버에서 실제 AI 서버(sLLM)와 전체 흐름 동작 |
| 10/20–21 | 중간발표 | AI 서버 상시 구동(10/19–10/21) |
| 11/10 | M5 기능 완성 | 이력·추이 등 S 우선순위 |
| 11/24 | M6 v0.2.0 | 통합·부하 테스트 통과 |
| 12/9 | M7 v1.0.0 최종발표 | — |

---

## 2. 설계 원칙 (SC-02 §1)

1. **클라이언트는 AI 서버를 모른다.** 모든 요청은 백엔드를 거친다. `AI_BASE_URL`·`AI_INTERNAL_TOKEN`은 백엔드 환경변수에만 있다.
2. **측정은 빠르게, 추천은 줄 세워서.** 측정은 `POST /analyses` 요청 안에서 끝내고, 추천은 큐에 넣어 워커가 GPU에 한 건씩 보낸다.
3. **얼굴 이미지는 디스크에 닿지 않는다.** 메모리 버퍼로만 받아 AI 서버로 넘기고 측정 직후 버린다. 추천 단계에는 이미지가 필요 없다.
4. **AI 서버가 꺼져도 서비스는 산다.** 분석 제출만 막고 로그인·이력 등 나머지는 정상 동작한다.
5. **AI 출력은 그대로 믿지 않는다.** 열거형 검증과 0–100 변환은 백엔드가 한 번 더 한다.

측정과 추천을 나눈 이유: 한 번에 호출하면 사용자가 50초 넘게 아무것도 못 보고, 둘 다 큐에 넣으면 이미지를 임시 저장해야 해서 원칙 3을 어긴다.

---

## 3. 시스템 구성

```
[사용자 iPhone: Swift 앱]
        │ HTTPS
        ▼
┌──────────── 정도희 자택 서버 · Docker Compose ────────────┐
│ cloudflared (app.<도메인> → web:80)                        │
│   → web: nginx 1.27 (/api → backend 프록시, 업로드 12MB 제한)     │
│       → backend: NestJS :3000 (외부 비공개)                │
│           ├─ db: PostgreSQL 16 (볼륨 pgdata, 외부 비공개)   │
│           └─ HTTPS + X-Internal-Token ─────────────┐       │
└────────────────────────────────────────────────────│───────┘
                                                     ▼
┌──────── 김우주 PC · Windows 11 · RTX 5070 Ti 16GB ────────┐
│ cloudflared (ai.<도메인> → localhost:8000)                 │
│   → FastAPI 추론 서버: 회귀 모델(현재 stub) + 추천 sLLM(v2) │
└────────────────────────────────────────────────────────────┘
```

- 백엔드 인스턴스는 **1개**다. 메모리 상태(헬스 결과, 진행 중 제출 잠금, 로그인 제한 카운터, fixture 시나리오 맵)를 인스턴스 안에 둬도 된다.
- AI 서버는 조장 PC에서만 돈다. 학습 중이거나 PC가 꺼지면 `aiAvailable=false`가 정상 상태다.
- 도메인이 없으면(결정 D3) 임시 터널 주소를 쓰며, 재시작할 때마다 `AI_BASE_URL`을 바꾸고 백엔드를 재시작해야 한다.

### 3.1 분석 처리 시퀀스

```
FE ──POST /analyses(image, survey, deviceType)──▶ BE
BE: 인증 · 요청 제한 · 대기열 상한 · 형식 검사
BE ──POST /infer/skin-metrics(image, 20초)──▶ AI
  ├ [얼굴 미검출/품질 미달] AI ─422 E2001/E2002─▶ BE ─422(분석 생성 안 함)─▶ FE (재촬영)
  └ [성공] AI ─ROI별 지표(0~1), is_stub─▶ BE
       BE: 이미지 버퍼 폐기, 종합 점수 계산
       BE ─▶ DB: analyses(status=queued) + skin_metrics + surveys 저장
       BE ─202 {analysisId, status: queued, queuePosition, estimatedWaitSec}─▶ FE
FE ──GET /analyses/{id} (3초, 3분 후 10초, 15분 후 중단)──▶ BE ─측정 결과 + 추천 상태─▶ FE
Worker ◀─ DB: 가장 오래된 queued 1건 획득
Worker ──POST /infer/recommend(지표* + 설문, 130초)──▶ AI ─피부 타입·고민·성분·루틴·근거·면책─▶ Worker
Worker ─▶ DB: recommendations 저장, status=done
FE ──GET /analyses/{id}──▶ BE ─전체 결과─▶ FE
(* is_demo=true면 metrics: null)
```

---

## 4. 기술 스택

| 영역 | 선택 | 비고 |
|---|---|---|
| 런타임 | Node.js 20, TypeScript(strict) | 팀 문서 기준 |
| 프레임워크 | NestJS 10 (`@nestjs/platform-express`) | 11로 올리지 않음 |
| ORM·DB | TypeORM 0.3 + `pg`, PostgreSQL 16 | `synchronize: false` 고정 |
| 검증 | `class-validator`, `class-transformer` | |
| 문서 | `@nestjs/swagger` | `/api/docs`, JSON `/api/docs-json` (앱은 이 JSON으로 swift-openapi-generator 등을 써서 타입을 만들 수 있음) |
| 설정 | `@nestjs/config` + 기동 시 env 검증 | [구현 결정] 검증 라이브러리는 Joi 또는 class-validator 중 택1 |
| 인증 | `@nestjs/jwt`, `@nestjs/passport`, `passport-jwt`, `cookie-parser` | |
| 해시 | `bcrypt`(네이티브, `$2b$` 접두) | [구현 결정] `bcryptjs`는 구버전이 `$2a$`를 만들어 TC-SEC-02와 어긋날 수 있음 |
| 업로드 | multer `memoryStorage` (Nest `FileInterceptor`) | 디스크 옵션 사용 금지 |
| 스케줄 | `@nestjs/schedule` | 워커 폴링, 헬스체크, 장기 대기 만료, 토큰 정리 |
| ID | ULID (`ulidx` 등) | [구현 결정] |
| 이미지 헤더 | `image-size` (버퍼에서 가로·세로만 읽음) | [구현 결정] 디코딩·저장 없음 |
| AI HTTP | Node 내장 `fetch` + `FormData`/`Blob` + `AbortSignal.timeout` | [구현 결정] 추가 의존성 없음 |
| 로깅 | `nestjs-pino`(pino) JSON 로그 + redact | [구현 결정] |
| 요청 컨텍스트 | `AsyncLocalStorage`(node 내장) 또는 `nestjs-cls` | [구현 결정] requestId 전파 |
| 테스트 | Jest(단위), Jest + supertest(e2e), PostgreSQL 서비스 컨테이너 | |
| 부하 | k6 (`load/`) | WBS 4.8, 11/10~ |
| 린트 | NestJS 기본 ESLint + Prettier(printWidth 100, singleQuote) | |

Redis·BullMQ 등 별도 큐 인프라는 쓰지 않는다(SC-02 §4.2).

---

## 5. 프로젝트 구조 [구현 결정]

10/10 구현 기준 실제 구조. 파일을 옮기거나 더하면 이 트리도 고친다.

```
skincare-backend/
├─ CLAUDE.md · README.md        # README 첫 줄: skincare-ai/docs/project/ 링크
├─ docs/TRD.md                  # 이 문서
├─ .env.example                 # 가짜 값만
├─ Dockerfile · docker/start.sh # 시작 시 마이그레이션 → 앱
├─ docker-compose.yml · nginx/default.conf
├─ .github/                     # ISSUE_TEMPLATE·PR 템플릿(skincare-ai에서 복사), workflows/ci.yml
├─ load/                        # k6 스크립트(perf-01·03·04), lib/common.js, README (Phase 7)
├─ src/
│  ├─ main.ts                   # 로거, configureApp, shutdown hooks, 0.0.0.0 listen
│  ├─ app.setup.ts              # X-Request-Id(맨 먼저)·trust proxy·helmet·쿠키·CORS·prefix·Swagger (e2e와 공유)
│  ├─ app.module.ts             # 전역 필터·봉투 인터셉터·ValidationPipe(422 E4002)
│  ├─ config/                   # app-config.ts(env 스키마·검증 → 타입 있는 APP_CONFIG), constants.ts
│  ├─ database/
│  │  ├─ data-source.ts         # TypeORM CLI용
│  │  ├─ typeorm-options.ts · entities.ts · transformers.ts(NUMERIC·BIGINT) · raw-query.ts
│  │  ├─ migrations/            # {타임스탬프}-{동사}{대상}.ts
│  │  ├─ seeds/                 # seed.ts(npm run seed), seed-data.ts(e2e와 공유)
│  │  └─ scripts/latency-report.ts  # 응답 시간 분포 (npm run report:latency)
│  ├─ common/
│  │  ├─ enums/                 # 부록 A 단일 출처 (코드 ↔ AI 라벨)
│  │  ├─ errors/                # error-codes.ts, api.exception.ts, api-exception.filter.ts
│  │  ├─ interceptors/ · middleware/ · logging/ · swagger/
│  │  ├─ validation/            # 검증 오류 → detail.fields, ParseUlidPipe
│  │  ├─ http/app-request.ts    # req.id·req.user 타입
│  │  ├─ utils/                 # time.ts(KST·ISO), client-ip.ts, ulid.ts, sleep.ts(지연 주입 토큰)
│  │  └─ common.module.ts       # SLEEP 제공
│  ├─ auth/                     # controller, service, refresh-token.service(교체·재사용 감지·정리 배치),
│  │                            # jwt.strategy, guards/(jwt-auth, login-rate), login-rate-limiter,
│  │                            # refresh-cookie.ts, client-type.ts([앱 협의] X-Client), token-hash.ts, dto/
│  ├─ users/                    # controller, service, user.entity
│  ├─ analyses/
│  │  ├─ analyses.controller.ts · analyses.service.ts
│  │  ├─ analysis-presenter.ts  # DB 행 → API 응답
│  │  ├─ scoring.ts             # 점수·등급 순수 함수 (§11)
│  │  ├─ survey.ts              # survey 파싱·검증·정규화
│  │  ├─ guards/                # analysis-limit(+submission-lock), queue-capacity, ai-availability
│  │  ├─ pipes/                 # analysis-submission.pipe(처리 순서 6), image-signature
│  │  ├─ dto/ · entities/
│  ├─ recommendation/
│  │  ├─ recommendation.worker.ts
│  │  ├─ recommendation-queue.repository.ts + .module.ts  # §13 SQL (analyses·system도 사용)
│  │  └─ queue-maintenance.service.ts        # 장기 대기 만료, 기동 복구
│  ├─ ai-client/
│  │  ├─ ai-client.interface.ts · ai-client.module.ts (AI_CLIENT 토큰)
│  │  ├─ http-ai-client.ts · fixture-ai-client.ts · fixture-scenario.store.ts
│  │  ├─ ai-error.ts            # 분류: http / connection / timeout / invalid_response
│  │  ├─ ai-error.mapper.ts     # §12.5 표
│  │  ├─ ai-response.schemas.ts # AI 응답 검증
│  │  └─ ai-transform.ts        # 설문·지표 변환, 라벨→코드
│  └─ system/                   # ai-health.service.ts, system.controller.ts
└─ test/
   ├─ fixtures/ai/              # skin-metrics.ok.json, recommend.ok.json (skincare-ai 실제 출력)
   ├─ fixtures/images.ts        # 합성 이미지 생성 (실제 얼굴 사진 커밋 금지)
   ├─ e2e/                      # *.e2e-spec.ts, helpers/(test-app, factories, capture-stdout), global-setup
   ├─ smoke/smoke-flow.ts       # npm run smoke
   └─ jest-e2e.json
```

파일 이름은 kebab-case (`ai-client.service.ts`, `analysis-presenter.ts`).

---

## 6. 환경변수

`.env.example`에는 가짜 값만 커밋하고 `.env`는 `.gitignore`. 실제 값은 담당자에게 1:1로만 전달한다. 비밀키 생성: `openssl rand -base64 48`.

| 변수 | 예 | 설명 |
|---|---|---|
| `NODE_ENV` | development \| test \| production | [구현 결정] production에서만 fixture 헤더 무시, 시드 금지 |
| `PORT` | 3000 | |
| `DATABASE_URL` | `postgres://skincare:***@db:5432/skincare` | |
| `JWT_ACCESS_SECRET` / `JWT_REFRESH_SECRET` | 48바이트 난수 | 서로 다른 값 (기동 시 검증) |
| `CORS_ORIGIN` | `https://app.<도메인>` | 로컬 `http://localhost:5173`. iOS 앱은 CORS와 무관(웹에서 부를 때만 의미) |
| `AI_MODE` | fixture \| real | 로컬 기본 fixture |
| `AI_BASE_URL` | `https://ai.<도메인>` | real일 때 필수 |
| `AI_INTERNAL_TOKEN` | 48바이트 난수 | AI 서버와 같은 값. real일 때 필수 |
| `AI_SKIN_TIMEOUT_MS` | 20000 | |
| `AI_REC_TIMEOUT_MS` | 130000 | AI 내부 120초보다 길게 |
| `AI_HEALTH_INTERVAL_MS` | 30000 | |
| `REC_WORKER_CONCURRENCY` | 1 | **1 외의 값이면 기동 실패** [구현 결정] |
| `REC_QUEUE_LIMIT` | 10 | queued 건수 상한 (AI 서버 QUEUE_LIMIT과 별개) |
| `REC_QUEUE_MAX_WAIT_MIN` | 60 | 초과 시 failed E3001 |
| `ANALYSIS_DAILY_LIMIT` | 20 | KST 00:00 기준. 스테이징 성능 테스트 때 100 |
| `LOGIN_RATE_PER_MIN` | 10 | IP당, CF-Connecting-IP 기준 |
| `TRUST_PROXY` | true | nginx·터널 뒤 실제 IP |
| `AI_FIXTURE_SCENARIO` | ok | ok \| no-face \| rec-timeout \| rec-slow \| ai-down |
| `COOKIE_SECURE` | true | [구현 결정] production에선 항상 true. 로컬 HTTP 개발에서만 false 허용 |
| `LOG_LEVEL` | info | [구현 결정] |
| `SEED_PASSWORD` | (개발용 값) | [구현 결정] 시드 계정 공통 비밀번호. production에선 시드 자체가 거부됨 |

추가 규칙 [구현 결정]:

- 기동 시 검증: 필수값 누락, `AI_MODE=real`인데 `AI_BASE_URL`/`AI_INTERNAL_TOKEN` 없음, 두 JWT 비밀키가 같음, `REC_WORKER_CONCURRENCY≠1` → 즉시 종료.
- 토큰 수명(액세스 3600초, 리프레시 14일), 워커 폴링 1초, 재시도 대기 5초, 장기 대기 점검 1분은 코드 상수.

---

## 7. 공통 규약

### 7.1 경로

- 전역 prefix `api/v1` → 로컬 `http://localhost:3000/api/v1`, 운영 `https://app.<도메인>/api/v1`.
- Swagger: `/api/docs`, JSON `/api/docs-json` (prefix 밖). 구현 후에는 Swagger가 실제 동작 기준이며, SC-03과 다르면 둘 중 하나를 고치는 PR을 올린다.
- 경로는 소문자 복수형 kebab-case.

### 7.2 응답 봉투

```json
// 성공
{ "success": true, "data": { } }
// 실패
{ "success": false, "error": { "code": "E2001", "message": "얼굴을 찾지 못했어요. 정면에서 다시 찍어 주세요.", "detail": null } }
```

- 성공 봉투는 전역 인터셉터가 씌운다. **204는 본문 없음.**
- 실패는 전역 예외 필터 하나가 만든다. 서비스 코드는 `throw new ApiException(ErrorCode.E4003)` 형태만 쓰고, Nest 기본 예외(`NotFoundException` 등)·라이브러리 예외도 필터에서 아래 표의 코드로 바꾼다. 매핑되지 않는 예외는 500 E4001 + ERROR 로그(스택·requestId 포함, 요청 본문 제외).
- `message`는 **사용자에게 그대로 보여줘도 되는 문장**, `detail`은 개발용(화면 비표시).
- Swagger에 봉투 구조가 그대로 나오도록 응답 데코레이터(`allOf` + `getSchemaPath`)를 만든다 — 앱이 타입을 만들 때 이것에 의존한다 [구현 결정].

### 7.3 에러 코드

| 코드 | HTTP | 의미 | 기본 message | 출처 |
|---|---|---|---|---|
| E1001 | 401 | 토큰 없음·만료·위조 | 로그인이 필요해요. 다시 로그인해 주세요. | [구현 결정] |
| E1002 | 403 | 권한 없음 | 접근 권한이 없어요. | [구현 결정] |
| E1003 | 401 | 이메일 또는 비밀번호 불일치 | 이메일 또는 비밀번호가 맞지 않아요. | SC-05 |
| E1004 | 409 | 이미 가입된 이메일 | 이미 가입된 이메일이에요. | [구현 결정] |
| E2001 | 422 | 얼굴 미검출 | 얼굴을 찾지 못했어요. 정면에서 다시 찍어 주세요. | SC-03 |
| E2002 | 422 | 이미지 품질 미달(흐림·과노출·저조도) | 사진이 흐리거나 너무 어두워요. 밝은 곳에서 다시 찍어 주세요. | SC-05 변형 |
| E2003 | 413 | 이미지 10MB 초과 | 사진 용량이 너무 커요. 10MB 이하로 올려 주세요. | [구현 결정] |
| E2004 | 415 | JPEG·PNG 아님 | JPEG 또는 PNG 사진만 올릴 수 있어요. | [구현 결정] |
| E3001 | 503 | AI 서버 응답 없음·설정 오류, 또는 60분 넘게 처리 못 함 | 지금은 분석 서버 점검 중이에요. 잠시 후 다시 시도해 주세요. | SC-05 변형 |
| E3002 | 504 | 추론 타임아웃 | 추천을 만들지 못했어요. 다시 시도해 주세요. | SC-02 §9 |
| E3003 | 503 | 대기열 가득 참 | 지금 요청이 많아요. 잠시 후 다시 시도해 주세요. | SC-02 §9 |
| E4001 | 500 | 내부 오류 | 문제가 생겼어요. 잠시 후 다시 시도해 주세요. | SC-05 (요청 ID는 앱이 덧붙임) |
| E4002 | 422 | 요청 형식 오류(검증 실패) | 입력값을 확인해 주세요. | [구현 결정] |
| E4003 | 404 | 리소스 없음(남의 것 포함) | 결과를 찾을 수 없어요. | SC-03 |
| E4004 | 429 | 요청 제한 초과 | 사유별 문구 (아래) | [구현 결정] |
| E4005 | 409 | 지금 상태에서 할 수 없는 요청 | 지금은 할 수 없는 요청이에요. 화면을 새로고침해 주세요. | [구현 결정] |

E4004 사유별 message·detail [구현 결정]:

| 사유 | detail.reason | message | Retry-After |
|---|---|---|---|
| 진행 중 분석 있음 | `in_progress` | 진행 중인 분석이 끝난 뒤에 다시 시도해 주세요. | 30 |
| 일일 한도 | `daily_limit` | 오늘 분석할 수 있는 횟수를 모두 사용했어요. 내일 다시 시도해 주세요. | 다음 KST 00:00까지 초 |
| 로그인 시도 | `login_rate` | 로그인 시도가 너무 많아요. 1분 뒤에 다시 시도해 주세요. | 해당 창이 끝날 때까지 초 |

- E3001에는 `Retry-After: 30`, E3003에는 `Retry-After: 60`을 붙인다 (SC-03 재시도 열) [구현 결정].
- 문구는 SC-05 §4 문구 규칙을 지킨다: "진단·치료·처방·완치·개선됩니다·효과가 있습니다·질환명·정상/비정상" 금지. 새 문구는 PR에서 손승우 확인.
- E4002 `detail` 형식 [구현 결정]: `{ "fields": [ { "field": "survey.gender", "reason": "required" } ] }` (TC-ANA-02: detail에 gender). 그 외 코드의 detail은 `null`. production에서 E4001 detail은 항상 `null`.
- `ValidationPipe`의 기본 400을 쓰지 않는다. `exceptionFactory`로 **422 E4002**를 던진다. 옵션: `transform: true`, `whitelist: true`(모르는 필드는 제거), `forbidNonWhitelisted: false` [구현 결정].

### 7.4 필드·시간·ID·페이지

| 항목 | 규칙 |
|---|---|
| JSON 필드 | camelCase. 열거형 **값**은 소문자 snake_case (`full_face`, `cheek_left`) |
| DB | snake_case. 엔티티마다 `name:`을 명시 [구현 결정] (네이밍 전략 패키지 추가 안 함) |
| 시간 | 저장 `TIMESTAMPTZ`(UTC). 응답 ISO 8601 UTC `2026-10-15T09:12:33Z` — 밀리초 제거 [구현 결정] (`common/utils/time.ts`의 `toIsoUtc`) |
| KST | 일일 한도·배치 시각에만 사용. 한국은 서머타임이 없으므로 UTC+9 고정 계산 |
| 분석 ID | ULID 26자. 경로 파라미터가 ULID 형식(`^[0-9A-HJKMNP-TV-Z]{26}$`)이 아니면 **404 E4003** (422 아님 — 존재 여부를 흘리지 않음) [구현 결정] |
| 사용자 ID | BIGSERIAL 숫자 |
| 페이지 | `?page=1&size=20`, page ≥ 1, 1 ≤ size ≤ 50, 위반 시 E4002. 응답 `data.page = { page, size, totalItems, totalPages }` (`totalPages = ceil(totalItems/size)`, 0건이면 0) |

### 7.5 X-Request-Id

- 미들웨어에서 요청 헤더 `X-Request-Id`가 `^[A-Za-z0-9._-]{1,64}$`이면 그대로, 아니면 UUID v4 생성 [구현 결정]. iOS 앱은 요청마다 생성해서 보낸다 [앱 협의].
- **모든 응답(에러 포함)**에 `X-Request-Id` 헤더. 백엔드 → AI 호출에도 같은 값 전달.
- 워커 작업은 요청 맥락이 없으므로 작업마다 새 UUID를 만들고 로그에 `analysisId`를 같이 남긴다.

### 7.6 로깅 (NFR-PRI-02, NFR-OBS-01)

- JSON 로그. 공통 필드: `requestId`, `method`, `route`, `status`, `durationMs`, `userId`(있으면).
- **절대 기록하지 않음**: 이미지(버퍼·base64·파일명 포함), 설문 원문(`note`, `currentProducts` 등 전체), 비밀번호, 액세스·리프레시 토큰, 쿠키, `X-Internal-Token`, 요청/응답 본문 전체.
- pino `redact`: `req.headers.authorization`, `req.headers.cookie`, `res.headers["set-cookie"]`, `*.password`, `*.accessToken`, `*.refreshToken`, `*.survey`, `*.note`.
- 이메일은 마스킹 `d***@gmail.com` (첫 글자 + `***` + `@도메인`).
- AI 오류는 HTTP 상태·에러 코드·소요 시간만 남기고 AI 응답 본문 전체는 남기지 않는다. AI 응답 검증 실패는 실패한 필드 경로와 이유만 남긴다.
- ERROR 로그 대상: AI 401(내부 토큰 불일치), AI 422 E4002(백엔드 변환 버그), AI 응답 스키마 위반, 미처리 예외.

---

## 8. DB (SC-04)

PostgreSQL 16, 스키마 `public`, UTF-8, 시간대 UTC.

### 8.1 DDL (마이그레이션의 기준)

```sql
CREATE TABLE users (
  id                  BIGSERIAL PRIMARY KEY,
  email               VARCHAR(255) NOT NULL UNIQUE,
  password_hash       VARCHAR(100) NOT NULL,
  nickname            VARCHAR(20)  NOT NULL,
  terms_agreed_at     TIMESTAMPTZ  NOT NULL,
  privacy_agreed_at   TIMESTAMPTZ  NOT NULL,
  image_consent_at    TIMESTAMPTZ  NOT NULL,
  over14_confirmed_at TIMESTAMPTZ  NOT NULL,
  created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE refresh_tokens (
  id          BIGSERIAL PRIMARY KEY,
  user_id     BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash  CHAR(64)    NOT NULL UNIQUE,      -- SHA-256(토큰) hex. 원문 저장 금지
  expires_at  TIMESTAMPTZ NOT NULL,              -- 발급 + 14일
  revoked_at  TIMESTAMPTZ,                       -- 로그아웃·교체 시각
  replaced_by BIGINT,                            -- 교체한 새 토큰 id (재사용 감지용)
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens(user_id);

CREATE TABLE analyses (
  id                  CHAR(26)     PRIMARY KEY,  -- ULID, URL에 노출
  user_id             BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  status              VARCHAR(16)  NOT NULL DEFAULT 'queued'
                      CHECK (status IN ('queued','recommending','done','failed')),
  device_type         VARCHAR(16)  NOT NULL
                      CHECK (device_type IN ('smartphone','tablet','camera')),
  is_demo             BOOLEAN      NOT NULL,     -- AI is_stub 그대로
  confidence          NUMERIC(4,3) NOT NULL CHECK (confidence BETWEEN 0 AND 1),
  calibration_applied BOOLEAN      NOT NULL DEFAULT false,
  overall_score       SMALLINT     NOT NULL CHECK (overall_score BETWEEN 0 AND 100),
  skin_model_version  VARCHAR(40)  NOT NULL,
  measure_ms          INTEGER      NOT NULL,     -- 측정 API 왕복
  queued_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),  -- 재요청 시 갱신
  rec_started_at      TIMESTAMPTZ,               -- 워커 획득 시각
  completed_at        TIMESTAMPTZ,               -- done·failed 확정 시각
  queue_wait_ms       INTEGER,                   -- rec_started_at − queued_at
  recommend_ms        INTEGER,                   -- 추천 API 왕복
  attempt_count       SMALLINT     NOT NULL DEFAULT 0,  -- 추천 호출 횟수(재시도 포함)
  error_code          VARCHAR(10),
  created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_analyses_user_created ON analyses(user_id, created_at DESC);
CREATE INDEX idx_analyses_queue ON analyses(queued_at) WHERE status = 'queued';

CREATE TABLE skin_metrics (
  analysis_id  CHAR(26)     NOT NULL REFERENCES analyses(id) ON DELETE CASCADE,
  roi          VARCHAR(16)  NOT NULL,
  moisture     NUMERIC(5,4) NOT NULL CHECK (moisture BETWEEN 0 AND 1),
  elasticity   NUMERIC(5,4) NOT NULL CHECK (elasticity BETWEEN 0 AND 1),
  pigmentation NUMERIC(5,4) NOT NULL CHECK (pigmentation BETWEEN 0 AND 1),  -- 높을수록 나쁨
  pore         NUMERIC(5,4) NOT NULL CHECK (pore BETWEEN 0 AND 1),          -- 높을수록 나쁨
  PRIMARY KEY (analysis_id, roi)
);

CREATE TABLE surveys (
  analysis_id CHAR(26)    PRIMARY KEY REFERENCES analyses(id) ON DELETE CASCADE,
  gender      VARCHAR(8)  NOT NULL,
  age_band    VARCHAR(4)  NOT NULL,
  skin_type   VARCHAR(16),            -- 사용자 자가응답 코드
  payload     JSONB       NOT NULL    -- 제출한 설문 전체(클라이언트 코드)
);

CREATE TABLE recommendations (
  analysis_id   CHAR(26)    PRIMARY KEY REFERENCES analyses(id) ON DELETE CASCADE,
  skin_type     VARCHAR(16) NOT NULL,  -- 도출된 피부 타입 **코드**
  concerns      JSONB       NOT NULL,  -- 고민 코드 배열 ["pore","pigmentation"]
  ingredients   JSONB       NOT NULL,  -- [{name, purpose, priority}]
  routine       JSONB       NOT NULL,  -- [{step, time, action}]
  reasoning     JSONB       NOT NULL,  -- 문자열 배열
  answer        TEXT        NOT NULL,
  disclaimer    TEXT        NOT NULL,
  input_notes   JSONB       NOT NULL DEFAULT '[]',
  filtered      BOOLEAN     NOT NULL,
  used_fallback BOOLEAN     NOT NULL,
  model_version VARCHAR(40) NOT NULL,
  inference_ms  INTEGER     NOT NULL,  -- AI 서버 내부 생성 시간
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

0–1 원본을 저장하는 이유: 추천 API에 그대로 다시 넘기고, 최종 성능 분석에서 반올림 손실 없이 쓰기 위해서. 0–100 변환은 응답 시점에 한다.

### 8.2 TypeORM 주의

- **NUMERIC은 문자열로 돌아온다.** `confidence`와 지표 4개 컬럼 모두에 `transformer: { to: (v) => v, from: (v) => (v === null ? null : Number(v)) }`. 빠뜨리면 점수 계산이 문자열 연결로 깨진다.
- BIGSERIAL(`users.id`, `refresh_tokens.id`)도 `pg`가 문자열로 돌려준다. 엔티티에서 number로 변환하는 transformer를 쓰고, JWT `sub`에도 number를 넣는다 [구현 결정].
- `skin_metrics`는 복합 PK(`analysis_id`, `roi`).
- `synchronize: true` 금지(모든 환경). `migrationsRun`은 쓰지 않고 컨테이너 시작 스크립트에서 마이그레이션을 실행한다(§17).
- 상태·열거형 컬럼은 varchar + CHECK. Postgres enum 타입을 만들지 않는다.
- 여러 테이블을 쓰는 작업(분석 생성, 추천 저장)은 하나의 트랜잭션.
- 큐 SQL(§13.3)은 `FOR UPDATE SKIP LOCKED`를 쓰므로 QueryBuilder 대신 raw query로 쓴다.
- 연결 풀 10, 앱 연결에 `statement_timeout` 5초 (마이그레이션·시드 연결은 제외) [구현 결정].

### 8.3 마이그레이션

- TypeORM 마이그레이션만 사용. 파일 이름 `{타임스탬프}-{동사}{대상}.ts` (예: `1727740800000-CreateAnalyses.ts`).
- 첫 마이그레이션은 §8.1 DDL을 그대로 옮긴다(테이블별로 나눠도 됨).
- 스키마를 바꾸는 PR에는 마이그레이션 + SC-04 문서 수정이 함께 들어가야 한다(명세 변경 절차 §18.3).
- npm 스크립트: `migration:run`, `migration:revert`, `migration:generate`, `migration:create`.

### 8.4 시드 (`npm run seed`)

- `NODE_ENV=production`이면 즉시 종료.
- 계정 `test01@skincare.dev` ~ `test40@skincare.dev` (동시 요청 테스트용). 비밀번호는 `SEED_PASSWORD`, 동의 시각 4개 기록.
- `test01`에 **완료(done) 분석 25건** — 날짜를 최근 수 주에 분산, `is_demo=false`(이력·추이 화면 확인용), `full_face` + 일부 ROI 지표, 설문, 추천 행 포함. TC-HIS-01(1쪽 20건, 2쪽 5건)이 이 데이터로 돈다.
- 다시 실행해도 안전하게(시드 계정 삭제 후 재생성) [구현 결정].
- 배포 서버(`NODE_ENV=production`)에서는 시드가 거부된다. 성능 테스트 기간에만 일회용 컨테이너로 명시적으로 넣고 끝나면 지운다 [구현 결정]: `docker compose run --rm -e NODE_ENV=development backend node dist/database/seeds/seed.js` (삭제 명령은 `load/README.md`).

### 8.5 보존·삭제

| 데이터 | 보존 | 삭제 |
|---|---|---|
| 얼굴 원본 이미지 | **저장하지 않음** | 측정 응답 직후 메모리 참조 해제 |
| 계정 | 탈퇴 시까지 | 탈퇴 즉시 (CASCADE로 하위 전부) |
| 분석·지표·설문·추천 | 사용자 삭제·탈퇴 시까지 | 즉시 |
| DB 백업 | 7일 | 03:00 pg_dump (인프라, 정도희) |
| 리프레시 토큰 | 만료 후 7일 | **매일 03:30 KST 배치**: `DELETE FROM refresh_tokens WHERE expires_at < now() - interval '7 days'` |
| 애플리케이션 로그 | 14일 | Docker 로그 로테이션 |

---

## 9. 인증 · 사용자 (auth, users)

### 9.1 토큰 설계

| 항목 | 액세스 토큰 | 리프레시 토큰 |
|---|---|---|
| 형식 | JWT HS256, `JWT_ACCESS_SECRET` | JWT HS256, `JWT_REFRESH_SECRET` [구현 결정] |
| payload | `{ sub: userId }` | `{ sub: userId, jti: 랜덤 16바이트 hex }` |
| 수명 | 1시간 (`expiresIn: 3600`) | 14일 |
| 전달 | 응답 본문 `accessToken` → 앱 메모리 | `Set-Cookie` httpOnly. `X-Client: ios` 요청이면 응답 본문 `refreshToken`도 [앱 협의] |
| 서버 저장 | 없음 | `refresh_tokens.token_hash = SHA-256(토큰 문자열) hex` |

#### 네이티브 앱 전달 방식 (`X-Client`) [앱 협의]

기존 쿠키 방식은 그대로 두고 아래만 더한다.

| 항목 | 규칙 |
|---|---|
| 헤더 | 앱은 모든 요청에 `X-Client: ios`를 보낸다. 값은 앞뒤 공백 제거·소문자로 비교 |
| 응답 | `POST /auth/login`, `POST /auth/refresh`: `X-Client: ios`이면 응답 `data`에 `refreshToken` 추가. 이때도 `Set-Cookie`는 똑같이 보낸다. 헤더가 없거나 다른 값이면 `refreshToken` 필드 없음 (기존과 동일) |
| 요청 | `POST /auth/refresh`, `POST /auth/logout`: 쿠키 `refresh_token`이 없으면 본문 `{ "refreshToken": "…" }`을 읽는다. 둘 다 있으면 **쿠키 우선**. 본문 `refreshToken`이 문자열이 아니면 422 E4002. 이 본문은 `X-Client` 헤더와 관계없이 받는다 |
| 검증 | 본문으로 온 토큰도 쿠키와 **같은 경로**로 검증한다 — 교체·재사용 감지·해시 저장 규칙 그대로 (§9.4) |
| 로그 | 본문 `refreshToken`도 로그 금지 (redact `*.refreshToken`, 본문은 애초에 기록하지 않음) |
| 확장 | Android를 만들면 `X-Client: android`로 같은 방식을 쓴다. 허용 값은 코드 상수 한 곳(`auth/client-type.ts`)에만 두고, 추가할 때는 이 표를 먼저 고친다 |

- 앱은 `refreshToken`을 Keychain에 저장하고 refresh·logout 때 본문으로 보낸다.
- 백엔드는 쿠키를 우선하므로, URLSession이 오래된 쿠키를 자동으로 붙이면 그 쿠키가 쓰인다. 쿠키와 Keychain 값이 어긋나 이미 교체된 토큰이 오면 재사용 감지로 전체 로그아웃되므로, 앱은 auth 요청에서 쿠키 자동 처리를 끄는 것(`httpShouldHandleCookies = false`)을 권장한다 (앱 쪽 설정).
- 브라우저 클라이언트가 없으므로 본문 전달로 httpOnly 쿠키의 보호를 잃지 않는다. 웹 클라이언트가 생기면 웹은 `X-Client`를 보내지 않고 쿠키만 쓴다.

쿠키 (정확히 이 속성):

```
Set-Cookie: refresh_token=<token>; HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth; Max-Age=1209600
```

- 지울 때도 **같은 Path**로 `Max-Age=0`. Path가 다르면 쿠키 저장소가 지우지 않는다.
- `Secure`는 `COOKIE_SECURE`를 따른다. production은 항상 true. 로컬 HTTP(시뮬레이터·실기기)에서 쿠키를 주고받으려면 false로 둔다. (iOS 앱은 본문 `refreshToken`을 쓰므로 쿠키 전달 여부와 관계없이 동작한다.)

`JwtStrategy.validate`: 서명·만료 검증 후 `users`에 해당 id가 있는지 확인한다. 없으면(탈퇴 후 남은 토큰) 401 E1001 [구현 결정]. 토큰 없음·만료·위조 모두 **401 E1001**.

### 9.2 POST /auth/signup — 인증 없음 (FR-AUTH-01)

요청

```json
{
  "email": "user@example.com",
  "password": "Skin!2026",
  "nickname": "우주",
  "agreements": { "terms": true, "privacy": true, "imageProcessing": true, "over14": true }
}
```

| 필드 | 규칙 |
|---|---|
| email | 앞뒤 공백 제거 후 소문자 정규화, 이메일 형식, 최대 255자 |
| password | 8–64자, 영문·숫자·특수문자 중 2종 이상. [구현 결정] 출력 가능한 ASCII(`\x21-\x7E`)만 허용 → bcrypt 72바이트 한계를 넘지 않음 |
| nickname | 앞뒤 공백 제거, 2–20자 |
| agreements.* | 4개 모두 `true`. 하나라도 아니면 **422 E4002** (TC-AUTH-02) |

처리: 검증 → bcrypt(cost 12) → INSERT (동의 시각 4개 = 현재 시각) → 이메일 UNIQUE 위반(23505)이면 **409 E1004** (사전 조회 + 제약 위반 둘 다 처리).

응답 **201** `{ "success": true, "data": { "userId": 42 } }`

테스트: TC-AUTH-01(행·동의 시각 4개), 02(동의 누락 422, 행 없음), 03(대소문자만 다른 이메일 409).

### 9.3 POST /auth/login — 인증 없음 (FR-AUTH-02)

요청 `{ "email": "...", "password": "..." }`

처리 순서:

1. **로그인 제한 가드**: 클라이언트 IP(§15.2)당 1분 고정 창 `LOGIN_RATE_PER_MIN`(10)회. 성공·실패 관계없이 모든 시도를 센다. 초과 시 **429 E4004** (`reason: login_rate`, Retry-After). 인스턴스 메모리 카운터 [구현 결정].
2. 이메일 정규화 → 사용자 조회.
3. 사용자가 없어도 더미 해시와 `bcrypt.compare`를 실행해 응답 시간을 맞춘다 [구현 결정].
4. 불일치 또는 사용자 없음 → **401 E1003** (어느 쪽이 틀렸는지 구분하지 않음).
5. 액세스·리프레시 토큰 발급, 리프레시 해시 INSERT, 쿠키 설정.

응답 **200** + Set-Cookie

```json
{
  "success": true,
  "data": {
    "accessToken": "eyJhbGciOi…",
    "expiresIn": 3600,
    "refreshToken": "eyJhbGciOi…",
    "user": { "id": 42, "email": "user@example.com", "nickname": "우주" }
  }
}
```

`refreshToken`은 `X-Client: ios` 요청일 때만 있다 [앱 협의, §9.1]. 쿠키 값과 같은 토큰이다.

테스트: TC-AUTH-04, TC-SEC-04(같은 IP 11번째 429, 다른 IP 정상).

### 9.4 POST /auth/refresh — 인증 없음 (FR-AUTH-02)

쿠키 `refresh_token`(없으면 본문 `{ "refreshToken": "…" }` [앱 협의, §9.1])을 검증하고 **새 리프레시 토큰으로 교체**한다. 응답은 로그인과 같은 구조 + 새 쿠키 (`X-Client: ios`면 `data.refreshToken`에 새 토큰).

```
1. 쿠키도 본문 refreshToken도 없음               → 401 E1001
2. JWT 서명·만료 검증 실패                     → 401 E1001
3. row = SELECT … WHERE token_hash = sha256(token)
   - 없음                                      → 401 E1001
   - revoked_at IS NOT NULL
       - replaced_by IS NOT NULL  → 재사용 감지:
           UPDATE refresh_tokens SET revoked_at = now()
           WHERE user_id = row.user_id AND revoked_at IS NULL;  (WARN 로그, userId만)
                                               → 401 E1001
       - replaced_by IS NULL (로그아웃으로 폐기) → 401 E1001
   - expires_at < now()                        → 401 E1001
4. 트랜잭션:
   INSERT 새 토큰 → newId
   UPDATE refresh_tokens SET revoked_at = now(), replaced_by = newId
     WHERE id = row.id AND revoked_at IS NULL
   → 영향 0행(동시에 다른 요청이 먼저 교체) = 재사용으로 보고 3의 재사용 처리
5. 새 액세스 토큰 + 새 쿠키, 200
실패 응답에는 항상 쿠키 삭제 헤더를 붙인다 [구현 결정].
```

앱은 401 **E1001**에서만 refresh를 시도하고, 동시에 여러 요청이 401을 받아도 refresh는 한 번만 보낸다(single-flight — 두 번 보내면 재사용으로 판정돼 로그아웃됨). E1003에서는 시도하지 않는다. 앱을 켤 때 refresh로 로그인 상태를 복원한다(SC-02 §5). 백엔드는 E1001과 E1003을 섞지 않는다.

### 9.5 POST /auth/logout — 인증 필요 (FR-AUTH-03)

쿠키의 리프레시 토큰(없으면 본문 `{ "refreshToken": "…" }` [앱 협의, §9.1])이 있고 그 사용자의 것이면 `revoked_at = now()` (`replaced_by`는 NULL 유지). 쿠키 삭제. 토큰이 없어도 **204**. 테스트: TC-AUTH-06(로그아웃 후 이전 토큰으로 refresh → 401 E1001).

### 9.6 GET /users/me — 인증 필요

```json
{ "success": true, "data": { "id": 42, "email": "user@example.com", "nickname": "우주", "createdAt": "2026-10-03T02:11:00Z", "analysisCount": 3 } }
```

`analysisCount` = 해당 사용자의 analyses 행 수(상태 무관) [구현 결정].

### 9.7 DELETE /users/me — 인증 필요 (FR-AUTH-04, S)

`DELETE FROM users WHERE id = $1` → CASCADE로 분석·지표·설문·추천·리프레시 토큰 전부 즉시 삭제. 쿠키 삭제. **204**.
진행 중 추천 작업 "취소": 워커가 처리 중이면 결과 저장 시점에 행이 없으므로 버린다(§13.7). 테스트: TC-AUTH-07(분석 3건 계정 탈퇴 → 6개 테이블 모두 0건).

---

## 10. 분석 API (analyses)

### 10.1 POST /analyses — 인증 필요 (FR-ANA-01·02·05, FR-SUR-01, FR-MEA-04)

요청 `multipart/form-data`

| 필드 | 타입 | 필수 | 규칙 |
|---|---|---|---|
| image | file | O | JPEG/PNG, 10MB 이하, 짧은 변 720px 이상 |
| survey | string(JSON) | O | 아래 구조 |
| deviceType | string | O | `smartphone` \| `tablet` \| `camera` |

survey

```json
{
  "gender": "female",
  "ageBand": "20s",
  "skinType": "combination",
  "sleepHours": 6,
  "waterIntakeLevel": "low",
  "sunExposureLevel": "high",
  "stressLevel": "medium",
  "concerns": ["pore", "pigmentation"],
  "currentProducts": ["토너", "수분크림"],
  "note": "여름에 번들거림이 심해요"
}
```

| 필드 | 필수 | 값 |
|---|---|---|
| gender | O | `male` \| `female` |
| ageBand | O | `10s` `20s` `30s` `40s` `50s` `60s` `70s` |
| skinType | - | 피부 타입 코드(부록 A.3). 모르면 생략 또는 `null` |
| sleepHours | - | 0–14 정수 [구현 결정: 정수] |
| waterIntakeLevel, sunExposureLevel, stressLevel | - | `low` \| `medium` \| `high` |
| concerns | - | 고민 코드 배열(부록 A.4), 최대 3개, 중복 불가 |
| currentProducts | - | 문자열 배열, 최대 5개, 각 1–30자 |
| note | - | 300자 이하 |

앱(Swift)이 지켜야 할 업로드 규칙 — 정도희에게 전달 [구현 결정] (앱 연동 규칙 전체는 [ios-integration.md](ios-integration.md)):

- iPhone 카메라·앨범 사진은 HEIC인 경우가 많다. **JPEG로 바꿔서** 보낸다(아니면 415 E2004).
- **회전을 픽셀에 반영해서** 보낸다(EXIF 방향 태그만 두지 않음). 백엔드는 이미지를 디코딩하지 않고, AI 서버가 EXIF 방향을 처리한다는 보장이 없어 옆으로 누운 얼굴은 E2001이 될 수 있다.
- 긴 변 1600px로 줄이고 JPEG 품질 0.9 (SC-02 §5와 같은 기준). 짧은 변 720px 이상은 유지된다.
- 필드 이름 `image`·`survey`·`deviceType`, `survey`는 JSON **문자열**.
- iOS 앱은 `deviceType`을 항상 `smartphone`으로 보낸다 [앱 협의]. 백엔드 검증은 그대로 세 값 모두 허용.

`survey` 파싱: `JSON.parse` 실패 → E4002. 검증 실패 필드는 `survey.` 접두로 detail에 담는다. 저장·응답용으로 **정규화된 객체**(모든 키 포함, 미입력은 `null`, 배열은 `[]`)를 쓴다 [구현 결정].

#### 처리 순서 (SC-03 §4.1 순서를 지킴)

Nest 수명주기에서 **Guard는 Interceptor보다 먼저** 실행된다. 그래서 제한 검사를 Guard로 두면 multer가 파일을 버퍼링하기 전에 거절할 수 있다.

| 순서 | 단계 | 구현 위치 | 실패 |
|---|---|---|---|
| 1 | 인증 | `JwtAuthGuard` | 401 E1001 |
| 2 | 요청 제한: 진행 중 분석(queued·recommending) 또는 이 사용자의 처리 중 제출이 있음 / 오늘(KST) 생성한 분석 수 ≥ `ANALYSIS_DAILY_LIMIT` | `AnalysisLimitGuard` | 429 E4004 (`in_progress` / `daily_limit`) |
| 3 | 대기열 상한: `SELECT COUNT(*) FROM analyses WHERE status='queued'` ≥ `REC_QUEUE_LIMIT` | `QueueCapacityGuard` | 503 E3003 |
| 4 | AI 가용성: `aiAvailable=false` | `AiAvailabilityGuard` | 503 E3001 [구현 결정: 위치] |
| 5 | 파일 수신: memoryStorage, `limits: { fileSize: 10*1024*1024, files: 1 }` | `FileInterceptor('image')` | 크기 초과 413 E2003 |
| 6 | 형식 검증: image 없음 → E4002 / 파일 시그니처가 JPEG(`FF D8 FF`)·PNG(`89 50 4E 47`) 아님 → 415 E2004 / 짧은 변 < 720px → 422 E2002 "사진 해상도가 낮아요. 다시 찍어 주세요." [구현 결정] / deviceType·survey 검증 → E4002 | Pipe | |
| 7 | AI 측정 `POST /infer/skin-metrics` (§12) | `AiClient.measureSkin` | §12.5 표 |
| 8 | 이미지 버퍼 참조 해제 (`req.file = undefined`, 지역 변수 해제) | 서비스 `finally` | |
| 9 | AI 응답 검증(§12.4) → 4자리 반올림 → 점수 계산(§11) | | 500 E4001 |
| 10 | 트랜잭션: `analyses`(status `queued`, `queued_at = now()`, `is_demo = is_stub`, `measure_ms`) + `skin_metrics`(ROI별) + `surveys` INSERT | | 500 E4001 |
| 11 | 대기 순번·예상 대기 계산(§13.8) → **202** | | |

- **측정이 실패하면 분석 행을 만들지 않는다.** (상태 없음)
- 처리 중 제출 잠금 [구현 결정]: 2단계 Guard가 통과하면 인스턴스 메모리 `Set<userId>`에 넣고 `res.on('close')`에서 뺀다. 같은 사용자가 측정 3초 사이에 두 번 제출해도 두 번째는 E4004.
- 3단계 대기열 상한은 검사와 INSERT 사이에 경합이 있어 상한을 1–2건 넘을 수 있다. 시연 규모(동시 10명 이하)에서 허용 [구현 결정].
- 비운영 환경에서 `X-Fixture-Scenario` 헤더가 있으면 측정에 그 시나리오를 쓰고, 만든 분석 id와 함께 메모리에 기억해 워커의 추천 호출에도 쓴다(§12.7).

응답 **202 Accepted**

```json
{ "success": true, "data": { "analysisId": "01J9Z3K5V8Q2M4N6P8R0S2T4V6", "status": "queued", "queuePosition": 2, "estimatedWaitSec": 110 } }
```

테스트: TC-ANA-01(202, 저장), 02(gender 누락 422, detail), 03(얼굴 없음 422 E2001, 행 없음 — fixture `no-face`), 06(진행 중 → 429), 07(11MB 413, GIF 415), 09(데모 → 추천 metrics null), 10(AI 토큰 틀림 → 503 E3001), TC-PRI-01, TC-PERF-01·04.

### 10.2 GET /analyses/{id} — 인증 필요 (FR-ANA-03, FR-REP-01·02)

`WHERE id = $1 AND user_id = $2`. 없거나 남의 것이면 동일하게 **404 E4003**.

완료 응답

```json
{
  "success": true,
  "data": {
    "analysisId": "01J9Z3K5V8Q2M4N6P8R0S2T4V6",
    "status": "done",
    "createdAt": "2026-10-15T09:12:33Z",
    "completedAt": "2026-10-15T09:13:31Z",
    "deviceType": "smartphone",
    "queuePosition": null,
    "estimatedWaitSec": null,
    "measurement": {
      "isDemo": false,
      "confidence": 0.87,
      "calibrationApplied": true,
      "overallScore": 63,
      "summary": [
        { "metric": "moisture", "value": 61, "score": 61, "grade": "normal" },
        { "metric": "elasticity", "value": 74, "score": 74, "grade": "good" },
        { "metric": "pigmentation", "value": 38, "score": 62, "grade": "normal" },
        { "metric": "pore", "value": 45, "score": 55, "grade": "normal" }
      ],
      "byRoi": [
        { "roi": "forehead", "moisture": 58, "elasticity": 71, "pigmentation": 35, "pore": 52, "overallScore": 61, "grade": "normal" }
      ],
      "modelVersion": "skin_reg_v1"
    },
    "recommendation": {
      "status": "done",
      "skinType": { "code": "combination", "label": "복합성" },
      "concerns": [
        { "code": "pore", "label": "모공" },
        { "code": "pigmentation", "label": "미백(색소침착/기미/칙칙함)" }
      ],
      "ingredients": [ { "name": "나이아신아마이드", "purpose": "피지 분비와 모공 관리에 도움을 줄 수 있습니다.", "priority": 1 } ],
      "routine": [ { "step": 1, "time": "morning", "action": "약산성 클렌저로 가볍게 세안합니다." } ],
      "reasoning": ["(문제 분석·원인)", "(성분 선택·근거)", "(사용법·관리 방안)"],
      "answer": "복합성 피부에 모공과 톤이 고민이라면 …",
      "inputNotes": ["나이는 연령대(20s) 중간값 25세로 추정"],
      "disclaimer": "본 결과는 참고용 정보이며 의학적 진단이나 치료를 대신하지 않습니다. 피부 이상이 지속되면 피부과 전문의와 상담하세요.",
      "filtered": false,
      "usedFallback": false,
      "modelVersion": "rec_sllm_v2"
    },
    "survey": { "gender": "female", "ageBand": "20s", "skinType": "combination", "sleepHours": 6, "waterIntakeLevel": "low", "sunExposureLevel": "high", "stressLevel": "medium", "concerns": ["pore", "pigmentation"], "currentProducts": ["토너", "수분크림"], "note": "여름에 번들거림이 심해요" },
    "error": null
  }
}
```

상태별 규칙

| analyses.status | recommendation | queuePosition / estimatedWaitSec | error |
|---|---|---|---|
| queued | `{ "status": "pending" }` | 순번 ≥ 1 / 예상 초 (§13.8) | null |
| recommending | `{ "status": "pending" }` | `null` / `null` [구현 결정] | null |
| done | 위 전체 객체 | null / null | null |
| failed | `{ "status": "failed" }` | null / null | `{ "code": "E3002", "message": "…" }` (E3001·E3002·E3003·E4001 중 하나, message는 §7.3) |

필드 규칙

- **측정 결과는 추천 상태와 무관하게 항상 포함.**
- `summary`: 순서 고정 moisture → elasticity → pigmentation → pore. `value`는 원래 지표(0–100, 색소침착·모공은 높을수록 나쁨), `score`는 좋은 방향으로 통일, `grade`는 score 기준(§11).
- `byRoi`: 검출된 ROI 중 `full_face` 제외, 순서 고정 forehead → glabella → eye_left → eye_right → cheek_left → cheek_right → lip → chin [구현 결정]. 값은 `value`(0–100). 데모 값이면 빈 배열일 수 있음.
- `overallScore`는 `analyses.overall_score`, 나머지는 `skin_metrics`에서 응답 시점에 계산.
- `recommendation.skinType` / `concerns`: DB의 코드에 라벨(부록 A.3·A.4의 **AI 라벨**)을 붙인다. 화면 표기(예: acne → "트러블")는 앱이 코드로 매핑.
- `ingredients` 0–5개(purpose는 null 가능), `routine` 0개 이상, `reasoning` 3개 문단, `inputNotes` 기본 `[]`.
- `filtered`, `usedFallback`은 화면 비표시(분석용)지만 응답에 포함.
- `survey`: 저장한 정규화 설문(클라이언트 코드). 앱이 다음 측정의 기본값으로 쓴다.

테스트: TC-ANA-04(queued → recommending → done 순서, 완료 시 추천 필드 모두 존재), TC-SEC-01.

### 10.3 POST /analyses/{id}/recommendation/retry — 인증 필요 (FR-ANA-04, S)

- Guard: `JwtAuthGuard` → `AnalysisLimitGuard` → `QueueCapacityGuard` (SC-03: 대기열 상한·요청 제한을 4.1과 같이 적용). AI 가용성은 검사하지 않는다 — 큐에서 복구를 기다리면 된다 [구현 결정].
- 원자적 갱신:

```sql
UPDATE analyses
SET status = 'queued', queued_at = now(), error_code = NULL, completed_at = NULL,
    rec_started_at = NULL, queue_wait_ms = NULL, recommend_ms = NULL
WHERE id = $1 AND user_id = $2 AND status = 'failed'
RETURNING id;
```

  0행이면 다시 조회해서 행이 없거나 남의 것이면 **404 E4003**, 있으면(상태가 failed가 아님) **409 E4005**.
- `attempt_count`는 누적 유지.
- 비운영 환경에서 `X-Fixture-Scenario` 헤더가 있으면 이 분석의 시나리오를 갱신(TC-ANA-05: rec-timeout으로 실패 → ok로 바꿔 재요청 → done).
- 응답 **202** — 10.1과 같은 구조.

### 10.4 GET /analyses — 인증 필요 (FR-HIS-01, S)

`?page=1&size=20`. 정렬 `created_at DESC, id DESC`.

```json
{
  "success": true,
  "data": {
    "items": [
      { "analysisId": "01J9…", "createdAt": "2026-10-15T09:12:33Z", "status": "done", "overallScore": 63, "isDemo": false, "concerns": ["pore", "pigmentation"] }
    ],
    "page": { "page": 1, "size": 20, "totalItems": 3, "totalPages": 1 }
  }
}
```

`concerns`: 추천이 완료됐으면 `recommendations.concerns`(코드), 아니면 `[]` [구현 결정]. 홈 화면은 `size=1`로 최근 1건을 쓴다. 테스트: TC-HIS-01(test01: 1쪽 20건, 2쪽 5건, 최신순).

### 10.5 GET /analyses/{id}/trend — 인증 필요 (FR-HIS-02, S)

- 현재 분석: `id`로 조회(본인 것만, 상태 무관 — 측정은 항상 있음).
- 이전 분석: 같은 사용자의 `status='done' AND is_demo=false AND created_at < current.created_at` 중 최신 1건.
- 현재 분석이 데모 값이면 previous·delta는 `null` [구현 결정].

```json
{
  "success": true,
  "data": {
    "current":  { "analysisId": "01J9…", "date": "2026-10-15", "overallScore": 63, "summary": { "moisture": 61, "elasticity": 74, "pigmentation": 38, "pore": 45 } },
    "previous": { "analysisId": "01J8…", "date": "2026-09-28", "overallScore": 60, "summary": { "moisture": 54, "elasticity": 72, "pigmentation": 40, "pore": 47 } },
    "delta":    { "overallScore": 3, "moisture": 7, "elasticity": 2, "pigmentation": -2, "pore": -2 }
  }
}
```

- `summary`는 `value`(원래 값). `delta = current − previous`(정수끼리). 색소침착·모공은 **음수가 좋아진 것**.
- `date`는 `created_at`의 KST 날짜 `YYYY-MM-DD` [구현 결정].
- 이전 분석이 없으면 `previous: null, delta: null`. 테스트: TC-HIS-02.

### 10.6 DELETE /analyses/{id} — 인증 필요 (FR-HIS-03, S)

`DELETE FROM analyses WHERE id = $1 AND user_id = $2` → 0행이면 404 E4003. 상태 무관 삭제 허용(진행 중이면 워커가 결과를 버림, §13.7). **204**. 테스트: TC-HIS-03, TC-SEC-01.

---

## 11. 점수 계산 (`analyses/scoring.ts`, SC-02 §4.5)

### 11.1 명세의 식

```
value(m)      = round(원본 × 100)                    # 표시용 정수
score(m)      = moisture, elasticity   → value
                pigmentation, pore     → 100 − value
grade(score)  = good(≥70) / normal(40–69) / care(<40)   # 정수 score 기준
overallScore  = round( 평균( 원본 기반 score 4개, 반올림 전 값 ) )
ROI 점수      = 같은 식을 각 ROI에 적용 (byRoi[].overallScore, grade)
round         = 0.5 올림 (JavaScript Math.round)
```

- 종합 점수는 `full_face` 기준. `full_face`가 없으면 검출된 ROI 평균으로 계산(AI 서버 어댑터와 같은 규칙).
- 등급은 백엔드가 계산해 내려주고 앱은 표시만 한다.

### 11.2 부동소수점 함정과 구현 방법 [구현 결정]

`0.565 * 100`은 JavaScript에서 `56.49999999999999`라 `Math.round`가 56을 낸다(정답 57). 0–1을 0.0001 간격으로 보면 0.145, 0.285, 0.565, 0.575에서 어긋나고, 평균을 낼 때도 무작위 20만 건 중 25건이 1점 차이 났다(실측). 그래서 **정수 basis point(bp, 0–10000)로 계산**한다.

```ts
// AI 원본 v(0–1) → 저장 전 4자리 반올림을 bp로 확정
bp = Math.round(v * 10000)               // DB에는 (bp / 10000).toFixed(4)로 저장 → DB 값과 계산값 일치
// DB에서 읽은 값도 Math.round(v * 10000)으로 bp 복원

value(m)   = Math.round(bp / 100)        // 정수 ÷ 100 → .5가 정확히 표현됨
score(m)   = goodDirection(m) ? value : 100 - value
scoreBp(m) = goodDirection(m) ? bp : 10000 - bp
overall    = Math.round((scoreBp(moisture) + scoreBp(elasticity) + scoreBp(pigmentation) + scoreBp(pore)) / 400)
grade(s)   = s >= 70 ? 'good' : s >= 40 ? 'normal' : 'care'
```

- 지표 `grade`는 지표 `score`로, 종합·ROI `grade`는 각 `overallScore`로 계산.
- `full_face`가 없을 때 summary도 검출된 ROI 평균으로 계산한다 [구현 결정]: 지표별 `sumBp`(검출된 ROI n개 합)로 `value = Math.round(sumBp / (100 * n))`, `overall = Math.round(Σ scoreSum / (400 * n))` — 나눗셈을 한 번만 해서 오차를 없앤다 (`scoreSum`은 좋은 방향 지표면 `sumBp`, 아니면 `10000 * n − sumBp`).
- 측정 시점에 `overall_score`를 저장할 때와 응답 시점에 summary·byRoi를 계산할 때 **같은 함수**를 쓴다.

### 11.3 검증용 예시 (단위 테스트에 그대로 넣는다)

| 입력 (moisture, elasticity, pigmentation, pore) | value | score | overall | grade |
|---|---|---|---|---|
| full_face (0.61, 0.74, 0.38, 0.45) | 61, 74, 38, 45 | 61, 74, 62, 55 | 63 | normal |
| forehead (0.58, 0.71, 0.35, 0.52) | 58, 71, 35, 52 | 58, 71, 65, 48 | 61 (60.5 → 61) | normal |
| cheek_left (0.66, 0.76, 0.41, 0.38) | 66, 76, 41, 38 | 66, 76, 59, 62 | 66 (65.75) | normal |
| 함정: moisture 0.565 | 57 (naive 56) | 57 | | |
| 함정: (0.8906, 0.971, 0.9249, 0.5167) | | | 61 (naive 60) | normal |
| 경계: score 70 / 69 / 40 / 39 | | | | good / normal / normal / care |

---

## 12. AI 서버 연동 (ai-client)

### 12.1 인터페이스

```ts
interface AiCallContext { requestId: string; scenario?: FixtureScenario }

interface AiClient {
  health(): Promise<AiHealth>;                                    // GET /health
  modelInfo(): Promise<unknown>;                                  // GET /model-info (기동 시 1회 로그)
  measureSkin(input: { image: Buffer; mimeType: 'image/jpeg' | 'image/png'; deviceType: DeviceType },
              ctx: AiCallContext): Promise<SkinMetricsResult>;    // POST /infer/skin-metrics
  recommend(input: RecommendRequest, ctx: AiCallContext): Promise<RecommendResult>; // POST /infer/recommend
}
```

- `AI_MODE=real` → `HttpAiClient`, `fixture` → `FixtureAiClient`. DI 토큰 하나로 교체.
- 클라이언트는 AI 오류를 `AiError { kind: 'http' | 'connection' | 'timeout' | 'invalid_response'; status?; code?; message? }`로 던진다. **HTTP 코드 → 백엔드 코드 변환은 호출하는 쪽(측정 경로·워커)이 §12.5 표로** 한다.
- `/infer/analyze`는 쓰지 않는다.

### 12.2 HTTP 규약

| 항목 | 값 |
|---|---|
| Base URL | `AI_BASE_URL` |
| 인증 | `X-Internal-Token: <AI_INTERNAL_TOKEN>` (`/health` 제외) |
| 추적 | `X-Request-Id` 전달 |
| 필드 | AI 응답은 snake_case → 백엔드가 camelCase로 변환해 저장·응답 |
| 성공 본문 | 봉투 없이 그대로 |
| 실패 본문 | `{ "success": false, "error": { "code", "message", "detail" } }` |
| 실제 스키마 | `skincare-ai/server/schemas.py`, AI 서버 실행 후 `http://localhost:8000/docs` |

| 메서드 | 경로 | 백엔드 사용 | 타임아웃 | 재시도 |
|---|---|---|---|---|
| GET | /health | 30초마다 | 5초 | — |
| GET | /model-info | 기동 시 1회 로그 | 5초 | — |
| POST | /infer/skin-metrics | POST /analyses 안에서 동기 | 20초 (`AI_SKIN_TIMEOUT_MS`) | 연결 오류만 1회, 즉시 [구현 결정: 지연 없음] |
| POST | /infer/recommend | 워커 | 130초 (`AI_REC_TIMEOUT_MS`) | 연결 오류·503만 1회, 5초 후. **504·422 재시도 금지** |

**오류 분류 [구현 결정]** — 중요:

| 상황 | kind |
|---|---|
| `fetch`가 응답 전에 실패 (ECONNREFUSED, ENOTFOUND, ECONNRESET, TLS 오류 등) | `connection` |
| 상태 ≥ 500이고 본문이 위 실패 JSON(`error.code` 있음)이 **아님** — PC가 꺼졌을 때 Cloudflare가 주는 502/521/522/523/530 HTML 등 | `connection` |
| 우리 타임아웃(`AbortSignal.timeout`) 초과 | `timeout` |
| 상태 ≥ 400이고 본문에 `error.code` 있음 | `http` (status, code) |
| 2xx인데 JSON 파싱 실패 또는 스키마 위반 | `invalid_response` |

Cloudflare HTML 503을 AI의 503 E3003으로 읽으면 "요청이 많아요"가 잘못 뜬다. 반드시 본문의 `error.code`로 판단한다.

### 12.3 POST /infer/skin-metrics

요청 `multipart/form-data`: `image`(file, 시그니처로 판별한 Content-Type, 파일명 `image.jpg`/`image.png`), `device_type`.

응답 200

```json
{
  "roi_detected": ["full_face", "forehead", "glabella", "cheek_left", "cheek_right", "chin"],
  "metrics": {
    "full_face": { "moisture": 0.61, "elasticity": 0.74, "pigmentation": 0.38, "pore": 0.45 },
    "forehead":  { "moisture": 0.58, "elasticity": 0.71, "pigmentation": 0.35, "pore": 0.52 }
  },
  "confidence": 0.87,
  "calibration_applied": true,
  "is_stub": false,
  "model_version": "skin_reg_v1",
  "inference_ms": 840
}
```

- 값은 0–1. 백엔드는 **0–1 원본을 저장**(4자리)하고 응답 때 변환한다.
- `is_stub: true`(현재 기본, 10/17 회귀 모델 연결 전까지)면 해시로 만든 가짜 값 → `analyses.is_demo = true`.
- `skin_metrics`에는 `metrics`의 키(ROI)마다 한 행.
- AI 오류: 401 E1001, 413 E2003, 415 E2004, 422 E2001/E2002(회귀 모델 연결 후), 503 E3003, 504 E3002.

### 12.4 POST /infer/recommend

요청 (백엔드가 만든다)

```json
{
  "metrics": { "full_face": { "moisture": 0.61, "elasticity": 0.74, "pigmentation": 0.38, "pore": 0.45 } },
  "survey": {
    "gender": "female",
    "ageBand": "20s",
    "skinType": "복합성",
    "sleepHours": 6,
    "waterIntakeLevel": "low",
    "sunExposureLevel": "high",
    "stressLevel": "medium",
    "currentProducts": ["토너", "수분크림"],
    "selfReportedConcerns": ["모공", "미백(색소침착/기미/칙칙함)"],
    "note": "여름에 번들거림이 심해요"
  }
}
```

변환 규칙 (클라이언트 설문 → AI 설문)

| 클라이언트 | AI 서버 | 변환 |
|---|---|---|
| `skinType: "combination"` | `skinType: "복합성"` | 코드 → 라벨 (부록 A.3) |
| `concerns: ["pore"]` | `selfReportedConcerns: ["모공"]` | 코드 → 라벨 (부록 A.4). **코드를 그대로 보내면 인식되지 않음.** 키 이름도 바뀜 |
| (저장된 측정값) | `metrics` (0–1) | `skin_metrics`의 **모든 ROI**를 그대로 |
| 나머지 | 같은 이름 | 그대로 |

- **`is_demo = true`인 분석은 `"metrics": null`** — 가짜 값이 피부 타입·고민 추정에 섞이지 않게(FR-MEA-04, TC-ANA-09).
- 미입력 선택 필드는 키를 생략 [구현 결정] — `skincare-ai/server/schemas.py`와 대조해 맞춘다.

응답 200

```json
{
  "skin_type": "복합성",
  "concerns": ["모공", "미백(색소침착/기미/칙칙함)"],
  "ingredients": [{ "name": "나이아신아마이드", "purpose": "…", "priority": 1 }],
  "routine": [{ "step": 1, "time": "morning", "action": "약산성 클렌저로 가볍게 세안합니다." }],
  "reasoning": ["(문제 분석·원인 문단)", "(성분 선택·근거 문단)", "(사용법·관리 방안 문단)"],
  "answer": "…",
  "disclaimer": "본 결과는 참고용 정보이며 …",
  "filtered": false,
  "used_fallback": false,
  "input_notes": ["나이는 연령대(20s) 중간값 25세로 추정"],
  "model_version": "rec_sllm_v2",
  "inference_ms": 48100
}
```

응답 검증 (`ai-response.schemas.ts`)

| 검사 | 위반 시 |
|---|---|
| `skin_type`이 부록 A.3 라벨(또는 영문 코드)에 없음 | **failed E4001 + ERROR 로그** (SC-03) |
| `concerns` 원소가 부록 A.4 라벨에 없음 | failed E4001 + ERROR |
| `routine[].time`이 부록 A.5 밖 | failed E4001 + ERROR [구현 결정: 열거형은 엄격] |
| 타입 위반(필수 필드 없음, 문자열·숫자·불리언 아님) | failed E4001 + ERROR |
| 개수: ingredients > 5, concerns > 2, reasoning ≠ 3 | WARN 로그만, 그대로 저장 [구현 결정: 시연 안정성] |
| `ingredients[].purpose` null, `ingredients`·`routine` 빈 배열 | 정상 (AI 서버에 성분 사전이 없으면 ingredients가 빈 배열) |

- 라벨 → 코드로 바꿔 `recommendations.skin_type`·`concerns`에 **코드**로 저장. 중복 고민은 하나로.
- 추천 모델은 측정 수치·부위를 직접 보지 않는다(서빙 매핑을 거쳐 타입·고민으로만 전달). reasoning에 수치가 없는 게 정상.
- 측정 응답(§12.3)도 같은 방식으로 검증: ROI 이름이 부록 A.2에 없음, 값이 0–1 밖, `metrics`가 비어 있음 → 500 E4001 + ERROR.

### 12.5 AI 오류 → 백엔드 코드 (SC-02 §4.3)

| AI 결과 | 측정 (POST /analyses 안) | 추천 (워커) |
|---|---|---|
| 401 E1001 (내부 토큰 불일치) | **503 E3001** + ERROR 로그 (401을 그대로 주면 앱이 로그아웃시킴) | failed E3001 + ERROR |
| 413 E2003 / 415 E2004 | 그대로 | — |
| 422 E2001 / E2002 | 그대로 (재촬영 안내). message는 AI 문구가 있으면 그것, 없으면 §7.3 [구현 결정] | — |
| 422 E4002 | 500 E4001 + ERROR (백엔드 변환 버그) | failed E4001, 재시도 없음 |
| 503 E3003 | 그대로 | 5초 후 1회 재시도 → 실패 시 failed E3003 |
| 504 E3002 | 그대로 | **재시도 없이** failed E3002 |
| `connection` | 즉시 1회 재시도 → 503 E3001 | 5초 후 1회 재시도 → failed E3001 |
| `timeout` (우리 쪽) | 503 E3001 | **재시도 없이** failed E3002 [구현 결정] |
| `invalid_response` | 500 E4001 + ERROR | failed E4001 + ERROR |
| 그 밖의 4xx/5xx | 500 E4001 + ERROR | failed E4001 + ERROR |

504를 재시도하지 않는 이유: AI 서버는 타임아웃이 나도 진행 중인 생성을 끝까지 돌리며 GPU를 붙잡는다. 곧바로 재시도하면 그 뒤에 줄을 서다 다시 타임아웃이 나고 다음 작업까지 밀린다. 우리 쪽 130초 타임아웃도 같은 상황이므로 재시도하지 않는다.

### 12.6 GET /health

```json
{ "status": "ok", "rec_loaded": true, "skin_loaded": true, "rec_backend": "sllm", "skin_backend": "stub" }
```

`status`가 `"ok"`가 아니거나 5초 안에 응답이 없으면 `aiAvailable=false`. `skin_backend`가 `stub`이면 측정값이 데모 값이라는 뜻(로그 INFO).

### 12.7 Fixture 모드 (`AI_MODE=fixture`, SC-03 §7)

백엔드·앱 개발은 AI 서버가 꺼져 있어도 이 모드로 한다. `test/fixtures/ai/`의 JSON을 돌려준다.

| 시나리오 | 측정 | 추천 | health |
|---|---|---|---|
| `ok` (기본) | `skin-metrics.ok.json` (`is_stub: true`) | `recommend.ok.json`, **1초 지연** | ok |
| `no-face` | 422 E2001 | — | ok |
| `rec-timeout` | 정상 | 504 E3002 즉시 | ok |
| `rec-slow` | 정상 | **60초 지연** 후 정상 (대기열 테스트) | ok |
| `ai-down` | `connection` 오류 | `connection` 오류 | 실패 → `aiAvailable=false` |

- 시나리오는 `AI_FIXTURE_SCENARIO`. **`NODE_ENV !== 'production'`일 때만** 요청 헤더 `X-Fixture-Scenario`로 요청마다 바꿀 수 있다.
- 워커는 요청 헤더를 볼 수 없다 → POST /analyses·retry에서 받은 헤더 시나리오를 `Map<analysisId, scenario>`(인스턴스 메모리)에 넣고 워커가 꺼내 쓴다. 없으면 env 값. 재시작하면 사라져도 된다 [구현 결정].
- health는 env 시나리오만 따른다.
- 지연은 타이머로 구현하되 테스트에서 가짜 타이머로 넘길 수 있게 한다.
- `recommend.ok.json`·`skin-metrics.ok.json`은 `skincare-ai/docs/fixtures/`의 실제 출력(v2 모델, stub 회귀기)으로 교체했다(10/10). 실제 stub 측정은 `full_face`만 있고 confidence 0.0이라 `byRoi`가 빈 배열이다. 원본이 바뀌면 그대로 복사해 온다.
- fixture 응답도 HttpAiClient와 **같은 검증·변환 코드**를 통과시킨다(계약 테스트 겸용).

---

## 13. 추천 작업 큐 (recommendation)

### 13.1 상태 전이

```
(측정 성공) ──▶ queued ──워커 획득──▶ recommending ──추천 저장──▶ done
                 │  ▲                     │
   60분 초과 ─────┘  │                     └─재시도 후에도 실패──▶ failed
   (failed E3001)    └────────── 사용자 재요청 ────────────────────┘
(측정 실패: 행 없음, 상태 없음)
(백엔드 재시작: recommending ──▶ queued, queued_at 유지)
```

### 13.2 워커 동작

| 항목 | 설계 |
|---|---|
| 폴링 | 1초마다. 처리 중이면 건너뜀(동시성 1) |
| 동시성 | 1. GPU 생성이 직렬이기 때문 |
| AI 비가용 | `aiAvailable=false`면 획득 쿼리를 실행하지 않음(대기열 유지). 복구되면 이어서 |
| 기동 | `onApplicationBootstrap`: ① 복구 쿼리 ② 첫 헬스체크 ③ 폴링 시작. `NODE_ENV=test`에선 자동 시작하지 않고 테스트가 `tick()`을 직접 호출 [구현 결정] |
| 종료 | `enableShutdownHooks`. SIGTERM이면 폴링만 멈추고 진행 중 작업은 기다리지 않음 — 재시작 복구가 다시 큐에 넣는다 [구현 결정] |

```
tick():
  if (busy || !aiHealth.available) return
  job = acquire()                       // §13.3 획득 SQL
  if (!job) return
  busy = true
  try { process(job) } finally { busy = false }

process(analysisId):
  analysis, metrics, survey 로드 (없으면 삭제된 것 → INFO 로그 후 종료)
  req = buildRecommendRequest(analysis.is_demo ? null : metrics, survey.payload)
  started = now
  result = callWithPolicy(req)          // §12.5 워커 열: 503·connection만 5초 후 1회, 재시도 시 attempt_count + 1
  recommend_ms = 마지막 호출의 왕복 시간 [구현 결정]
  성공:
    tx:
      UPDATE analyses SET status='done', completed_at=now(), recommend_ms=$ms
        WHERE id=$1 AND status='recommending' RETURNING id
      0행 → 롤백, 결과 버림 (삭제·탈퇴됨) — INFO 로그
      INSERT INTO recommendations … ON CONFLICT (analysis_id) DO UPDATE …
  실패(code):
    UPDATE analyses SET status='failed', error_code=$code, completed_at=now(), recommend_ms=$ms
      WHERE id=$1 AND status='recommending'
```

### 13.3 SQL (SC-04 §4)

```sql
-- 작업 획득 (1초마다, aiAvailable=true일 때만)
UPDATE analyses SET status = 'recommending', rec_started_at = now(),
       queue_wait_ms = (EXTRACT(EPOCH FROM now() - queued_at) * 1000)::int,
       attempt_count = attempt_count + 1
WHERE id = (SELECT id FROM analyses WHERE status = 'queued'
            ORDER BY queued_at LIMIT 1 FOR UPDATE SKIP LOCKED)
RETURNING id;

-- 대기 순번
SELECT COUNT(*) + 1 FROM analyses
WHERE status = 'queued' AND queued_at < (SELECT queued_at FROM analyses WHERE id = $1);

-- 최근 완료 20건 평균 추천 시간 (없으면 55000ms)
SELECT COALESCE(AVG(recommend_ms), 55000) FROM
  (SELECT recommend_ms FROM analyses WHERE status = 'done' AND recommend_ms IS NOT NULL
   ORDER BY completed_at DESC LIMIT 20) t;

-- 서버 기동 시 복구
UPDATE analyses SET status = 'queued' WHERE status = 'recommending';

-- 장기 대기 처리 (1분마다)
UPDATE analyses SET status = 'failed', error_code = 'E3001', completed_at = now()
WHERE status = 'queued' AND queued_at < now() - make_interval(mins => $1);  -- $1 = REC_QUEUE_MAX_WAIT_MIN

-- 대기열 상한 확인 (분석 생성 전)
SELECT COUNT(*) FROM analyses WHERE status = 'queued';  -- ≥ REC_QUEUE_LIMIT 이면 E3003
```

`COUNT(*)`·`AVG`는 `pg`가 문자열로 돌려주므로 `Number()` 변환.

### 13.4 시간 기록 (NFR-OBS-01)

`measure_ms`(측정 왕복), `queue_wait_ms`(획득 시 계산), `recommend_ms`(추천 왕복). 최종 성능 분석(응답 시간 분포·대기 시간)이 이 값으로 계산되므로 성공·실패 모두 기록한다.

### 13.5 장기 대기

`queued`가 `REC_QUEUE_MAX_WAIT_MIN`(60)분을 넘으면 failed E3001 — AI 서버가 오래 꺼져 있던 경우. 사용자는 재요청할 수 있다.

### 13.6 재시작 복구

기동 시 `recommending`을 모두 `queued`로 되돌린다(`queued_at` 유지로 순서 보존). AI 서버가 이전 요청을 아직 생성 중일 수 있지만 AI 서버 큐에 순서대로 쌓이므로 허용.

### 13.7 취소 (탈퇴·이력 삭제)

진행 중 HTTP 호출은 끊지 않는다(끊어도 AI는 GPU 생성을 계속함). 결과 저장 시 `WHERE status='recommending'` 갱신이 0행이면 버린다. FK 위반으로 500이 나지 않게 이 순서를 지킨다.

### 13.8 대기 순번 · 예상 대기

| 값 | 계산 |
|---|---|
| `queuePosition` (queued) | 내 `queued_at`보다 앞선 queued 건수 + 1 |
| `estimatedWaitSec` (queued) | `Math.round(queuePosition × 평균추천ms / 1000)` (평균은 §13.3, 기본 55초) |
| recommending | `null` / `null` |
| `/system/status` `estimatedWaitSec` | `Math.round(queueLength × 평균추천ms / 1000)` |

예: 순번 2 → 110초, queueLength 3 → 165초.

---

## 14. 시스템 상태 (system) — FR-SYS-01, NFR-AVL-01

### 14.1 AiHealthService

- 기동 시 즉시 1회, 이후 `AI_HEALTH_INTERVAL_MS`(30초)마다 `GET /health`(5초).
- 상태 `{ aiAvailable: boolean, checkedAt: Date }`를 메모리에 둔다. 첫 검사 전에는 `false`.
- 바뀔 때만 로그: 끊김 WARN, 복구 INFO.
- 측정 호출에서 `connection` 오류가 나면 즉시 `aiAvailable=false`로 두고 다음 검사를 앞당긴다 [구현 결정].
- fixture 모드: `ai-down`이면 false, 그 외 true.

### 14.2 GET /system/status — 인증 없음

```json
{ "success": true, "data": { "aiAvailable": true, "queueLength": 3, "estimatedWaitSec": 165, "checkedAt": "2026-10-15T09:12:00Z" } }
```

- `queueLength` = queued 건수.
- DB 조회가 들어가므로 Docker 헬스체크에도 이 경로를 쓴다(DB 장애면 500 E4001) [구현 결정].
- 테스트: TC-SYS-01(AI 종료 후 1분 → false, 로그인·이력은 정상).

---

## 15. 보안 · 개인정보

### 15.1 체크리스트

| ID | 요구 | 구현 | 테스트 |
|---|---|---|---|
| NFR-SEC-02 | 비밀번호 | bcrypt cost 12, 평문 로그 금지, 로그인 실패 문구 동일, 더미 비교 | TC-SEC-02 (`$2b$12$` 시작) |
| — | 토큰 탈취 | 액세스 1h(메모리), 리프레시 14d httpOnly·Secure·SameSite=Strict(네이티브 앱은 본문 `refreshToken` → Keychain, §9.1), 해시 저장, 사용 시 교체, 재사용 감지 시 전부 폐기 | TC-AUTH-04~06 |
| NFR-SEC-04 | IDOR | 모든 분석 쿼리에 `user_id` 조건. 없거나 남의 것이면 동일한 404 E4003 | TC-SEC-01 |
| — | 무차별 요청 | 로그인 IP당 분당 10회, 분석 사용자당 일 20건 + 진행 중 1건 | TC-SEC-04, TC-ANA-06 |
| NFR-SEC-03 | AI 직접 호출 | AI 토큰은 백엔드 env에만. AI 401은 클라이언트에 503 E3001 | TC-ANA-10 |
| NFR-PRI-01 | 이미지 잔존 | `memoryStorage`만, `dest`·`diskStorage`·임시 파일 금지, 측정 후 참조 해제, 예외·로그에 버퍼 금지 | TC-PRI-01 |
| NFR-PRI-02 | 로그 유출 | §7.6 | TC-PRI-02 |
| NFR-PRI-03 | 만 14세·동의 | 4개 동의 필수, 시각 기록 | TC-AUTH-01·02 |
| NFR-SEC-05 | 비밀값 | `.env` gitignore, `.env.example` 가짜 값, GitHub secret scanning | TC-SEC-03 (gitleaks) |
| NFR-DAT-01 | 학습 데이터 | AI허브 데이터·가공물·가중치·실제 얼굴 사진을 레포에 넣지 않음. 테스트 이미지는 합성 | TC-DAT-01 |
| NFR-SEC-01 | HTTPS | Cloudflare Tunnel이 처리. 백엔드는 HTTP :3000, 외부 비공개 | — |

### 15.2 클라이언트 IP

nginx·터널 뒤라 모든 요청의 출발 IP가 같다. `TRUST_PROXY=true`면 Express `trust proxy`를 켜고, **`CF-Connecting-IP` 헤더 → 없으면 `req.ip`** 순으로 판별(`common/utils/client-ip.ts`). 백엔드는 터널·nginx로만 들어오므로 헤더 위조 경로가 없다. 로컬에선 헤더가 없어 `req.ip`.

### 15.3 기타 [구현 결정]

- CORS: `CORS_ORIGIN` 하나만, `credentials: true`. iOS 앱은 CORS와 무관하다.
- `helmet` 기본 헤더 적용(선택).
- Swagger는 운영에서도 열어 둔다(레포가 Public이라 숨길 정보 없음, 앱 타입 생성용). 팀이 원하면 production에서 끔.

---

## 16. 테스트

### 16.1 레벨

| 레벨 | 대상 | 도구 | 실행 |
|---|---|---|---|
| 단위 | `scoring.ts`, `ai-transform.ts`, `ai-error.mapper.ts`, 응답 presenter, 토큰 로직, 시간 유틸 | Jest | `npm run test`, CI |
| 계약 | fixture JSON이 AI 응답 검증·변환을 통과하는지, 변환한 요청이 SC-03 §5.2 모양인지 | Jest + `test/fixtures/ai` | CI, AI 응답이 바뀔 때 |
| API 통합 | 백엔드 + DB, 엔드포인트별 | Jest e2e + supertest + PostgreSQL 16 | `npm run test:e2e`, CI 서비스 컨테이너 |
| 성능 | 응답 시간, 동시 요청 | k6 (`load/`) | 스테이징, 11/10–11/20 |
| 실제 AI 계약 [구현 결정] | 백엔드 요청을 실제 skincare-ai가 인식하는지(고민 8종·피부 타입 4종 라벨), 응답이 검증을 통과하는지 | Jest (`test/e2e/ai-contract.e2e-spec.ts`, `AI_CONTRACT_URL`이 있을 때만) | AI 서버가 바뀔 때, M3 전 |
| 스모크 [구현 결정] | 실행 중인 서버의 전체 흐름(상태 → 로그인 → 제출 → 추천 완료 → 이력·추이 → refresh) | `npm run smoke` (`SMOKE_BASE_URL`) | 배포 직후, AI 연동 확인 |

e2e 기반 [구현 결정]:

- `AI_MODE=fixture`, `NODE_ENV=test`. 전역 setup에서 마이그레이션 실행, 테스트마다 테이블 TRUNCATE(`users … CASCADE`).
- 워커는 자동 시작하지 않고 `RecommendationWorker.tick()`을 직접 호출. 지연 시나리오는 Jest 가짜 타이머 또는 지연 주입으로 기다리지 않게.
- 실패·지연은 `X-Fixture-Scenario` 헤더로 재현.
- 테스트 이미지는 코드로 생성(예: 1080×1440 단색 JPEG/PNG, 600×600 PNG, GIF 헤더, 11MB 버퍼). 실제 얼굴 사진 금지.
- 테스트 이름에 TC ID를 넣는다: `it('TC-ANA-06: 진행 중 분석이 있으면 429 E4004', …)`.

### 16.2 백엔드가 자동화하는 테스트 케이스 (SC-08)

| TC | 내용 | 방법 |
|---|---|---|
| TC-AUTH-01~03 | 가입 성공(동의 시각 4개) / 동의 누락 422 E4002 / 대소문자 다른 이메일 409 E1004 | e2e |
| TC-AUTH-04 | 로그인 200, 액세스 토큰, httpOnly 쿠키(속성 전부 확인) | e2e |
| TC-AUTH-05 | 만료 토큰 → 401 E1001, refresh 후 원 요청 성공 (앱 동작의 백엔드 쪽) | e2e (짧은 만료 토큰 서명) |
| TC-AUTH-06 | 로그아웃 후 이전 리프레시로 refresh → 401 E1001 | e2e |
| TC-AUTH-07 | 분석 3건 계정 탈퇴 → 6개 테이블 0건 | e2e |
| (추가) | 교체된 리프레시 토큰 재사용 → 401 + 그 사용자 토큰 전부 폐기 | e2e |
| (추가) | `X-Client: ios` 로그인·refresh → `data.refreshToken` 있음(헤더 없으면 없음), 쿠키 없이 본문 토큰으로 refresh·logout, 본문으로 교체된 토큰 재사용 → 401 + 전부 폐기, 쿠키·본문 둘 다 있으면 쿠키 우선 | e2e |
| TC-ANA-01 | 202, queued, queuePosition ≥ 1, skin_metrics·surveys 저장 | e2e |
| TC-ANA-02 | gender 누락 → 422 E4002, detail에 `survey.gender` | e2e |
| TC-ANA-03 | `no-face` → 422 E2001, analyses 행 없음 | e2e |
| TC-ANA-04 | queued → recommending → done, 완료 시 추천 필드 모두 | e2e + `tick()` |
| TC-ANA-05 | `rec-timeout` → failed E3002(attempt_count 1, 재시도 없음), 측정 유지 → ok로 재요청 → done | e2e |
| TC-ANA-06 | 진행 중 분석 있을 때 두 번째 제출 → 429 E4004 | e2e |
| TC-ANA-07 | 11MB → 413 E2003, GIF → 415 E2004 | e2e |
| TC-ANA-08 | recommending 상태에서 재시작(복구 함수 호출) → queued → done | e2e |
| TC-ANA-09 | 데모 측정 → 추천 요청 `metrics: null` | e2e (FixtureAiClient가 받은 요청 기록) |
| TC-ANA-10 | AI 401 → 클라이언트 503 E3001, ERROR 로그 | 단위(매퍼) + e2e(HttpAiClient를 로컬 가짜 서버로) |
| TC-HIS-01~03 | 목록 20/5, 최신순 / trend delta·previous null / 삭제 후 목록·추이 제외 | e2e (시드 또는 팩토리) |
| TC-SEC-04 | 같은 CF-Connecting-IP 11번째 429, 다른 IP 정상 | e2e |
| TC-SYS-01 | `ai-down` → aiAvailable false, 제출 503 E3001, 로그인·이력 정상 | e2e |
| TC-SEC-01 | A 토큰으로 B 분석 조회·삭제·재요청·추이 → 404 E4003 | e2e |
| TC-SEC-02 | password_hash가 `$2b$12$`로 시작 | e2e |
| TC-PRI-01 | 분석 처리 후 업로드 관련 파일이 디스크에 없음 | e2e (OS 임시 디렉터리 전후 비교) + 코드 검사(`diskStorage`/`dest` 없음) |
| TC-PRI-02 | 전체 흐름의 로그 출력에 설문 note·토큰·비밀번호·이메일 원문 없음 | e2e (로거 출력 캡처 후 문자열 검색) |
| TC-PERF-04 | `rec-slow`로 큐를 채운 뒤 15개 계정 제출 → 12번째부터 503 E3003 | e2e(로컬) 또는 k6. **첫 건이 recommending으로 넘어간 뒤** 나머지를 보내야 "처리 1 + 대기 10"이 성립(아니면 11번째부터 거절) |

스테이징에서 수동·k6로 하는 것: TC-PERF-01(제출 p50 ≤ 3초, p95 ≤ 5초), TC-PERF-02(recommend_ms p95 ≤ 90초), TC-PERF-03(10명 동시, 실패 0, 마지막 완료 약 10분 이내), TC-E2E-01~03(앱 담당).

### 16.3 CI (`.github/workflows/ci.yml`)

```yaml
name: ci
on:
  pull_request: { branches: [develop, main] }
  push: { branches: [develop, main] }
jobs:
  build:
    runs-on: ubuntu-latest
    services:
      db:
        image: postgres:16-alpine
        env: { POSTGRES_USER: test, POSTGRES_PASSWORD: test, POSTGRES_DB: test }
        ports: ["5432:5432"]
        options: --health-cmd pg_isready --health-interval 5s --health-retries 10
    env:
      DATABASE_URL: postgres://test:test@localhost:5432/test
      AI_MODE: fixture
      NODE_ENV: test
      JWT_ACCESS_SECRET: ci-access-secret-not-real
      JWT_REFRESH_SECRET: ci-refresh-secret-not-real
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: 20, cache: npm }
      - run: npm ci
      - run: npm run lint
      - run: npm run test
      - run: npm run build
      - run: npm run test:e2e
```

(팀 문서 예시에 env 검증 통과용 가짜 JWT 값과 `NODE_ENV`만 더했다.)

---

## 17. 배포 (SC-02 §7)

### 17.1 Dockerfile [구현 결정]

- 멀티 스테이지 `node:20-alpine`: build 스테이지(`npm ci` → `npm run build` → `npm prune --omit=dev`), run 스테이지(build의 운영 `node_modules`·`dist/`·`test/fixtures/ai`만 복사, 비root 사용자). 네이티브 모듈을 build에서 컴파일해 넘기므로 run 스테이지에 빌드 도구가 없다. fixture JSON은 배포 서버에서 `AI_MODE=fixture`로 띄울 때만 쓴다.
- `bcrypt` 네이티브 빌드가 alpine에서 실패하면 build 스테이지에 `python3 make g++`를 추가.
- 시작 스크립트: 컴파일된 마이그레이션 실행 → `node dist/main.js`.
- `HEALTHCHECK`: `GET /api/v1/system/status`.

### 17.2 docker-compose.yml (이 레포에 둠)

```
docker-compose.yml
├─ web          nginx:1.27-alpine (/api 프록시, 업로드 12MB 제한 — 앱만 쓰므로 정적 파일 서빙 없음)
├─ backend      node:20-alpine (NestJS, 포트 3000 — 외부 비공개)
├─ db           postgres:16-alpine (볼륨 pgdata, 외부 비공개)
└─ cloudflared  (app.<도메인> → web:80)
```

- 외부에 여는 포트 없음. 로컬 개발을 위해 db만 `127.0.0.1:5432:5432`로 바인드(서버에서도 외부에 노출되지 않음) [구현 결정].
- 로컬 개발은 `docker compose up -d db`만 쓴다.
- 로그 로테이션: `logging: { driver: json-file, options: { max-size: "10m", max-file: "14" } }` (보존 14일 근사).
- 배포: main 병합 → 서버에서 `git pull && docker compose up -d --build` (자동화는 S).
- DB 백업(매일 03:00 pg_dump, 7일 보관)은 WBS 6.2 정도희 담당. 컴포즈에 백업 사이드카를 넣을지 함께 정한다.

> 10/10 로컬에서 compose(`web`·`backend`·`db`, cloudflared 제외)로 확인: 컨테이너 시작 시 마이그레이션, 헬스체크 healthy, nginx 경유 `/api` 200·그 밖 404, `CF-Connecting-IP`가 백엔드까지 전달돼 로그인 제한이 IP별로 동작, 12MB 초과 업로드는 nginx가 413 E2003 봉투로 응답, production 쿠키 `Secure`.

### 17.3 로컬에서 iOS 앱과 연동 [구현 결정]

- 백엔드는 `app.listen(PORT, '0.0.0.0')`으로 띄운다.
- iOS 시뮬레이터: `http://localhost:3000/api/v1`.
- 실기기: 같은 Wi-Fi에서 Mac의 LAN IP(`http://192.168.x.x:3000/api/v1`). 평문 HTTP라 앱 Info.plist에 ATS 예외가 필요하다(앱 쪽 설정). 또는 임시 터널(`cloudflared tunnel --url http://localhost:3000`)의 HTTPS 주소를 쓴다.
- 로컬 HTTP로 붙을 때는 `Secure` 쿠키가 전달되지 않으므로 `COOKIE_SECURE=false` (터널 HTTPS면 true 그대로). iOS 앱은 본문 `refreshToken`(§9.1)을 쓰므로 이 설정과 관계없이 refresh가 동작한다.
- 개발 중 실패 재현은 앱이 `X-Fixture-Scenario` 헤더를 붙이면 된다(production에선 무시).

### 17.4 AI 서버 연동 절차 (김우주 PC가 켜져 있을 때)

1. 김우주: AI 서버 기동(`$env:REC_BACKEND="sllm"; .\scripts\run_server.ps1`) + 터널(`cloudflared tunnel run skincare-ai`)
2. 백엔드 `.env`: `AI_MODE=real`, `AI_BASE_URL`, `AI_INTERNAL_TOKEN` 설정 후 재시작
3. `GET /api/v1/system/status`가 `aiAvailable: true`인지 확인
4. `SMOKE_BASE_URL=https://app.<도메인>/api/v1 SMOKE_PASSWORD=… npm run smoke`로 전체 흐름 확인 [구현 결정] (회귀 모델 연결 후에는 `SMOKE_IMAGE`로 본인 동의 얼굴 사진)

로컬에서 미리 확인하려면 skincare-ai를 mock 모드(`REC_BACKEND=mock`, GPU 불필요)로 띄우고 `AI_MODE=real`로 붙인다. 10/10 이 방식으로 측정·추천·토큰 오류·AI 중지/복구를 확인했다.

연동 테스트가 필요하면 김우주에게 **하루 전** 요청한다(PC 학습 일정과 겹침 방지).

---

## 18. 구현 단계

### 18.1 단계별 계획 (WBS 4.x에 맞춤)

오늘(10/10) 기준 M2(10/12)까지 Phase 0–3의 M 항목을 fixture 모드로 끝내는 것이 목표다. 완료한 항목은 체크한다.

> 10/10 진행: Phase 0–7 구현·테스트 완료 (단위 + e2e, fixture 모드). 로컬에서 실제 skincare-ai(mock 추천·stub 측정)와 `AI_MODE=real` 연동도 확인했다. 남은 것: 배포 서버 + 실제 sLLM 연동(M3), 스테이징 부하 테스트 실측(11/10–11/20).

**Phase 0 — 골격 (WBS 4.1·4.2)**
- [x] NestJS 10 프로젝트, ESLint·Prettier(printWidth 100, singleQuote), TS strict
- [x] `config/` env 스키마·검증(§6), `.env.example`
- [x] `common/`: 에러 코드 표·`ApiException`·전역 필터, 응답 봉투 인터셉터, X-Request-Id 미들웨어, pino 로깅·redact, 시간 유틸, 열거형(부록 A)
- [x] `main.ts`: prefix `api/v1`, cookie-parser, CORS, trust proxy, ValidationPipe(422 E4002), Swagger `/api/docs`, `0.0.0.0`에서 listen
- [x] TypeORM DataSource, §8.1 마이그레이션, 엔티티(NUMERIC·BIGINT transformer)
- [x] `docker-compose.yml`(db), npm 스크립트(start:dev, test, test:e2e, migration:*, seed)
- [x] CI(§16.3), e2e 기반(§16.1)
- 완료 기준: `npm run start:dev` → `/api/docs` 열림, 마이그레이션 적용, CI 통과

**Phase 1 — 인증 (WBS 4.3, M)**
- [x] signup, login(로그인 제한 포함), refresh(교체·재사용 감지), logout, JwtStrategy, GET /users/me
- [x] 시드(test01~40, test01 완료 분석 25건)
- 완료 기준: TC-AUTH-01~06, TC-SEC-02·04

**Phase 2 — 분석 생성·조회 (WBS 4.4, M)**
- [x] `scoring.ts` + §11.3 단위 테스트
- [x] AiClient 인터페이스, FixtureAiClient(시나리오 5종, 헤더 오버라이드), `test/fixtures/ai/*.json`
- [x] AI 응답 검증·변환·오류 매퍼(§12.4·12.5) + 단위 테스트
- [x] POST /analyses(가드 순서, multer memoryStorage, 시그니처·크기·짧은 변, survey 파이프, 트랜잭션 저장)
- [x] GET /analyses/{id} presenter
- [x] HttpAiClient(타임아웃·재시도·오류 분류 §12.2)
- 완료 기준: TC-ANA-01·02·03·06·07·09·10, TC-SEC-01, TC-PRI-01·02

**Phase 3 — 추천 큐 (WBS 4.5, M)**
- [x] 큐 SQL 리포지토리, 워커(`tick`), 재시도 정책, 기동 복구, 장기 대기 만료, 취소 처리
- [x] POST /analyses/{id}/recommendation/retry (S)
- 완료 기준: TC-ANA-04·05·08
- **→ M2 (10/12): fixture 모드로 가입 → 촬영 → 설문 → 결과까지 로컬 동작**

**Phase 4 — 상태·제한 (WBS 4.6)**
- [x] AiHealthService, GET /system/status, AI 가용성 가드
- [x] 일일 한도(KST), 진행 중 1건 + 처리 중 제출 잠금, 대기열 상한, Retry-After
- 완료 기준: TC-SYS-01, TC-ANA-06, TC-PERF-04
- **→ M3 (10/18): 배포 서버에서 `AI_MODE=real`로 실제 sLLM과 전체 흐름**

**Phase 5 — 이력·탈퇴 (WBS 4.7, S, 10/22–11/3)**
- [x] GET /analyses(페이지), GET /analyses/{id}/trend, DELETE /analyses/{id}, DELETE /users/me
- [x] 리프레시 토큰 정리 배치(03:30 KST)
- 완료 기준: TC-HIS-01~03, TC-AUTH-07

**Phase 6 — 배포 구성 (WBS 6.1 정도희와 협업, 10/8–10/16)**
- [x] Dockerfile, 운영 compose(web·backend·db·cloudflared), 로그 로테이션, 시작 시 마이그레이션

**Phase 7 — 부하 테스트 (WBS 4.8, 11/10–11/20)**
- [x] k6 스크립트(`load/`): TC-PERF-01~04, 계정별 토큰 미리 발급(로그인 제한 회피), 스테이징 `ANALYSIS_DAILY_LIMIT=100` — 로컬 fixture로 동작 확인 (10/10)
- [x] 응답 시간 분포 집계 쿼리(measure_ms, queue_wait_ms, recommend_ms) — `npm run report:latency`, 배포 서버는 `node dist/database/scripts/latency-report.js`
- [ ] 스테이징 실측 (11/10–11/20, 실제 AI 서버) — 실행 방법은 `load/README.md`

### 18.2 완료 기준 (Definition of Done, SC-06 §4.3)

- [ ] 요구사항 ID의 수용 기준을 만족한다
- [ ] 단위 테스트가 있고 CI(lint·test·build·test:e2e)가 통과한다
- [ ] API·DB·화면이 바뀌었다면 해당 문서도 같은 PR에서 고쳤다
- [ ] 로그에 이미지·설문 원문·토큰이 남지 않는다
- [ ] 사용자 문구가 문구 규칙을 지킨다

### 18.3 협업 규칙 요약 (SC-06)

- 브랜치: `main`·`develop` 직접 푸시 금지. `feat/{이슈번호}-{영문-요약}`, `fix/…`, `docs/…`, `chore/…`, `hotfix/…`(main에서 출발 → main + develop).
- 커밋: `<타입>: <무엇을 했는지 한 줄, 한국어> (#이슈번호)` — 타입 feat·fix·docs·refactor·test·chore. 한 커밋에 한 가지 변경.
- PR: develop 대상, 변경 400줄 이하 권장, 템플릿(무엇을·왜·테스트 방법), 리뷰어 정도희 1명 승인, CI 통과 후 Squash merge.
- **명세 변경 절차(문서 먼저, 코드 나중)**: spec 라벨 이슈 → `skincare-ai`에 SC-03·04 수정 PR, 영향 담당자 전원 리뷰 → 병합 후 버전 올림(호환 깨짐 v1.1, 단순 추가 v1.0.1) → 각 레포가 그 PR 번호를 적고 구현. **시연 1주 전(10/13, 12/2)부터 호환이 깨지는 변경 금지.**
- 릴리스 태그: v0.1.0(10/18), v0.2.0(11/24), v1.0.0(12/8).

---

## 부록 A. 열거형 (SC-03 §6) — `src/common/enums/`의 단일 출처

### A.1 상태

| 필드 | 값 |
|---|---|
| 분석 `status` | `queued`(추천 대기) · `recommending`(추천 생성 중) · `done` · `failed` |
| `recommendation.status` | `pending`(queued·recommending일 때) · `done` · `failed` |

### A.2 ROI

`full_face`, `forehead`, `glabella`(미간), `eye_left`, `eye_right`, `cheek_left`, `cheek_right`, `lip`, `chin`

### A.3 피부 타입

| 코드 | 라벨 (AI 서버 값) |
|---|---|
| `dry` | 건성 |
| `normal` | 중성 |
| `oily` | 지성 |
| `combination` | 복합성 |

### A.4 피부 고민 (8종)

| 코드 | 라벨 (AI 서버 값) | 화면 표기 (앱) |
|---|---|---|
| `pigmentation` | 미백(색소침착/기미/칙칙함) | 피부 톤·잡티 |
| `pore` | 모공 | 모공 |
| `wrinkle` | 주름 | 주름 |
| `acne` | 여드름/뾰루지 | 트러블 |
| `redness` | 붉어짐(홍조) | 붉은기 |
| `dryness` | 과각질/악건성 | 건조·각질 |
| `sagging` | 피부처짐/탄력저하 | 탄력 |
| `sensitivity` | 민감성(트러블/자극감) | 민감함 |

라벨은 괄호·슬래시까지 **한 글자도 다르지 않게** 옮긴다(AI 서버가 문자열 일치로 인식).

### A.5 루틴 시간

`morning` · `evening` · `daily` · `weekly` · `anytime`

### A.6 등급

| 코드 | score 범위 | 화면 표기 |
|---|---|---|
| `good` | 70–100 | 양호 |
| `normal` | 40–69 | 보통 |
| `care` | 0–39 | 관리 필요 |

### A.7 기타

| 항목 | 값 |
|---|---|
| deviceType | `smartphone` · `tablet` · `camera` |
| gender | `male` · `female` |
| ageBand | `10s` `20s` `30s` `40s` `50s` `60s` `70s` |
| 수준 (수분 섭취·자외선·스트레스) | `low` · `medium` · `high` |
| 지표 metric | `moisture` · `elasticity` · `pigmentation`(높을수록 나쁨) · `pore`(높을수록 나쁨) |

---

## 부록 B. 임시 fixture (교체됨 — 기록용)

> 2026-10-10 `skincare-ai/docs/fixtures/`의 실제 출력으로 교체했다. 레포의 `test/fixtures/ai/`가 기준이고 아래는 최초 임시본 기록이다.

`test/fixtures/ai/skin-metrics.ok.json`

```json
{
  "roi_detected": ["full_face", "forehead", "glabella", "cheek_left", "cheek_right", "chin"],
  "metrics": {
    "full_face":   { "moisture": 0.61, "elasticity": 0.74, "pigmentation": 0.38, "pore": 0.45 },
    "forehead":    { "moisture": 0.58, "elasticity": 0.71, "pigmentation": 0.35, "pore": 0.52 },
    "glabella":    { "moisture": 0.6,  "elasticity": 0.73, "pigmentation": 0.33, "pore": 0.48 },
    "cheek_left":  { "moisture": 0.66, "elasticity": 0.76, "pigmentation": 0.41, "pore": 0.38 },
    "cheek_right": { "moisture": 0.64, "elasticity": 0.75, "pigmentation": 0.43, "pore": 0.4 },
    "chin":        { "moisture": 0.57, "elasticity": 0.72, "pigmentation": 0.36, "pore": 0.44 }
  },
  "confidence": 0.87,
  "calibration_applied": false,
  "is_stub": true,
  "model_version": "skin_reg_stub",
  "inference_ms": 12
}
```

`test/fixtures/ai/recommend.ok.json`

```json
{
  "skin_type": "복합성",
  "concerns": ["모공", "미백(색소침착/기미/칙칙함)"],
  "ingredients": [
    { "name": "나이아신아마이드", "purpose": "피지 분비와 모공 관리에 도움을 줄 수 있습니다.", "priority": 1 },
    { "name": "판테놀", "purpose": "피부 장벽을 지키는 데 도움을 줄 수 있습니다.", "priority": 2 },
    { "name": "알로에신", "purpose": "칙칙한 피부 톤 관리에 도움을 줄 수 있습니다.", "priority": 3 }
  ],
  "routine": [
    { "step": 1, "time": "morning", "action": "약산성 클렌저로 가볍게 세안합니다." },
    { "step": 2, "time": "morning", "action": "자외선 차단제를 바릅니다." },
    { "step": 3, "time": "evening", "action": "나이아신아마이드 세럼을 바릅니다." }
  ],
  "reasoning": [
    "복합성 피부로 T존의 피지 분비가 많아 모공이 도드라져 보일 수 있습니다. 자외선 노출이 많은 생활 습관은 피부 톤이 칙칙해지는 데 영향을 줄 수 있습니다.",
    "나이아신아마이드는 피지 분비와 모공 관리에 도움을 줄 수 있습니다. 판테놀은 피부 장벽을 지키는 데, 알로에신은 칙칙한 톤 관리에 도움을 줄 수 있습니다.",
    "아침에는 약산성 클렌저로 세안한 뒤 자외선 차단제를 바르고, 저녁에는 나이아신아마이드 세럼을 사용하세요."
  ],
  "answer": "복합성 피부에 모공과 톤이 고민이라면 피지 관리와 자외선 차단을 함께 챙기는 것이 도움이 될 수 있습니다.",
  "disclaimer": "본 결과는 참고용 정보이며 의학적 진단이나 치료를 대신하지 않습니다. 피부 이상이 지속되면 피부과 전문의와 상담하세요.",
  "filtered": false,
  "used_fallback": false,
  "input_notes": ["나이는 연령대(20s) 중간값 25세로 추정"],
  "model_version": "rec_sllm_v2",
  "inference_ms": 48100
}
```

AI 실패 응답 형식 (fixture 오류 시나리오용)

```json
{ "success": false, "error": { "code": "E2001", "message": "얼굴을 찾지 못했어요. 정면에서 다시 찍어 주세요.", "detail": null } }
```
