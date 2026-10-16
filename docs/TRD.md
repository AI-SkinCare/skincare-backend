# skincare-backend 기술 요구사항 명세서 (TRD)

| 항목 | 내용 |
|---|---|
| 대상 | `skincare-backend` (Spring Boot API · PostgreSQL · 추천 작업 큐 · Docker Compose) |
| 근거 문서 | **개정 수행계획서 — 파이프라인 개정안 (2026-10-15)**: `skincare-ai/docs/project/09-revised-project-plan.md`와 같은 폴더의 원본 `스킨케어_수행계획서_파이프라인개정_20261015.docx`(브랜치 `feat/rec-sllm-v2`, 커밋 `d09e597`). SC-01 요구사항 정의서, SC-02 시스템 설계서, SC-03 API 명세서, SC-04 DB 설계서, SC-05 화면 설계서, SC-06 협업 규칙, SC-07 WBS, SC-08 테스트 계획서 (모두 v1.0, 2026-10-01, 원본 `skincare-ai/docs/project/`) |
| 작성 | 2026-10-10 · 개정 2026-10-16 · 구현 담당 이현승 · 리뷰 정도희 |
| 용도 | Claude Code가 이 문서만 보고 백엔드를 구현·테스트할 수 있도록 팀 문서의 백엔드 관련 내용을 한곳에 모으고, 구현에 필요한 세부 결정을 보탠 문서. |

**표기**

- 표시 없음 — 팀 문서(SC-0x) 그대로
- **[개정]** — 개정 수행계획서(2026-10-15)에서 온 것. SC-0x의 회귀 지표 측정 관련 내용을 대체한다. `[개정 B.1]`처럼 절 번호를 붙인다
- **[구현 결정]** — 팀 문서에 없거나 모호해서 이 문서가 정한 것. API 계약(경로·필드·코드)에는 영향 없음
- **[앱 협의]** — iOS 앱 담당(정도희)과 합의해 API 계약에 **더한** 것. 기존 동작은 그대로 두는 추가 변경이며, SC-03에도 반영해야 한다(v1.0.1 단순 추가, §18.3)

**우선순위**: 개정 수행계획서 > 그 밖의 팀 문서(SC-0x) > 이 문서 > 코드에서의 추측. 이 문서와 팀 문서가 다르면 팀 문서를 따르고 이 문서를 고친다. SC-0x 중 회귀 지표(수분·탄력·색소침착·모공)·ROI·점수·추이에 관한 내용은 개정안으로 범위에서 빠졌다.

**클라이언트**: 실제 클라이언트는 **Swift iOS 앱**이다. 개정 수행계획서(3.2)가 Swift iOS 앱 → Spring Boot → FastAPI 구성을 채택했다(SC-0x에는 아직 React 웹·NestJS로 적혀 있음). 네이티브 앱용 리프레시 토큰 본문 전달(`X-Client`, §9.1)은 [앱 협의]로 더했다. 앱은 `Origin`을 보내지 않으므로 CORS와도 무관하다.

**개정 이력**

| 날짜 | 내용 |
|---|---|
| 2026-10-10 | 최초 작성 |
| 2026-10-10 | [앱 협의] §9 네이티브 앱 리프레시 토큰 본문 전달(`X-Client: ios`) 추가 — SC-03 반영 필요 |
| 2026-10-10 | §12.7·부록 B: fixture를 `skincare-ai/docs/fixtures/` 실제 출력으로 교체 |
| 2026-10-10 | §8.4 배포 서버 시드 절차, §16.1 실제 AI 계약 테스트·스모크, §17.4 스모크 단계, §18.1 Phase 7 |
| 2026-10-12 | §4 image-size 2.x, 하위 의존성 보안 패치(overrides) |
| 2026-10-12 | §12.2 Cloudflare 524를 timeout으로 분류 (재시도 금지) |
| 2026-10-13 | **NestJS → Spring Boot 4.1 (Java 21) 전환** [구현 결정] — §4·5·6·7·8.2–8.4·10.1·16·17·18. API 계약·DDL 변경 없음. 환경변수 `NODE_ENV` → `APP_ENV`, JWT 비밀키 32자 이상, `LOG_LEVEL` 값은 error·warn·info·debug |
| 2026-10-16 | **개정 수행계획서(2026-10-15) 반영 — 회귀 지표 측정 → DenseNet121 6종 이미지 분류 + Qwen2.5 추천** [개정]. 호환이 깨지는 변경(앱 DTO 함께 갱신). 분석 응답을 `disease`·`recommendationStatus`·`guidance` 구조로, 작업 상태를 `QUEUED`·`GENERATING`·`COMPLETED`·`WITHHELD`·`FAILED`로. 측정 결과(`measurement`)·추이 API·`deviceType` 삭제, 점수 계산(§11)을 분류·추천 상태 규칙으로 교체. AI 연동 `/infer/disease`·`/infer/disease-recommend`. DB V2(기존 분석 삭제 — 이현승 결정, `skin_metrics`·회귀 컬럼 삭제, 분류 컬럼 추가). env `AI_SKIN_TIMEOUT_MS` → `AI_CLASSIFY_TIMEOUT_MS`, fixture 시나리오 `uncertain`·`no-concern` 추가. Spring Boot·Swift 구성이 개정안에 반영돼 "팀 합의 필요" 표시 삭제 — §1–8·10–14·16–18·부록 A·B |

---

## 1. 개요

### 1.1 서비스 [개정 1.1]

스마트폰으로 촬영한 피부(얼굴) 사진 1장 + 설문을 받아, DenseNet121이 6종(건선·아토피·여드름·정상·주사·지루)으로 이미지를 분류하고, 그 분류 결과를 Qwen2.5(v2 LoRA) 추천 입력으로 바꿔 스킨케어 성분·관리법을 제시하는 iOS 앱 서비스.

분류 결과는 **참고 정보**이고 확정 진단이나 치료 처방으로 표현하지 않는다. 성분 추천도 기존 피부 고민 데이터에 기반한 **보조 관리 정보**로 한정한다. 확정 진단, 치료·처방·의약품 안내, 제품 판매·광고는 범위 밖이다. 기존의 수분·탄력·색소침착·모공 회귀 예측, 9개 관심영역(ROI) 분할, 촬영 환경 보정도 개정안에서 범위 밖으로 뺐다 [개정 1.2].

### 1.2 백엔드의 책임

| 책임 | 내용 |
|---|---|
| 인증 | 가입(동의 4종), 로그인, 액세스/리프레시 토큰, 로그아웃, 탈퇴 |
| 분석 API | 업로드 수신 → AI 이미지 분류(동기, 목표 3초) → 저장 → 추천 대기열 등록 → 결과·이력·삭제 |
| 추천 작업 큐 | PostgreSQL 기반 큐, 워커 1개가 GPU 추천(약 45초)을 한 건씩 요청. 추천 보류(uncertain·no_concern)도 같은 경로로 AI 응답을 받아 저장 |
| AI 서버 중계 | 클라이언트는 AI 서버를 모른다. 주소·토큰은 백엔드 환경변수에만 있다 |
| 검증·변환 | AI 응답 검증(분류 라벨·점수 범위, 추천 열거형), snake_case → camelCase, 추천 라벨 → 코드 |
| 상태 | AI 가용성(30초 헬스체크), 대기열 길이 |
| 배포 구성 | Dockerfile, docker-compose.yml(web·backend·db·cloudflared), DB 마이그레이션, CI |

### 1.3 백엔드가 하지 않는 것

iOS 앱(Swift, 정도희), nginx 설정 세부(정도희), AI 추론·분류 임계값·분류→고민 매핑·금칙 필터·면책·안내 문구 생성(AI 서버, 김우주), Cloudflare 터널 운영, DB 백업 cron(정도희, WBS 6.2).

**분류→고민 매핑과 추천 보류 판단(불확실·정상 무고민)을 백엔드에 복제하지 않는다.** 최종 `recommendationStatus`는 AI 응답을 따른다 [개정 B.1].

### 1.4 일정 (개정 4.2, 오늘 10/16 기준)

| 기간 | 단계 | 백엔드 기준 |
|---|---|---|
| 10/16–10/20 | 계약 확정과 중간 시연 | Swift·Spring DTO와 작업 상태 합의(부록 B 계약), fixture 모드로 새 흐름 동작. **중간 발표 10/20** |
| 10/21–11/9 | 서비스 연동과 품질 보완 | 작업 큐·실제 AI(DenseNet + Qwen2.5) 연동, 앱 화면 연동 |
| 11/10–11/24 | 기본 기능 시험 | 실제 사진·반례·부하·재시도 검증 |
| 11/25–12/1 | 기능 완료와 안정화 | 오류 수정·배포 점검 |
| 12/2–12/8 | 성능 분석과 보고 | 목표 대비 결과·한계·개선안 |

---

## 2. 설계 원칙 (SC-02 §1, 개정 3.1)

1. **클라이언트는 AI 서버를 모른다.** 모든 요청은 백엔드를 거친다. `AI_BASE_URL`·`AI_INTERNAL_TOKEN`은 백엔드 환경변수에만 있다.
2. **분류는 빠르게, 추천은 줄 세워서.** 이미지 분류는 `POST /analyses` 요청 안에서 끝내고, 추천은 큐에 넣어 워커가 GPU에 한 건씩 보낸다.
3. **얼굴 이미지는 디스크에 닿지 않는다.** 메모리 버퍼로만 받아 AI 서버로 넘기고 분류 직후 버린다. 추천 단계에는 이미지가 필요 없다(저장한 분류 JSON과 설문만 보낸다).
4. **AI 서버가 꺼져도 서비스는 산다.** 분석 제출만 막고 로그인·이력 등 나머지는 정상 동작한다.
5. **AI 출력은 그대로 믿지 않는다.** 분류 라벨·점수 범위와 추천 열거형은 백엔드가 한 번 더 검증한다. 다만 분류→고민 매핑과 보류 판단은 AI 몫이라 복제하지 않는다.

분류와 추천을 나눈 이유: 추천 생성은 약 45초(10/15 실측 중앙값 43.33초, 최대 50.04초)라 한 번에 호출하면 사용자가 분류 결과도 못 보고 기다린다. 둘 다 큐에 넣으면 이미지를 임시 저장해야 해서 원칙 3을 어긴다. AI의 `/infer/disease-analyze`(한 번에 실행)는 통합 시험·시연용이고 서비스 흐름은 2단계다 [개정 A.4·B.1].

---

## 3. 시스템 구성

```
[사용자 iPhone: Swift 앱]
        │ HTTPS
        ▼
┌──────────── 정도희 자택 서버 · Docker Compose ────────────┐
│ cloudflared (app.<도메인> → web:80)                        │
│   → web: nginx 1.27 (/api → backend 프록시, 업로드 12MB 제한)     │
│       → backend: Spring Boot :3000 (외부 비공개)           │
│           ├─ db: PostgreSQL 16 (볼륨 pgdata, 외부 비공개)   │
│           └─ HTTPS + X-Internal-Token ─────────────┐       │
└────────────────────────────────────────────────────│───────┘
                                                     ▼
┌──────── 김우주 PC · Windows 11 · RTX 5070 Ti 16GB ────────┐
│ cloudflared (ai.<도메인> → localhost:8000)                 │
│   → FastAPI 추론 서버                                       │
│      DenseNet121 6종 분류 (CPU) + Qwen2.5-7B·rec_sllm_v2 (GPU 4-bit) │
└────────────────────────────────────────────────────────────┘
```

- 백엔드 인스턴스는 **1개**다. 메모리 상태(헬스 결과, 진행 중 제출 잠금, 로그인 제한 카운터, fixture 시나리오 맵)를 인스턴스 안에 둬도 된다.
- AI 서버는 조장 PC에서만 돈다. 학습 중이면 추론 서버를 함께 운영하지 않으므로(개정 2.2) `aiAvailable=false`가 정상 상태다.
- 백엔드·DB의 배포 위치는 개정안에서 팀이 확정할 사항이다(개정 4.1). 확정 전까지 위 구성을 쓴다.
- 도메인이 없으면(결정 D3) 임시 터널 주소를 쓰며, 재시작할 때마다 `AI_BASE_URL`을 바꾸고 백엔드를 재시작해야 한다.

### 3.1 분석 처리 시퀀스 [개정 B.1]

```
FE ──POST /analyses(image, survey)──▶ BE
BE: 인증 · 요청 제한 · 대기열 상한 · AI 가용성 · 형식 검사
BE ──POST /infer/disease(image, 20초)──▶ AI (DenseNet121, CPU)
  ├ [실패] 422 E2002(디코딩 실패) 등 ─▶ BE ─오류(분석 생성 안 함)─▶ FE (재촬영)
  └ [성공] AI ─분류 JSON(label·label_en·confidence·uncertain·probabilities·model_version·inference_ms)─▶ BE
       BE: 이미지 버퍼 폐기
       BE ─▶ DB: analyses(status=QUEUED, 분류 JSON) + surveys 저장
       BE ─202 {analysisId, status: QUEUED, disease, recommendationStatus: null, recommendation: null,
                guidance: [], error: null, queuePosition, estimatedWaitSec, …}─▶ FE
FE ──GET /analyses/{id} (처음 3분 3초, 이후 10초)──▶ BE ─분류 결과 + 작업 상태─▶ FE
Worker ◀─ DB: 가장 오래된 QUEUED 1건 획득 (→ GENERATING)
Worker ──POST /infer/disease-recommend(저장한 분류 JSON 전체 + 설문, 130초)──▶ AI
  AI: 점수 < 0.6 또는 uncertain → uncertain / 정상·고민 없음 → no_concern  (GPU 생성 없이 바로 응답)
      그 밖 → Qwen2.5 생성 (약 45초)
  AI ─{recommendation_status, recommendation | null, guidance}─▶ Worker
Worker ─▶ DB: generated → recommendations 저장 + COMPLETED / uncertain·no_concern → WITHHELD (추천 행 없음)
FE ──GET /analyses/{id}──▶ BE ─최종 결과(같은 형태)─▶ FE
```

---

## 4. 기술 스택

10/13 NestJS → **Spring Boot**로 전환했고 [구현 결정], 개정 수행계획서(3.2)가 Spring Boot REST API·서버 작업 큐 구성을 팀 계획으로 채택했다. SC-02·SC-06·SC-07·개발운영계획서는 아직 NestJS로 적혀 있다. NestJS 구현은 `archive/nestjs` 브랜치에 보관한다(개정 전 API).

| 영역 | 선택 | 비고 |
|---|---|---|
| 런타임 | Java 21 (Temurin) | |
| 프레임워크 | Spring Boot 4.1 (Spring MVC, 내장 Tomcat) | `spring-boot-starter-webmvc` |
| DB 접근 | `JdbcClient`(spring-boot-starter-jdbc) + HikariCP, PostgreSQL 16 | [구현 결정] ORM 없이 SQL 그대로 — §13 큐 SQL이 대부분이라 |
| 마이그레이션 | Flyway (`src/main/resources/db/migration`) | 앱 기동 시 적용. 스키마 자동 생성 없음 |
| 검증 | Jakarta Bean Validation(`spring-boot-starter-validation`) + 설문은 직접 검증 | 오류는 422 E4002 |
| JSON | Jackson 3 (Boot 기본) | [구현 결정] 문자열 ↔ 숫자·불리언 자동 변환 끔 (타입이 다르면 E4002) |
| 문서 | springdoc-openapi 3.1 | `/api/docs`, JSON `/api/docs-json` — **OpenAPI 3.0으로 출력** (앱은 이 JSON으로 swift-openapi-generator 등을 써서 타입을 만들 수 있음) |
| 설정 | `@ConfigurationProperties` record + 기동 시 검증 | `.env`는 `spring.config.import`로 읽음 |
| 인증 | JWT HS256 — `nimbus-jose-jwt` | [구현 결정] 키 256비트 이상이어야 해서 비밀키 32자 이상 |
| 해시 | `spring-security-crypto` `BCryptPasswordEncoder($2B, 12)` | `$2b$12$` 접두 (TC-SEC-02). Spring Security 필터 체인은 쓰지 않음 |
| 업로드 | Servlet multipart, **메모리에서만** | `file-size-threshold`(11MB) > 최대 파일(10MB)이라 디스크에 쓰지 않음. `resolve-lazily`로 인증·제한 검사 뒤에 파싱 |
| 스케줄 | `ThreadPoolTaskScheduler` + `@Scheduled` | 워커 폴링, 헬스체크, 장기 대기 만료, 토큰 정리 |
| ID | ULID (직접 구현, `SecureRandom`) | [구현 결정] 추가 의존성 없음 |
| 이미지 헤더 | 직접 파싱 (PNG IHDR, JPEG SOFn) | [구현 결정] 디코딩·저장 없음 |
| AI HTTP | Spring `RestClient` + JDK `HttpClient` | [구현 결정] 연결 5초, 읽기 타임아웃은 분류·추천별 |
| 로깅 | Spring Boot 구조화 로그(logstash JSON) + MDC(requestId·userId) | [구현 결정] 금지 항목은 애초에 로그에 넣지 않는다 (§7.6) |
| 테스트 | JUnit 6 + AssertJ(단위), `@SpringBootTest` + 실제 HTTP + PostgreSQL(통합) | 태그로 분리 (§16) |
| 빌드·형식 | Gradle 9 (wrapper), Spotless + google-java-format | |
| 부하 | k6 (`load/`) | 11/10~ |

Redis·BullMQ 등 별도 큐 인프라는 쓰지 않는다(SC-02 §4.2).

---

## 5. 프로젝트 구조 [구현 결정]

10/16 구현 기준 실제 구조. 파일을 옮기거나 더하면 이 트리도 고친다. 패키지 `com.aiskincare.backend` 아래.

```
skincare-backend/
├─ CLAUDE.md · README.md        # README 첫 줄: skincare-ai/docs/project/ 링크
├─ docs/TRD.md · docs/ios-integration.md
├─ .env.example                 # 가짜 값만
├─ build.gradle · settings.gradle · gradlew · gradle/wrapper/
├─ Dockerfile                   # JDK 빌드 → JRE 실행, 기동 시 Flyway
├─ docker-compose.yml · nginx/default.conf
├─ .github/                     # ISSUE_TEMPLATE·PR 템플릿(skincare-ai에서 복사), workflows/ci.yml
├─ load/                        # k6 스크립트(perf-01·03·04), lib/common.js, latency-report.sql·latency-rows.sql, README
├─ src/main/java/…/
│  ├─ SkincareBackendApplication.java   # --seed면 웹 없이 시드만
│  ├─ config/                   # AppProperties(env 검증), Constants, DataSourceConfig(DATABASE_URL 파싱·Flyway),
│  │                            # WebConfig(/api/v1 접두·인터셉터 순서·필터), JacksonConfig, SchedulingConfig,
│  │                            # OpenApiConfig·NullableRefCustomizer(봉투·에러 응답·보안·nullable 참조)
│  ├─ common/
│  │  ├─ enums/                 # 부록 A 단일 출처 (코드 ↔ AI 라벨)
│  │  ├─ error/                 # ErrorCode, ApiException, GlobalExceptionHandler, EnvelopeErrorController(/error도 봉투)
│  │  ├─ web/                   # ApiEnvelope·ErrorEnvelope, RequestIdFilter, RequestLogFilter, SecurityHeadersFilter,
│  │  │                         # ClientIp, @PublicApi, @CurrentUser, @ApiErrorCodes
│  │  ├─ validation/FieldIssue  # 검증 오류 → detail.fields
│  │  └─ util/                  # TimeUtil(KST·ISO), Ulid, MaskEmail, Sleeper(지연 주입)
│  ├─ auth/                     # AuthController·AuthService, RefreshTokenService(교체·재사용 감지·정리 배치),
│  │                            # AuthInterceptor, LoginRateLimiter, JwtTokens, RefreshCookies, ClientType([앱 협의] X-Client), TokenHash, dto/
│  ├─ users/                    # UsersController·UsersService·UserRepository
│  ├─ analyses/
│  │  ├─ AnalysesController · AnalysesService · AnalysisRepository · AnalysisRows
│  │  ├─ AnalysisPresenter      # DB 행 → API 응답 (§10.2, §11)
│  │  ├─ SurveyParser · NormalizedSurvey · Submission(처리 순서 6) · ImageInspector(시그니처·크기)
│  │  ├─ AnalysisGuardInterceptor · @AnalysisGuards · SubmissionLock   # 처리 순서 2–4
│  │  └─ dto/
│  ├─ recommendation/           # RecommendationWorker, RecommendationQueueRepository(§13 SQL),
│  │                            # QueueMaintenance(장기 대기 만료·기동 복구), BackgroundJobs(기동 순서)
│  ├─ aiclient/                 # AiClient → HttpAiClient(real) / FixtureAiClient(fixture), FixtureScenarioStore,
│  │                            # AiError(분류), AiErrorMapper(§12.5 표), AiResponseParser(응답 검증), AiTransform(설문·분류 → AI 요청)
│  ├─ system/                   # AiHealthService, SystemController
│  └─ seed/                     # SeedRunner(./gradlew seed), SeedData(통합 테스트와 공유)
├─ src/main/resources/
│  ├─ application.yml
│  ├─ db/migration/V1__create_initial_schema.sql · V2__disease_pipeline.sql
│  └─ fixtures/ai/disease-pipeline/   # generated·uncertain·no_concern.response.json (skincare-ai 기록, AI_MODE=fixture가 씀)
└─ src/test/java/…/
   ├─ support/                  # IntegrationTestBase, TestHttp, Factories, TestImages(합성 이미지), FakeSleeper, TestDatabase, LogLines
   ├─ **/*Test.java             # 단위 (DB 없음)
   ├─ **/*IT.java               # 통합 (@Tag("integration"), PostgreSQL)
   └─ tools/                    # SmokeFlow(./gradlew smoke), LoadImage(./gradlew loadImage)
```

클래스는 Java 관례대로 PascalCase, 패키지는 기능별(소문자). SQL 파일은 `V<번호>__<설명>.sql`.

---

## 6. 환경변수

`.env.example`에는 가짜 값만 커밋하고 `.env`는 `.gitignore`. 실제 값은 담당자에게 1:1로만 전달한다. 비밀키 생성: `openssl rand -base64 48`. 앱은 실행 위치의 `.env`를 읽고, 같은 이름의 환경변수가 있으면 환경변수가 우선한다.

| 변수 | 예 | 설명 |
|---|---|---|
| `APP_ENV` | development \| test \| production | [구현 결정] production에서만 fixture 헤더 무시, 시드 금지, 쿠키 항상 Secure. Docker 이미지 기본값 production |
| `PORT` | 3000 | |
| `DATABASE_URL` | `postgres://skincare:***@db:5432/skincare` | 앱이 JDBC URL로 바꾼다 |
| `JWT_ACCESS_SECRET` / `JWT_REFRESH_SECRET` | 48바이트 난수 | 서로 다른 값, **32자 이상** (기동 시 검증) |
| `CORS_ORIGIN` | `https://app.<도메인>` | 비우면 CORS 끔. iOS 앱은 CORS와 무관(웹에서 부를 때만 의미) |
| `AI_MODE` | fixture \| real | 로컬 기본 fixture |
| `AI_BASE_URL` | `https://ai.<도메인>` | real일 때 필수 |
| `AI_INTERNAL_TOKEN` | 48바이트 난수 | AI 서버와 같은 값. real일 때 필수 |
| `AI_CLASSIFY_TIMEOUT_MS` | 20000 | 이미지 분류(`/infer/disease`) 읽기 제한 [개정 B.2]. 10/16 `AI_SKIN_TIMEOUT_MS`에서 이름을 바꿨다 |
| `AI_REC_TIMEOUT_MS` | 130000 | AI 내부 120초보다 길게 [개정 B.2] |
| `AI_HEALTH_INTERVAL_MS` | 30000 | |
| `REC_WORKER_CONCURRENCY` | 1 | **1 외의 값이면 기동 실패** [구현 결정] |
| `REC_QUEUE_LIMIT` | 10 | QUEUED 건수 상한 (AI 서버 QUEUE_LIMIT과 별개) [개정 B.2] |
| `REC_QUEUE_MAX_WAIT_MIN` | 60 | 초과 시 FAILED E3001 |
| `ANALYSIS_DAILY_LIMIT` | 20 | KST 00:00 기준. 스테이징 성능 테스트 때 100 |
| `LOGIN_RATE_PER_MIN` | 10 | IP당, CF-Connecting-IP 기준 |
| `TRUST_PROXY` | true | `CF-Connecting-IP`가 없을 때 `X-Forwarded-For` 첫 값을 쓸지 |
| `AI_FIXTURE_SCENARIO` | ok | ok \| uncertain \| no-concern \| no-face \| rec-timeout \| rec-slow \| ai-down (§12.7) |
| `COOKIE_SECURE` | true | [구현 결정] production에선 항상 true. 로컬 HTTP 개발에서만 false 허용 |
| `LOG_LEVEL` | info | [구현 결정] error \| warn \| info \| debug |
| `SEED_PASSWORD` | (개발용 값) | [구현 결정] 시드 계정 공통 비밀번호. production에선 시드 자체가 거부됨 |

추가 규칙 [구현 결정]:

- 기동 시 검증: 필수값 누락, `APP_ENV`·`AI_MODE`·`AI_FIXTURE_SCENARIO` 값 오류, `AI_MODE=real`인데 `AI_BASE_URL`/`AI_INTERNAL_TOKEN` 없음, 두 JWT 비밀키가 같거나 32자 미만, `REC_WORKER_CONCURRENCY≠1` → 즉시 종료. 오류 메시지에는 변수 이름만 넣고 값은 넣지 않는다.
- 토큰 수명(액세스 3600초, 리프레시 14일), 워커 폴링 1초, 재시도 대기 5초, 장기 대기 점검 1분은 코드 상수(`config/Constants`).

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
{ "success": false, "error": { "code": "E2004", "message": "JPEG 또는 PNG 사진만 올릴 수 있어요.", "detail": null } }
```

- 성공 봉투는 컨트롤러가 `ApiEnvelope.ok(data)`로 돌려준다 [구현 결정]. **204는 본문 없음.**
- 실패는 전역 예외 처리(`GlobalExceptionHandler`) 하나가 만든다. 서비스 코드는 `throw new ApiException(ErrorCode.E4003)` 형태만 쓰고, Spring 기본 예외(경로 없음·메서드 불일치·본문 파싱 실패·업로드 한도 등)·라이브러리 예외도 여기서 아래 표의 코드로 바꾼다. 필터·Tomcat 단계 오류가 가는 `/error`도 같은 봉투로 바꾼다(`EnvelopeErrorController`). 매핑되지 않는 예외는 500 E4001 + ERROR 로그(스택·requestId 포함, 요청 본문 제외).
- `message`는 **사용자에게 그대로 보여줘도 되는 문장**, `detail`은 개발용(화면 비표시).
- Swagger에 봉투 구조가 그대로 나오도록 응답 타입을 `ApiEnvelope<T>`로 선언하고, 실패 응답은 `@ApiErrorCodes`로 상태별 `ErrorEnvelope`를 붙인다 — 앱이 타입을 만들 때 이것에 의존한다 [구현 결정]. 출력은 OpenAPI 3.0이고, null일 수 있는 참조 필드(`recommendation`·`error`)는 `{ type: object, nullable: true, allOf: [$ref] }`로 낸다.

### 7.3 에러 코드

| 코드 | HTTP | 의미 | 기본 message | 출처 |
|---|---|---|---|---|
| E1001 | 401 | 토큰 없음·만료·위조 | 로그인이 필요해요. 다시 로그인해 주세요. | [구현 결정] |
| E1002 | 403 | 권한 없음 | 접근 권한이 없어요. | [구현 결정] |
| E1003 | 401 | 이메일 또는 비밀번호 불일치 | 이메일 또는 비밀번호가 맞지 않아요. | SC-05 |
| E1004 | 409 | 이미 가입된 이메일 | 이미 가입된 이메일이에요. | [구현 결정] |
| E2001 | 422 | 얼굴 미검출 — **AI에 아직 없음**(개정 B.3). 매핑만 유지 | 얼굴을 찾지 못했어요. 정면에서 다시 찍어 주세요. | SC-03 |
| E2002 | 422 | 이미지 품질 미달·해상도 부족·AI 디코딩 실패 | 사진이 흐리거나 너무 어두워요. 밝은 곳에서 다시 찍어 주세요. | SC-05 변형 |
| E2003 | 413 | 이미지 10MB 초과 | 사진 용량이 너무 커요. 10MB 이하로 올려 주세요. | [구현 결정] |
| E2004 | 415 | JPEG·PNG 아님 | JPEG 또는 PNG 사진만 올릴 수 있어요. | [구현 결정] |
| E3001 | 503 | AI 서버 응답 없음·설정 오류·분류기 비활성, 또는 60분 넘게 처리 못 함 | 지금은 분석 서버 점검 중이에요. 잠시 후 다시 시도해 주세요. | SC-05 변형 |
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
- 문구는 SC-05 §4 문구 규칙을 지킨다: "진단·치료·처방·완치·개선됩니다·효과가 있습니다·질환명·정상/비정상" 금지. 새 문구는 PR에서 손승우 확인. 이 규칙은 **백엔드가 만드는 문구**에 적용한다. 분류 라벨(`disease.label`)·`guidance`·추천 문장은 AI가 만든 값을 그대로 전달하고, 화면에서 참고 결과로 보여 주는 것은 앱 몫이다 [개정 1.1·A.3].
- E4002 `detail` 형식 [구현 결정]: `{ "fields": [ { "field": "survey.gender", "reason": "required" } ] }` (TC-ANA-02: detail에 gender). 그 외 코드의 detail은 `null`. production에서 E4001 detail은 항상 `null`.
- Spring 기본 400(검증 실패·본문 파싱 실패·타입 불일치·필수 파라미터 없음)을 쓰지 않고 **422 E4002**로 바꾼다. 모르는 필드는 무시하고, 문자열 ↔ 숫자·불리언 자동 변환은 하지 않는다(타입이 다르면 E4002) [구현 결정].

### 7.4 필드·시간·ID·페이지

| 항목 | 규칙 |
|---|---|
| JSON 필드 | camelCase. 열거형 **값**은 소문자 snake_case (`no_concern`, `anytime`). **예외: 분석 `status`는 대문자**(`QUEUED` 등, 개정 B.1 — DB 값도 같음) |
| DB | snake_case. SQL에 컬럼 이름을 그대로 쓴다 [구현 결정] (ORM 매핑 없음) |
| 시간 | 저장 `TIMESTAMPTZ`(UTC). 응답 ISO 8601 UTC `2026-10-15T09:12:33Z` — 밀리초 제거 [구현 결정] (`common/util/TimeUtil.toIsoUtc`) |
| KST | 일일 한도·배치 시각에만 사용. 한국은 서머타임이 없으므로 UTC+9 고정 계산 |
| 분석 ID | ULID 26자. 경로 파라미터가 ULID 형식(`^[0-9A-HJKMNP-TV-Z]{26}$`)이 아니면 **404 E4003** (422 아님 — 존재 여부를 흘리지 않음) [구현 결정] |
| 사용자 ID | BIGSERIAL 숫자 |
| 페이지 | `?page=1&size=20`, page ≥ 1, 1 ≤ size ≤ 50, 위반 시 E4002. 응답 `data.page = { page, size, totalItems, totalPages }` (`totalPages = ceil(totalItems/size)`, 0건이면 0) |

### 7.5 X-Request-Id

- 가장 바깥 필터(`RequestIdFilter`)에서 요청 헤더 `X-Request-Id`가 `^[A-Za-z0-9._-]{1,64}$`이면 그대로, 아니면 UUID v4 생성 [구현 결정]. iOS 앱은 요청마다 생성해서 보낸다 [앱 협의].
- **모든 응답(에러 포함)**에 `X-Request-Id` 헤더. 백엔드 → AI 호출에도 같은 값 전달.
- 워커 작업은 요청 맥락이 없으므로 작업마다 새 UUID를 만들고 로그에 `analysisId`를 같이 남긴다 (MDC).

### 7.6 로깅 (NFR-PRI-02, NFR-OBS-01)

- JSON 로그(Spring Boot 구조화 로그, logstash 형식). 요청마다 `request completed` 한 줄: `method`, `route`, `status`, `durationMs`. `requestId`·`userId`(있으면)·워커의 `analysisId`는 MDC로 모든 줄에 붙는다 [구현 결정].
- MDC에 있는 키(`requestId`·`userId`·`analysisId`)를 로그 key-value로 또 넣지 않는다 — 구조화 로그가 키 중복으로 그 줄을 버린다 [구현 결정].
- **절대 기록하지 않음**: 이미지(버퍼·base64·파일명 포함), 설문 원문(`note`, `currentProducts` 등 전체), 비밀번호, 액세스·리프레시 토큰, 쿠키, `X-Internal-Token`, 요청/응답 본문 전체. 분류 라벨·확률과 `guidance`·추천 문장도 로그에 넣지 않는다(건강 관련 추정값) [구현 결정] — 로그에는 `uncertain`·`recommendationStatus`·소요 시간만.
- 요청·응답 헤더와 본문은 로그 대상이 아니다(필터가 메서드·경로·상태·시간만 남김). 서비스 로그는 위 항목을 key-value로 넣지 않는다 — TC-PRI-02가 전체 흐름 로그에서 확인한다.
- 이메일은 마스킹 `d***@gmail.com` (첫 글자 + `***` + `@도메인`).
- AI 오류는 HTTP 상태·에러 코드·소요 시간만 남기고 AI 응답 본문 전체는 남기지 않는다(개정 B.3: 내부 detail·토큰·원본 입력을 앱과 로그에 노출하지 않음). AI 응답 검증 실패는 실패한 필드 경로와 이유만 남긴다.
- ERROR 로그 대상: AI 401(내부 토큰 불일치), AI 422 E4002(백엔드 변환 버그), AI 응답 스키마 위반, 미처리 예외.

---

## 8. DB (SC-04, 개정 3.2)

PostgreSQL 16, 스키마 `public`, UTF-8, 시간대 UTC. 저장 대상은 사용자·설문·분류·추천·작업 상태·모델 버전이다 [개정 3.2].

### 8.1 현재 스키마 (V1 + V2)

`V1__create_initial_schema.sql`(SC-04 그대로) 위에 `V2__disease_pipeline.sql`이 회귀 지표 부분을 걷어내고 분류 컬럼을 더한다(§8.3). 아래는 두 마이그레이션을 적용한 결과다(컬럼의 물리 순서는 다를 수 있다).

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
  id                    CHAR(26)     PRIMARY KEY,  -- ULID, URL에 노출
  user_id               BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  status                VARCHAR(16)  NOT NULL DEFAULT 'QUEUED'
                        CHECK (status IN ('QUEUED','GENERATING','COMPLETED','WITHHELD','FAILED')),
  -- POST /infer/disease 분류 결과. 추천 호출·재시도 때 그대로 다시 보낸다 [개정 B.1·B.2]
  disease_label         VARCHAR(16)  NOT NULL      -- label_en 코드 (부록 A.2)
                        CHECK (disease_label IN ('psoriasis','atopic','acne','normal','rosacea','seborrheic')),
  disease_confidence    DOUBLE PRECISION NOT NULL CHECK (disease_confidence BETWEEN 0 AND 1),
  disease_uncertain     BOOLEAN      NOT NULL,
  disease_probabilities JSONB        NOT NULL,     -- {"건선": 0.02, …} 6종, AI 값 그대로
  disease_model_version VARCHAR(80)  NOT NULL,
  disease_inference_ms  INTEGER      NOT NULL,     -- AI 내부 분류 시간
  classify_ms           INTEGER      NOT NULL,     -- 분류 API 왕복
  recommendation_status VARCHAR(16)                -- AI 값. COMPLETED·WITHHELD일 때만
                        CHECK (recommendation_status IN ('generated','uncertain','no_concern')),
  guidance              JSONB        NOT NULL DEFAULT '[]',  -- AI 안내 문장 배열
  queued_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),  -- 재요청 시 갱신
  rec_started_at        TIMESTAMPTZ,               -- 워커 획득 시각
  completed_at          TIMESTAMPTZ,               -- COMPLETED·WITHHELD·FAILED 확정 시각
  queue_wait_ms         INTEGER,                   -- rec_started_at − queued_at
  recommend_ms          INTEGER,                   -- 추천 API 왕복
  attempt_count         SMALLINT     NOT NULL DEFAULT 0,  -- 추천 호출 횟수(재시도 포함)
  error_code            VARCHAR(10),
  created_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_analyses_user_created ON analyses(user_id, created_at DESC);
CREATE INDEX idx_analyses_queue ON analyses(queued_at) WHERE status = 'QUEUED';

CREATE TABLE surveys (
  analysis_id CHAR(26)    PRIMARY KEY REFERENCES analyses(id) ON DELETE CASCADE,
  gender      VARCHAR(8)  NOT NULL,
  age_band    VARCHAR(4)  NOT NULL,
  skin_type   VARCHAR(16),            -- 사용자 자가응답 코드
  payload     JSONB       NOT NULL    -- 제출한 설문 전체(클라이언트 코드)
);

-- recommendation_status = generated(COMPLETED)일 때만 행이 있다. 보류(WITHHELD)는 행 없음
CREATE TABLE recommendations (
  analysis_id   CHAR(26)    PRIMARY KEY REFERENCES analyses(id) ON DELETE CASCADE,
  skin_type     VARCHAR(16) NOT NULL,  -- 도출된 피부 타입 **코드**
  concerns      JSONB       NOT NULL,  -- 고민 코드 배열 ["sensitivity"]
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

분류 JSON을 반올림 없이 저장하는 이유: 추천 호출과 재시도 때 분류를 다시 하지 않고 같은 값을 보낸다 [개정 B.2]. AI가 확률 합(±0.001)과 최고 점수·`confidence` 일치(±0.0002)를 검증하므로 값을 바꾸지 않는다 — `disease_confidence`는 DOUBLE PRECISION, 확률은 Jackson이 쓴 숫자 그대로 JSONB에 둔다 [구현 결정].

### 8.2 DB 접근 주의 [구현 결정]

- ORM 없이 `JdbcClient`로 SQL을 쓴다. 행은 record(`AnalysisRows` 등)로 직접 매핑한다.
- `disease_confidence`는 `getDouble`. `disease_probabilities`·`guidance` 등 JSONB는 문자열로 읽어 Jackson으로 바꾼다. **JSONB는 키 순서를 보존하지 않으므로** 확률은 읽은 뒤 부록 A.2 순서로 다시 놓는다.
- BIGSERIAL(`users.id`, `refresh_tokens.id`)은 `long`. `COUNT(*)`는 `Long`으로 읽는다. JWT `sub`에는 사용자 id 문자열.
- 스키마 자동 생성·변경 없음(Flyway만). 앱 기동 시 Flyway가 미적용 마이그레이션을 먼저 적용하고, 실패하면 앱이 뜨지 않는다(§17).
- 상태·열거형 컬럼은 varchar + CHECK. Postgres enum 타입을 만들지 않는다.
- 여러 테이블을 쓰는 작업(분석 생성, 추천 저장, 토큰 교체)은 `TransactionTemplate` 트랜잭션 하나.
- 큐 SQL(§13.3)은 `FOR UPDATE SKIP LOCKED`를 그대로 쓴다.
- 연결 풀 10(HikariCP), 앱 연결에 `statement_timeout` 5초·`TIME ZONE 'UTC'`. 마이그레이션 연결은 풀을 쓰지 않아 제한이 없다.

### 8.3 마이그레이션

- Flyway만 사용. 파일 `src/main/resources/db/migration/V<번호>__<설명>.sql`. **적용된 파일은 고치지 않는다** — 바꿀 것이 있으면 새 번호로 추가한다(체크섬이 달라지면 기동 실패).
- `V1__create_initial_schema.sql`: SC-04 그대로(NestJS 때 TypeORM 마이그레이션과 같은 스키마).
- `V2__disease_pipeline.sql` [개정, 10/16]: **기존 분석 행을 모두 지운다**(CASCADE로 설문·추천·지표까지 — 회귀 stub 데모 값뿐이고 새 응답 형식으로 보여줄 수 없음, 2026-10-16 이현승 결정). `skin_metrics` 테이블과 `device_type`·`is_demo`·`confidence`·`calibration_applied`·`overall_score`·`skin_model_version` 컬럼 삭제, `measure_ms` → `classify_ms`, 상태 값을 대문자 5종으로, 분류 컬럼·`recommendation_status`·`guidance` 추가. 사용자·토큰은 그대로.
- 스키마를 바꾸는 PR에는 마이그레이션 + SC-04 문서 수정이 함께 들어가야 한다(명세 변경 절차 §18.3).
- NestJS 버전으로 이미 스키마가 만들어진 DB는 Flyway 이력 테이블이 없어 V1이 충돌한다. 개발 DB는 지우고 다시 만들고, 운영 DB가 이미 있으면 `baseline`으로 V1을 적용된 것으로 표시한다.

### 8.4 시드 (`./gradlew seed`)

- `APP_ENV=production`이면 즉시 종료. `SEED_PASSWORD`가 없어도 종료.
- 계정 `test01@skincare.dev` ~ `test40@skincare.dev` (동시 요청 테스트용). 비밀번호는 `SEED_PASSWORD`, 동의 시각 4개 기록.
- `test01`에 **완료(COMPLETED) 분석 25건** — 날짜를 최근 수 주에 분산, 분류 라벨을 돌려 가며(정상 제외 5종, 시험용 분류 JSON), 설문·추천 행 포함(이력 화면 확인용). TC-HIS-01(1쪽 20건, 2쪽 5건)이 이 데이터로 돈다.
- 다시 실행해도 안전하게(시드 계정 삭제 후 재생성) [구현 결정].
- 배포 서버(`APP_ENV=production`)에서는 시드가 거부된다. 성능 테스트 기간에만 일회용 컨테이너로 명시적으로 넣고 끝나면 지운다 [구현 결정]: `docker compose run --rm -e APP_ENV=development backend --seed` (삭제 명령은 `load/README.md`).

### 8.5 보존·삭제

| 데이터 | 보존 | 삭제 |
|---|---|---|
| 얼굴 원본 이미지 | **저장하지 않음** — 학습에도 재사용하지 않음 (개정 2.2) | 분류 응답 직후 메모리 참조 해제 |
| 계정 | 탈퇴 시까지 | 탈퇴 즉시 (CASCADE로 하위 전부) |
| 분석(분류)·설문·추천 | 사용자 삭제·탈퇴 시까지 | 즉시 |
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
| 확장 | Android를 만들면 `X-Client: android`로 같은 방식을 쓴다. 허용 값은 코드 상수 한 곳(`auth/ClientType`)에만 두고, 추가할 때는 이 표를 먼저 고친다 |

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
3. 사용자가 없어도 더미 해시와 bcrypt 비교를 실행해 응답 시간을 맞춘다 [구현 결정].
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

`DELETE FROM users WHERE id = $1` → CASCADE로 분석·설문·추천·리프레시 토큰 전부 즉시 삭제. 쿠키 삭제. **204**.
진행 중 추천 작업 "취소": 워커가 처리 중이면 결과 저장 시점에 행이 없으므로 버린다(§13.7). 테스트: TC-AUTH-07(분석 3건 계정 탈퇴 → 사용자·토큰·분석·설문·추천 5개 테이블 모두 0건).

---

## 10. 분석 API (analyses) [개정 B.1]

`POST /analyses`·재요청·`GET /analyses/{id}`는 **같은 형태**(`AnalysisView`)를 돌려준다. 계약의 기준은 개정 수행계획서 부록 B(앱·백엔드 공동 계약)이고, 그 7개 필드(`analysisId`·`status`·`disease`·`recommendationStatus`·`recommendation`·`guidance`·`error`)에 기존 응답에 있던 `queuePosition`·`estimatedWaitSec`·`createdAt`·`completedAt`·`survey`를 그대로 더했다 [구현 결정 — 대기 안내·이력·설문 기본값용].

### 10.1 POST /analyses — 인증 필요 (FR-ANA-01·02·05, FR-SUR-01)

요청 `multipart/form-data`

| 필드 | 타입 | 필수 | 규칙 |
|---|---|---|---|
| image | file | O | JPEG/PNG, 10MB 이하, 짧은 변 720px 이상 |
| survey | string(JSON) | O | 아래 구조 |

`deviceType`은 받지 않는다(개정 B.1은 `image`·`survey`만 보낸다. 보내도 무시) [개정].

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
| concerns | - | 고민 코드 배열(부록 A.4), 최대 3개, 중복 불가. 정상 분류일 때 추천 대상이 된다 — 없으면 정상 분류는 `no_concern` 보류 [개정 1.3] |
| currentProducts | - | 문자열 배열, 최대 5개, 각 1–30자 |
| note | - | 300자 이하 |

앱(Swift)이 지켜야 할 업로드 규칙 — 정도희에게 전달 [구현 결정] (앱 연동 규칙 전체는 [ios-integration.md](ios-integration.md)):

- iPhone 카메라·앨범 사진은 HEIC인 경우가 많다. **JPEG로 바꿔서** 보낸다(아니면 415 E2004). HEIC 원본은 그대로 보내지 않는다 [개정 B.1].
- **회전을 픽셀에 반영해서** 보낸다(EXIF 방향 태그만 두지 않음). 백엔드는 이미지를 디코딩하지 않고, AI 분류기도 EXIF 회전 같은 추가 전처리 없이 256×256으로 줄여 분류한다.
- 긴 변 1600px로 줄이고 JPEG 품질 0.9 (SC-02 §5와 같은 기준). 짧은 변 720px 이상은 유지된다.
- 필드 이름 `image`·`survey`, `survey`는 JSON **문자열**.

`survey` 파싱: JSON 파싱 실패 → E4002. 검증 실패 필드는 `survey.` 접두로 detail에 담는다. 저장·응답용으로 **정규화된 객체**(모든 키 포함, 미입력은 `null`, 배열은 `[]`)를 쓴다 [구현 결정].

#### 처리 순서 (SC-03 §4.1 순서를 지킴)

Spring MVC에서 **인터셉터(`preHandle`)는 컨트롤러 인자를 만들기 전에** 실행된다. multipart를 지연 파싱(`resolve-lazily: true`)하므로 인증·제한 검사가 업로드 본문을 읽기 전에 거절할 수 있다. 같은 이유로 `@PostMapping`에 `consumes`를 두지 않는다 — 두면 Content-Type 불일치(415)가 인증(401)보다 먼저 판정된다 [구현 결정].

| 순서 | 단계 | 구현 위치 | 실패 |
|---|---|---|---|
| 1 | 인증 | `AuthInterceptor` (순서 0) | 401 E1001 |
| 2 | 요청 제한: 진행 중 분석(QUEUED·GENERATING) 또는 이 사용자의 처리 중 제출이 있음 / 오늘(KST) 생성한 분석 수 ≥ `ANALYSIS_DAILY_LIMIT` | `AnalysisGuardInterceptor` (순서 1, `@AnalysisGuards`) | 429 E4004 (`in_progress` / `daily_limit`) |
| 3 | 대기열 상한: `SELECT COUNT(*) FROM analyses WHERE status='QUEUED'` ≥ `REC_QUEUE_LIMIT` | 〃 | 503 E3003 |
| 4 | AI 가용성: `aiAvailable=false` | 〃 | 503 E3001 [구현 결정: 위치] |
| 5 | 파일 수신: 메모리에서만, 파일 10MB·요청 12MB 한도 | Servlet multipart (컨트롤러 인자 해석 시) | 크기 초과 413 E2003 |
| 6 | 형식 검증: image 없음 → E4002 / 파일 시그니처가 JPEG(`FF D8 FF`)·PNG(`89 50 4E 47`) 아님 → 415 E2004 / 짧은 변 < 720px → 422 E2002 "사진 해상도가 낮아요. 다시 찍어 주세요." [구현 결정] / survey 검증 → E4002 | `Submission.validate` | |
| 7 | AI 이미지 분류 `POST /infer/disease` (§12.3) | `AiClient.classify` | §12.5 표 |
| 8 | 이미지 버퍼 참조 해제 (`Submission.releaseImage()`) | 서비스 `finally` | |
| 9 | AI 응답 검증(§12.3) | `AiResponseParser.disease` | 500 E4001 |
| 10 | 트랜잭션: `analyses`(status `QUEUED`, `queued_at = now()`, 분류 JSON, `classify_ms`) + `surveys` INSERT | | 500 E4001 |
| 11 | 저장한 행으로 응답(§10.2 형태, 대기 순번·예상 대기 §13.8) → **202** | | |

- **분류가 실패하면 분석 행을 만들지 않는다.** (상태 없음)
- 분류가 불확실하거나(`uncertain`) 정상·고민 없음이어도 일단 `QUEUED`로 접수한다. 보류는 워커가 받은 `/infer/disease-recommend` 응답으로 확정한다 — AI가 GPU 생성 없이 바로 응답한다 [개정 B.1]. 개정안이 허용한 "접수 단계에서 200 WITHHELD"는 쓰지 않는다 [구현 결정: 보류 판단을 백엔드에 두지 않으려고]. 그래서 이 API는 항상 202다.
- 처리 중 제출 잠금 [구현 결정]: 2단계를 통과하면 인스턴스 메모리 집합(`SubmissionLock`)에 사용자 id를 넣고 요청이 끝나면(`afterCompletion`, 3·4단계 실패 포함) 뺀다. 같은 사용자가 분류 사이에 두 번 제출해도 두 번째는 E4004.
- 3단계 대기열 상한은 검사와 INSERT 사이에 경합이 있어 상한을 1–2건 넘을 수 있다. 시연 규모(동시 10명 이하)에서 허용 [구현 결정].
- 비운영 환경에서 `X-Fixture-Scenario` 헤더가 있으면 분류에 그 시나리오를 쓰고, 만든 분석 id와 함께 메모리에 기억해 워커의 추천 호출에도 쓴다(§12.7).

응답 **202 Accepted** — §10.2와 같은 형태 (워커가 이미 집어 갔으면 `status`가 `GENERATING`일 수 있다)

```json
{
  "success": true,
  "data": {
    "analysisId": "01J9Z3K5V8Q2M4N6P8R0S2T4V6",
    "status": "QUEUED",
    "disease": {
      "label": "아토피", "labelEn": "atopic", "confidence": 0.9, "uncertain": false,
      "probabilities": { "건선": 0.02, "아토피": 0.9, "여드름": 0.02, "정상": 0.02, "주사": 0.02, "지루": 0.02 },
      "modelVersion": "densenet121_s2_3ea9506", "inferenceMs": 45
    },
    "recommendationStatus": null,
    "recommendation": null,
    "guidance": [],
    "error": null,
    "queuePosition": 2,
    "estimatedWaitSec": 110,
    "createdAt": "2026-10-16T09:12:33Z",
    "completedAt": null,
    "survey": { "gender": "female", "ageBand": "20s", "skinType": "combination", "sleepHours": 6, "waterIntakeLevel": "low", "sunExposureLevel": "high", "stressLevel": "medium", "concerns": ["pore", "pigmentation"], "currentProducts": ["토너", "수분크림"], "note": "여름에 번들거림이 심해요" }
  }
}
```

테스트: TC-ANA-01(202, 저장), 02(gender 누락 422, detail), 03(fixture `no-face` 422 E2001, 행 없음), 06(진행 중 → 429), 07(11MB 413, GIF 415), 10(AI 토큰 틀림 → 503 E3001), TC-PRI-01, TC-PERF-01·04.

### 10.2 GET /analyses/{id} — 인증 필요 (FR-ANA-03, FR-REP-01·02)

`WHERE id = $1 AND user_id = $2`. 없거나 남의 것이면 동일하게 **404 E4003**.

완료(COMPLETED) 응답

```json
{
  "success": true,
  "data": {
    "analysisId": "01J9Z3K5V8Q2M4N6P8R0S2T4V6",
    "status": "COMPLETED",
    "disease": {
      "label": "아토피", "labelEn": "atopic", "confidence": 0.9, "uncertain": false,
      "probabilities": { "건선": 0.02, "아토피": 0.9, "여드름": 0.02, "정상": 0.02, "주사": 0.02, "지루": 0.02 },
      "modelVersion": "densenet121_s2_3ea9506", "inferenceMs": 45
    },
    "recommendationStatus": "generated",
    "recommendation": {
      "skinType": { "code": "dry", "label": "건성" },
      "concerns": [ { "code": "sensitivity", "label": "민감성(트러블/자극감)" } ],
      "ingredients": [ { "name": "성분명 예시", "purpose": null, "priority": 1 } ],
      "routine": [ { "step": 1, "time": "daily", "action": "관리법 설명 예시" } ],
      "reasoning": ["입력과 관련된 추천 설명 예시"],
      "answer": "사용자에게 보여줄 추천 요약 예시",
      "inputNotes": ["이미지 분류 '아토피'를 스킨케어 고민으로 변환: 민감성(트러블/자극감)"],
      "disclaimer": "본 결과는 참고용 정보이며 의학적 진단이나 치료를 대신하지 않습니다. 피부 이상이 지속되면 피부과 전문의와 상담하세요.",
      "filtered": false,
      "usedFallback": false,
      "modelVersion": "rec_sllm_v2",
      "inferenceMs": 43000
    },
    "guidance": [
      "이미지 모델의 6종 분류 참고 결과이며 의학적 진단이 아닙니다. 분류 점수만으로 다른 질환을 배제할 수 없습니다.",
      "성분 추천은 화장품 수준의 보조 관리 정보입니다. 질환 확인과 치료는 피부과 전문의와 상담하세요."
    ],
    "error": null,
    "queuePosition": null,
    "estimatedWaitSec": null,
    "createdAt": "2026-10-16T09:12:33Z",
    "completedAt": "2026-10-16T09:13:21Z",
    "survey": { "gender": "female", "ageBand": "30s", "skinType": "dry", "sleepHours": null, "waterIntakeLevel": null, "sunExposureLevel": null, "stressLevel": null, "concerns": [], "currentProducts": [], "note": null }
  }
}
```

상태별 값은 §11.1 표를 따른다. 필드 규칙:

- **`disease`는 상태와 관계없이 항상 포함**(접수 시 저장한 분류). `probabilities`는 6종 키를 부록 A.2 순서로. `confidence`·확률은 AI 0–1 원본이다 — 화면에 보이면 백분율로 바꾸고 반드시 **'모델 분류 점수'**라고 표기한다(질환 확률로 표시하지 않음) [개정 A.3]. 변환은 앱이 한다.
- `recommendation`: COMPLETED일 때만 객체, 그 밖에는 `null`. `skinType`·`concerns`는 DB의 코드에 라벨(부록 A.3·A.4의 **AI 라벨**)을 붙인다. 화면 표기(예: sensitivity → "민감함")는 앱이 코드로 매핑.
- `ingredients`는 개수가 정해져 있지 않다(0개 이상, 배열 길이대로 표시). `purpose`는 `null`일 수 있다 — 설명 없는 항목은 성분명만 표시하고 효능을 지어내지 않는다 [개정 A.3]. `routine.time`은 부록 A.5. `reasoning`은 생성된 설명이지 검증된 인과 근거가 아니다. `inputNotes` 기본 `[]`.
- `filtered`(표현 필터가 개입함 — 검증 완료 표시 아님), `usedFallback`(true면 규칙 기반 대체 답변)은 화면 비표시(분석용)지만 응답에 포함. `modelVersion`·`inferenceMs`는 운영 기록용.
- `guidance`: AI가 준 안내 문장(진단 아님·전문의 상담·보류 사유). COMPLETED·WITHHELD에서 채워지고 그 밖에는 `[]`. 앱은 그대로 보여 준다.
- `survey`: 저장한 정규화 설문(클라이언트 코드). 앱이 다음 분석의 기본값으로 쓴다.

테스트: TC-ANA-04(QUEUED → GENERATING → COMPLETED 순서, 완료 시 추천 필드 모두 존재), TC-SEC-01.

### 10.3 POST /analyses/{id}/recommendation/retry — 인증 필요 (FR-ANA-04, S)

- 검사: `AuthInterceptor` → `AnalysisGuardInterceptor`(`@AnalysisGuards(checkAiAvailability = false)`: 요청 제한 → 대기열 상한) (SC-03: 대기열 상한·요청 제한을 4.1과 같이 적용). AI 가용성은 검사하지 않는다 — 큐에서 복구를 기다리면 된다 [구현 결정].
- **저장한 분류를 다시 쓴다** — 이미지가 없으므로 분류를 다시 하지 않는다 [개정 B.2]. 원자적 갱신(작업 ID 하나에 한 번만 큐에 들어간다):

```sql
UPDATE analyses
SET status = 'QUEUED', queued_at = now(), error_code = NULL, completed_at = NULL,
    rec_started_at = NULL, queue_wait_ms = NULL, recommend_ms = NULL,
    recommendation_status = NULL, guidance = '[]'
WHERE id = $1 AND user_id = $2 AND status = 'FAILED'
RETURNING id;
```

  0행이면 다시 조회해서 행이 없거나 남의 것이면 **404 E4003**, 있으면(상태가 FAILED가 아님 — WITHHELD도 재요청 대상이 아님) **409 E4005**.
- `attempt_count`는 누적 유지.
- 비운영 환경에서 `X-Fixture-Scenario` 헤더가 있으면 이 분석의 시나리오를 갱신(TC-ANA-05: rec-timeout으로 실패 → ok로 바꿔 재요청 → COMPLETED).
- 응답 **202** — §10.2와 같은 형태.

### 10.4 GET /analyses — 인증 필요 (FR-HIS-01, S)

`?page=1&size=20`. 정렬 `created_at DESC, id DESC`.

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "analysisId": "01J9…", "createdAt": "2026-10-16T09:12:33Z", "status": "COMPLETED",
        "recommendationStatus": "generated",
        "disease": { "label": "아토피", "labelEn": "atopic", "confidence": 0.9, "uncertain": false, "probabilities": { … }, "modelVersion": "densenet121_s2_3ea9506", "inferenceMs": 45 },
        "concerns": ["sensitivity"]
      }
    ],
    "page": { "page": 1, "size": 20, "totalItems": 3, "totalPages": 1 }
  }
}
```

`disease`는 §10.2와 같은 객체. `concerns`: COMPLETED면 `recommendations.concerns`(코드), 아니면 `[]` [구현 결정]. 홈 화면은 `size=1`로 최근 1건을 쓴다. 테스트: TC-HIS-01(test01: 1쪽 20건, 2쪽 5건, 최신순).

### 10.5 DELETE /analyses/{id} — 인증 필요 (FR-HIS-03, S)

`DELETE FROM analyses WHERE id = $1 AND user_id = $2` → 0행이면 404 E4003. 상태 무관 삭제 허용(진행 중이면 워커가 결과를 버림, §13.7). **204**. 테스트: TC-HIS-03, TC-SEC-01.

> 추이 API(`GET /analyses/{id}/trend`, FR-HIS-02)는 비교할 회귀 지표가 없어져 10/16 삭제했다 [개정 1.2].

---

## 11. 분류·추천 상태 규칙 (`analyses/AnalysisPresenter`) [개정 B.1·A.3]

10/16 이전의 점수 계산(회귀 지표 0–100 변환·등급)은 회귀 지표가 범위에서 빠져 삭제했다.

### 11.1 작업 상태와 추천 상태

`status`는 백엔드 작업 상태, `recommendationStatus`는 AI의 `recommendation_status` 값이다.

| status | 의미 | recommendationStatus | recommendation | guidance | queuePosition / estimatedWaitSec | error | 앱 처리 |
|---|---|---|---|---|---|---|---|
| QUEUED | 접수 후 생성 대기 | `null` | `null` | `[]` | 순번 ≥ 1 / 예상 초 (§13.8) | `null` | 분류 참고 결과와 대기 안내 |
| GENERATING | AI 추천 호출 중 | `null` | `null` | `[]` | `null` / `null` [구현 결정] | `null` | 진행 표시, 중복 접수 방지 |
| COMPLETED | 추천 생성 완료 | `generated` | 객체 | AI 안내 | `null` / `null` | `null` | 추천과 공통 안내 표시 |
| WITHHELD | 추천 보류 | `uncertain` · `no_concern` | `null` | AI 안내(보류 사유 포함) | `null` / `null` | `null` | 추천 없이 사유와 다음 행동 |
| FAILED | 통신·생성·저장 실패 | `null` | `null` | `[]` | `null` / `null` | `{ code, message }` (E3001·E3002·E3003·E4001, message는 §7.3) | 오류 안내와 재요청(§10.3) |

- **보류는 오류가 아니다.** 보류를 FAILED로 바꾸거나 빈 추천 객체로 대체하지 않는다 [개정 B.1].
- 앱은 COMPLETED·WITHHELD·FAILED에서 폴링을 끝내고, 앱을 다시 열면 같은 id로 결과를 다시 확인한다 [개정 B.1].

| recommendationStatus | 뜻 | 앱에서 보여줄 내용 [개정 A.3] |
|---|---|---|
| generated | 추천 생성 | 성분·관리 단계·요약과 안내문 |
| uncertain | 분류가 불확실해 자동 추천 보류 | 판단 보류와 재촬영·상담 안내 |
| no_concern | 정상 분류이고 선택한 지원 고민이 없어 추천 생략 | 자동 추천할 고민 없음, 설문에서 고민 선택 안내 |

### 11.2 보류 판단은 AI가 한다

- `uncertain`: 분류 점수가 AI 서버 임계값(`DISEASE_THRESHOLD`, 현재 0.6) 미만이거나 `uncertain=true`. 0.6은 현재 분류기의 운영 임계값이지 검증된 입력 안전성 기준이 아니다 [개정 3.1].
- `no_concern`: 정상 분류이고 사용자가 고른 지원 고민이 없음.
- 백엔드는 분류→고민 매핑(부록 A.2의 참고 열)과 위 판단을 코드에 두지 않는다. `recommendationStatus`는 AI 응답 값을 그대로 저장한다 [개정 B.1].

### 11.3 비진단 표현

- 분류 결과는 참고 정보다. 백엔드가 만드는 문구(에러 message 등)는 §7.3 문구 규칙을 지킨다.
- 분류 라벨·`guidance`·추천 문장은 AI 값을 그대로 전달한다. 화면에서 확정 진단·치료 처방처럼 보이지 않게 하는 것은 앱 몫이다(`'모델 분류 점수'` 표기, 공통 안내 표시).

---

## 12. AI 서버 연동 (aiclient) [개정 부록 A]

### 12.1 인터페이스

```java
public interface AiClient {
  AiHealth health();                                                          // GET /health
  Object modelInfo();                                                         // GET /model-info (가용해질 때 로그)
  DiseaseResult classify(ClassifyInput input, AiCallContext ctx);             // POST /infer/disease
  DiseaseRecommendResult recommend(DiseaseRecommendRequest request, AiCallContext ctx); // POST /infer/disease-recommend
}
```

- `AI_MODE=real` → `HttpAiClient`, `fixture` → `FixtureAiClient`. 빈 하나로 교체.
- 클라이언트는 AI 오류를 `AiError { kind: http | connection | timeout | invalid_response; status?; code?; message? }`로 던진다. **HTTP 코드 → 백엔드 코드 변환은 호출하는 쪽(분류 경로·워커)이 §12.5 표로** 한다.
- `/infer/disease-analyze`(분류 + 추천 한 번에)는 통합 시험·시연용이라 쓰지 않는다 [개정 A.4]. 개정 전 경로 `/infer/skin-metrics`·`/infer/recommend`·`/infer/analyze`도 쓰지 않는다.

### 12.2 HTTP 규약

| 항목 | 값 |
|---|---|
| Base URL | `AI_BASE_URL` |
| 인증 | `X-Internal-Token: <AI_INTERNAL_TOKEN>` (`/health` 제외). 앱에는 넣지 않는다 [개정 3.1] |
| 추적 | `X-Request-Id` 전달 |
| 필드 | AI 요청·응답은 snake_case → 백엔드가 camelCase로 변환해 저장·응답 |
| 성공 본문 | 봉투 없이 그대로 |
| 실패 본문 | `{ "success": false, "error": { "code", "message", "detail" } }` |
| 실제 스키마 | `skincare-ai/server/schemas.py`(`DiseasePrediction`·`DiseaseRecommendRequest`·`DiseaseAnalyzeResponse`), AI 서버 실행 후 `http://localhost:8000/docs` |

| 메서드 | 경로 | 백엔드 사용 | 타임아웃 | 재시도 |
|---|---|---|---|---|
| GET | /health | 30초마다 | 5초 | — |
| GET | /model-info | 가용해질 때 1회 로그 | 5초 | — |
| POST | /infer/disease | POST /analyses 안에서 동기 | 20초 (`AI_CLASSIFY_TIMEOUT_MS`) [개정 B.2] | 연결 오류만 1회, 즉시 [구현 결정: 지연 없음] |
| POST | /infer/disease-recommend | 워커 | 130초 (`AI_REC_TIMEOUT_MS`) [개정 B.2] | 연결 오류·503만 1회, 5초 후. **504·422 재시도 금지** |

20초 제한을 추천 호출에 쓰면 현재 모델의 정상 응답도 받지 못한다 [개정 B.2].

**오류 분류 [구현 결정]** — 중요:

| 상황 | kind |
|---|---|
| 요청이 응답 전에 실패 (연결 거부, DNS, 연결 리셋, TLS 오류 등) | `connection` |
| 상태 ≥ 500이고 본문이 위 실패 JSON(`error.code` 있음)이 **아님** — PC가 꺼졌을 때 Cloudflare가 주는 502/521/522/523/530 HTML 등 (524 제외, 아래) | `connection` |
| 우리 읽기 타임아웃 초과 | `timeout` |
| 상태 524(Cloudflare: AI가 연결은 받았지만 125초 안에 응답 안 함) — AI가 생성을 계속하므로 504처럼 재시도 금지 [구현 결정, 10/12] | `timeout` |
| 상태 ≥ 400이고 본문에 `error.code` 있음 | `http` (status, code) |
| 2xx인데 JSON 파싱 실패 또는 스키마 위반 | `invalid_response` |

Cloudflare HTML 503을 AI의 503 E3003으로 읽으면 "요청이 많아요"가 잘못 뜬다. 반드시 본문의 `error.code`로 판단한다.

### 12.3 POST /infer/disease [개정 A.1]

요청 `multipart/form-data`: `image`(file 1개, JPEG/PNG, 최대 10MiB, 시그니처로 판별한 Content-Type, 파일명 `image.jpg`/`image.png`). 그 밖의 필드는 없다.

응답 200

```json
{
  "label": "아토피",
  "label_en": "atopic",
  "confidence": 0.9,
  "uncertain": false,
  "probabilities": { "건선": 0.02, "아토피": 0.9, "여드름": 0.02, "정상": 0.02, "주사": 0.02, "지루": 0.02 },
  "model_version": "densenet121_s2_3ea9506",
  "inference_ms": 45
}
```

- AI: DenseNet121(기본 CPU)이 RGB 변환·256×256·ImageNet 정규화 후 6종 softmax. 최고 점수 라벨과 6개 점수를 준다. **점수는 임상적으로 보정된 질환 확률이 아니다.** `uncertain = confidence < DISEASE_THRESHOLD(0.6)`.
- 얼굴 검출·입력 품질 확인은 아직 없다. 비얼굴 입력도 높은 점수로 분류될 수 있다(10/15 점검: 비얼굴 대조군 6건 중 4건 수락) [개정 5.1]. 백엔드는 이를 보정하지 않는다.
- 응답 검증 (`AiResponseParser.disease`) — 위반하면 500 E4001 + ERROR:
  - `label`·`label_en`이 부록 A.2 표에 있고 서로 짝이 맞음
  - `confidence`와 `probabilities`의 값이 0–1 유한값, `probabilities`에 6종 라벨 키가 모두 있고 그 밖의 키는 없음
  - `uncertain` boolean, `model_version` 문자열 ≤ 80자, `inference_ms` ≥ 0
  - 확률 합·최고 점수 일치는 AI가 추천 요청 때 검증하므로 다시 보지 않는다 [구현 결정]
- 값은 반올림하지 않고 그대로 저장한다(§8.1).
- AI 오류: 401 E1001, 413 E2003, 415 E2004, 422 E2002(디코딩 실패)·E4002, 503 E3001(분류기 비활성)·E3003(대기열 초과), 504 E3002. 얼굴 미검출 E2001은 AI에 아직 없다 [개정 B.3].

### 12.4 POST /infer/disease-recommend [개정 A.2·A.3]

요청 (워커가 만든다)

```json
{
  "disease": {
    "label": "아토피", "label_en": "atopic", "confidence": 0.9, "uncertain": false,
    "probabilities": { "건선": 0.02, "아토피": 0.9, "여드름": 0.02, "정상": 0.02, "주사": 0.02, "지루": 0.02 },
    "model_version": "densenet121_s2_3ea9506", "inference_ms": 45
  },
  "survey": {
    "gender": "female",
    "ageBand": "20s",
    "skinType": "combination",
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

변환 규칙

| 항목 | AI 요청 | 변환 |
|---|---|---|
| 분류 | `disease` | **저장한 분류 JSON 전체** (라벨만 넘기지 않고 6개 점수도) — 재시도 때도 같은 값 [개정 A.2·B.2] |
| `skinType: "combination"` | `skinType: "combination"` | 코드 그대로 (AI가 `dry`·`건성` 모두 인식하고 개정 A.2가 코드를 쓴다) |
| `concerns: ["pore"]` | `selfReportedConcerns: ["모공"]` | 코드 → 라벨 (부록 A.4). **코드를 그대로 보내면 인식되지 않음.** 키 이름도 바뀜. 고민이 없어도 `[]`로 보낸다 [개정 A.2] |
| `ageBand` | `ageBand` | 그대로. AI가 연령대 중간값으로 바꾸고 `input_notes`에 남긴다 [개정 A.2] |
| (측정값) | `metrics` | **보내지 않는다** — 임시 측정값 금지 [개정 A.2] |
| 나머지 | 같은 이름 | 그대로. 미입력 선택 필드는 키를 생략 [구현 결정] |

응답 200 — 세 가지 (`docs/fixtures/disease-pipeline/*.response.json` 실제 기록)

```json
// generated: Qwen2.5가 생성
{
  "disease": { …요청을 되돌려 준 분류… },
  "recommendation_status": "generated",
  "recommendation": {
    "skin_type": "건성",
    "concerns": ["민감성(트러블/자극감)"],
    "ingredients": [{ "name": "쑥잎 추출물", "purpose": "…", "priority": 1 }, { "name": "대나무추출물", "purpose": null, "priority": 2 }],
    "routine": [{ "step": 1, "time": "anytime", "action": "…" }],
    "reasoning": ["…", "…", "…"],
    "answer": "…",
    "disclaimer": "본 결과는 참고용 정보이며 …",
    "filtered": true,
    "used_fallback": false,
    "input_notes": ["이미지 분류 '아토피'를 스킨케어 고민으로 변환: 민감성(트러블/자극감)"],
    "model_version": "rec_sllm_v2",
    "inference_ms": 47038
  },
  "guidance": ["이미지 모델의 6종 분류 참고 결과이며 의학적 진단이 아닙니다. …", "성분 추천은 화장품 수준의 보조 관리 정보입니다. …"]
}
// uncertain · no_concern: GPU 생성 없음
{ "disease": { … }, "recommendation_status": "uncertain", "recommendation": null,
  "guidance": ["…", "분류가 불확실해 자동 성분 추천을 보류했습니다. 재촬영 또는 피부과 전문의 상담을 권합니다."] }
```

응답 검증 (`AiResponseParser.diseaseRecommend`)

| 검사 | 위반 시 |
|---|---|
| `recommendation_status`가 `generated`·`uncertain`·`no_concern` 밖 | **FAILED E4001 + ERROR 로그** |
| `generated`인데 `recommendation`이 객체가 아님 / 보류인데 `recommendation`이 `null`이 아님 | FAILED E4001 + ERROR |
| `guidance`가 문자열 배열이 아님 | FAILED E4001 + ERROR |
| `recommendation.skin_type`이 부록 A.3 라벨(또는 영문 코드)에 없음 | FAILED E4001 + ERROR (SC-03) |
| `recommendation.concerns` 원소가 부록 A.4 라벨에 없음 | FAILED E4001 + ERROR |
| `recommendation.routine[].time`이 부록 A.5 밖 | FAILED E4001 + ERROR [구현 결정: 열거형은 엄격] |
| 타입 위반(필수 필드 없음, 문자열·숫자·불리언 아님) | FAILED E4001 + ERROR |
| 개수: ingredients > 5, concerns > 2, reasoning ≠ 3 | WARN 로그만, 그대로 저장 [구현 결정: 시연 안정성] |
| `ingredients[].purpose` null, `ingredients`·`routine` 빈 배열 | 정상 [개정 A.3] |

- 응답의 `disease`(요청을 되돌려 준 것)는 쓰지 않는다 — 저장한 분류가 기준이다 [구현 결정].
- 라벨 → 코드로 바꿔 `recommendations.skin_type`·`concerns`에 **코드**로 저장. 중복 고민은 하나로.
- Qwen은 사진을 직접 보지 않는다. 분류 JSON과 설문에서 만든 텍스트(분류 라벨·점수·비진단 문맥·관리 제약)를 받는다 [개정 3.1].

### 12.5 AI 오류 → 백엔드 코드 (SC-02 §4.3, 개정 B.3)

| AI 결과 | 분류 (POST /analyses 안) | 추천 (워커) |
|---|---|---|
| 401 E1001 (내부 토큰 불일치) | **503 E3001** + ERROR 로그 (401을 그대로 주면 앱이 로그아웃시킴) | FAILED E3001 + ERROR |
| 413 E2003 / 415 E2004 | 그대로 | — |
| 422 E2001 / E2002 | 그대로 (재촬영 안내). message는 AI 문구가 있으면 그것, 없으면 §7.3 [구현 결정] | — |
| 422 E4002 | 500 E4001 + ERROR (백엔드 변환 버그) | FAILED E4001, 재시도 없음 |
| 503 E3001 (분류기 비활성) | 그대로 | 5초 후 1회 재시도 → 실패 시 FAILED E3001 |
| 503 E3003 | 그대로 | 5초 후 1회 재시도 → 실패 시 FAILED E3003 |
| 504 E3002 | 그대로 | **재시도 없이** FAILED E3002 |
| `connection` | 즉시 1회 재시도 → 503 E3001 | 5초 후 1회 재시도 → FAILED E3001 |
| `timeout` (우리 쪽·524) | 503 E3001 | **재시도 없이** FAILED E3002 [구현 결정] |
| `invalid_response` | 500 E4001 + ERROR | FAILED E4001 + ERROR |
| 그 밖의 4xx/5xx | 500 E4001 + ERROR | FAILED E4001 + ERROR |

504를 재시도하지 않는 이유: AI 서버는 타임아웃이 나도 진행 중인 GPU 생성을 끝까지 돌린다. 곧바로 같은 요청을 다시 보내면 중복 생성이 그 뒤에 줄을 서다 다시 타임아웃이 나고 다음 작업까지 밀린다. 우리 쪽 130초 타임아웃도 같은 상황이므로 재시도하지 않는다 [개정 B.2].

### 12.6 GET /health

```json
{ "status": "ok", "rec_loaded": true, "skin_loaded": true, "rec_backend": "sllm", "skin_backend": "stub", "disease_loaded": true, "disease_backend": "densenet" }
```

`status`가 `"ok"`이고 **`disease_loaded`가 `true`**일 때만 `aiAvailable=true`. 5초 안에 응답이 없거나 둘 중 하나라도 아니면 false. 분류기가 꺼져 있으면(`DISEASE_BACKEND=disabled`) `/infer/disease`가 503 E3001이므로 접수를 미리 막는다. `disease_loaded`가 없는 응답(분류 API가 없는 이전 AI 서버)도 false [구현 결정].

### 12.7 Fixture 모드 (`AI_MODE=fixture`, SC-03 §7)

백엔드·앱 개발은 AI 서버가 꺼져 있어도 이 모드로 한다. `src/main/resources/fixtures/ai/disease-pipeline/`의 JSON을 돌려준다.

| 시나리오 | 분류 | 추천 | health |
|---|---|---|---|
| `ok` (기본) | `generated.response.json`의 `disease` (아토피 0.9) | `generated.response.json`, **1초 지연** | ok |
| `uncertain` | `uncertain.response.json`의 `disease` (아토피 0.55, `uncertain: true`) | `uncertain.response.json` (보류, 지연 없음) | ok |
| `no-concern` | `no_concern.response.json`의 `disease` (정상 0.9) | `no_concern.response.json` (보류, 지연 없음) | ok |
| `no-face` | 422 E2001 (AI에는 아직 없음 — 오류 경로 확인용) | — | ok |
| `rec-timeout` | 정상 | 504 E3002 즉시 | ok |
| `rec-slow` | 정상 | **60초 지연** 후 generated (대기열 테스트) | ok |
| `ai-down` | `connection` 오류 | `connection` 오류 | 실패 → `aiAvailable=false` |

- 시나리오는 `AI_FIXTURE_SCENARIO`. **`APP_ENV`가 production이 아닐 때만** 요청 헤더 `X-Fixture-Scenario`로 요청마다 바꿀 수 있다.
- 워커는 요청 헤더를 볼 수 없다 → POST /analyses·retry에서 받은 헤더 시나리오를 `Map<analysisId, scenario>`(인스턴스 메모리)에 넣고 워커가 꺼내 쓴다. 없으면 env 값. 재시작하면 사라져도 된다 [구현 결정].
- health는 env 시나리오만 따른다.
- 지연은 `Sleeper`로 구현하고 테스트에서 가짜(`FakeSleeper`)로 넘긴다.
- fixture 파일은 skincare-ai `docs/fixtures/disease-pipeline/`(커밋 `d09e597`)의 응답 기록을 그대로 복사했다. 분류 JSON은 시험용 입력이라 `model_version`이 `quality_test_fixture_not_a_patient_prediction`이고, generated 응답은 실제 Qwen2.5 v2 생성 기록(purpose 누락 포함)이다. 실제 환자 판정이 아니다. 원본이 바뀌면 그대로 복사해 온다.
- fixture 응답도 HttpAiClient와 **같은 검증·변환 코드**를 통과시킨다(계약 테스트 겸용).

---

## 13. 추천 작업 큐 (recommendation) [개정 B.2]

### 13.1 상태 전이

```
(분류 성공) ──▶ QUEUED ──워커 획득──▶ GENERATING ──generated──────────▶ COMPLETED
                 │  ▲                     ├──uncertain·no_concern──▶ WITHHELD
   60분 초과 ─────┘  │                     └──재시도 후에도 실패──────▶ FAILED
   (FAILED E3001)    └────────── 사용자 재요청 ─────────────────────────┘
(분류 실패: 행 없음, 상태 없음)
(백엔드 재시작: GENERATING ──▶ QUEUED, queued_at 유지)
```

### 13.2 워커 동작

| 항목 | 설계 |
|---|---|
| 폴링 | 1초마다. 처리 중이면 건너뜀(동시성 1) |
| 동시성 | 1. GPU 생성이 직렬이기 때문 [개정 B.2: 생성 동시 1건, 대기 최대 10건] |
| AI 비가용 | `aiAvailable=false`면 획득 쿼리를 실행하지 않음(대기열 유지). 복구되면 이어서 |
| 기동 | `ApplicationReadyEvent`(`BackgroundJobs`): ① 복구 쿼리 ② 첫 헬스체크 ③ 폴링 시작. `APP_ENV=test`에선 자동 시작하지 않고 테스트가 `tick()`을 직접 호출 [구현 결정] |
| 종료 | `enableShutdownHooks`. SIGTERM이면 폴링만 멈추고 진행 중 작업은 기다리지 않음 — 재시작 복구가 다시 큐에 넣는다 [구현 결정] |
| 취소 | 앱 종료·화면 이탈은 서버 작업 취소가 아니다 [개정 B.2]. 취소는 이력 삭제·탈퇴뿐(§13.7) |

```
tick():
  if (busy || !aiHealth.available) return
  job = acquire()                       // §13.3 획득 SQL
  if (!job) return
  busy = true
  try { process(job) } finally { busy = false }

process(analysisId):
  analysis(분류 포함), survey 로드 (없으면 삭제된 것 → INFO 로그 후 종료)
  req = { disease: 저장한 분류 JSON, survey: AI 설문 }   // §12.4
  started = now
  result = callWithPolicy(req)          // §12.5 워커 열: 503·connection만 5초 후 1회, 재시도 시 attempt_count + 1
  recommend_ms = 마지막 호출의 왕복 시간 [구현 결정]
  성공:
    generated →
      tx:
        UPDATE analyses SET status='COMPLETED', recommendation_status='generated', guidance=$g,
               completed_at=now(), recommend_ms=$ms
          WHERE id=$1 AND status='GENERATING'
        0행 → 롤백, 결과 버림 (삭제·탈퇴됨) — INFO 로그
        INSERT INTO recommendations … ON CONFLICT (analysis_id) DO UPDATE …
    uncertain · no_concern →
        UPDATE analyses SET status='WITHHELD', recommendation_status=$s, guidance=$g,
               completed_at=now(), recommend_ms=$ms
          WHERE id=$1 AND status='GENERATING'
  실패(code):
    UPDATE analyses SET status='FAILED', error_code=$code, completed_at=now(), recommend_ms=$ms
      WHERE id=$1 AND status='GENERATING'
```

### 13.3 SQL (SC-04 §4)

```sql
-- 작업 획득 (1초마다, aiAvailable=true일 때만)
UPDATE analyses SET status = 'GENERATING', rec_started_at = now(),
       queue_wait_ms = (EXTRACT(EPOCH FROM now() - queued_at) * 1000)::int,
       attempt_count = attempt_count + 1
WHERE id = (SELECT id FROM analyses WHERE status = 'QUEUED'
            ORDER BY queued_at LIMIT 1 FOR UPDATE SKIP LOCKED)
RETURNING id;

-- 대기 순번
SELECT COUNT(*) + 1 FROM analyses
WHERE status = 'QUEUED' AND queued_at < (SELECT queued_at FROM analyses WHERE id = $1);

-- 최근 생성 완료 20건 평균 추천 시간 (없으면 55000ms). 보류는 GPU 생성이 없어 평균에서 뺀다
SELECT COALESCE(AVG(recommend_ms), 55000) FROM
  (SELECT recommend_ms FROM analyses WHERE status = 'COMPLETED' AND recommend_ms IS NOT NULL
   ORDER BY completed_at DESC LIMIT 20) t;

-- 서버 기동 시 복구
UPDATE analyses SET status = 'QUEUED' WHERE status = 'GENERATING';

-- 장기 대기 처리 (1분마다)
UPDATE analyses SET status = 'FAILED', error_code = 'E3001', completed_at = now()
WHERE status = 'QUEUED' AND queued_at < now() - make_interval(mins => $1);  -- $1 = REC_QUEUE_MAX_WAIT_MIN

-- 대기열 상한 확인 (분석 생성 전)
SELECT COUNT(*) FROM analyses WHERE status = 'QUEUED';  -- ≥ REC_QUEUE_LIMIT 이면 E3003
```

### 13.4 시간 기록 (NFR-OBS-01, 개정 2.1)

`classify_ms`(분류 왕복 — 분류 지연 목표 p95 3초의 근거), `queue_wait_ms`(획득 시 계산), `recommend_ms`(추천 왕복 — 큐 대기 제외 생성 시간). 첫 요청·재시도·큐 대기 시간을 따로 기록하라는 개정 2.1을 따르며, 최종 성능 분석이 이 값으로 계산되므로 성공·보류·실패 모두 기록한다. 분류·추천의 모델 버전도 분석별로 저장한다(`disease_model_version`, `recommendations.model_version`) [개정 4.3].

### 13.5 장기 대기

`QUEUED`가 `REC_QUEUE_MAX_WAIT_MIN`(60)분을 넘으면 FAILED E3001 — AI 서버가 오래 꺼져 있던 경우. 사용자는 재요청할 수 있다. 큐 크기와 만료 시간은 부하 시험 후 함께 조정한다 [개정 B.2].

### 13.6 재시작 복구

기동 시 `GENERATING`을 모두 `QUEUED`로 되돌린다(`queued_at` 유지로 순서 보존). AI 서버가 이전 요청을 아직 생성 중일 수 있지만 AI 서버 큐에 순서대로 쌓이므로 허용.

### 13.7 취소 (탈퇴·이력 삭제)

진행 중 HTTP 호출은 끊지 않는다(끊어도 AI는 GPU 생성을 계속함). 결과 저장 시 `WHERE status='GENERATING'` 갱신이 0행이면 버린다. FK 위반으로 500이 나지 않게 이 순서를 지킨다.

### 13.8 대기 순번 · 예상 대기

| 값 | 계산 |
|---|---|
| `queuePosition` (QUEUED) | 내 `queued_at`보다 앞선 QUEUED 건수 + 1 |
| `estimatedWaitSec` (QUEUED) | `Math.round(queuePosition × 평균추천ms / 1000)` (평균은 §13.3, 기본 55초) |
| 그 밖의 상태 | `null` / `null` |
| `/system/status` `estimatedWaitSec` | `Math.round(queueLength × 평균추천ms / 1000)` |

예: 순번 2 → 110초, queueLength 3 → 165초.

---

## 14. 시스템 상태 (system) — FR-SYS-01, NFR-AVL-01

### 14.1 AiHealthService

- 기동 시 즉시 1회, 이후 `AI_HEALTH_INTERVAL_MS`(30초)마다 `GET /health`(5초). 가용 조건은 §12.6.
- 상태 `{ aiAvailable: boolean, checkedAt: Date }`를 메모리에 둔다. 첫 검사 전에는 `false`.
- 바뀔 때만 로그: 끊김 WARN(사유: 연결 오류·`status`·`disease_not_loaded`), 복구 INFO + 모델 정보(`/model-info`, 분류·추천 모델 버전).
- 분류 호출에서 `connection` 오류가 나면 즉시 `aiAvailable=false`로 두고 다음 검사를 앞당긴다 [구현 결정].
- fixture 모드: `ai-down`이면 false, 그 외 true.

### 14.2 GET /system/status — 인증 없음

```json
{ "success": true, "data": { "aiAvailable": true, "queueLength": 3, "estimatedWaitSec": 165, "checkedAt": "2026-10-15T09:12:00Z" } }
```

- `queueLength` = QUEUED 건수.
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
| NFR-SEC-03 | AI 직접 호출 | AI 토큰은 백엔드 env에만(앱에 포함하지 않음, 개정 3.1). AI 401은 클라이언트에 503 E3001 | TC-ANA-10 |
| NFR-PRI-01 | 이미지 잔존 | 메모리 multipart만, 임시 파일·파일 쓰기 금지, 분류 후 참조 해제, 예외·로그에 버퍼 금지. 학습에 재사용하지 않음(개정 2.2) | TC-PRI-01 |
| NFR-PRI-02 | 로그 유출 | §7.6 | TC-PRI-02 |
| NFR-PRI-03 | 만 14세·동의 | 4개 동의 필수, 시각 기록 | TC-AUTH-01·02 |
| NFR-SEC-05 | 비밀값 | `.env` gitignore, `.env.example` 가짜 값, GitHub secret scanning | TC-SEC-03 (gitleaks) |
| NFR-DAT-01 | 학습 데이터 | AI허브 데이터·가공물·가중치·어댑터·실제 얼굴 사진을 레포에 넣지 않음(개정 3.3). 테스트 이미지는 합성 | TC-DAT-01 |
| NFR-SEC-01 | HTTPS | Cloudflare Tunnel이 처리. 백엔드는 HTTP :3000, 외부 비공개 | — |

### 15.2 클라이언트 IP

nginx·터널 뒤라 모든 요청의 출발 IP가 같다. **`CF-Connecting-IP` 헤더 → (`TRUST_PROXY=true`면) `X-Forwarded-For` 첫 값 → 연결 주소** 순으로 판별(`common/web/ClientIp`). 백엔드는 터널·nginx로만 들어오므로 헤더 위조 경로가 없다. 로컬에선 헤더가 없어 연결 주소.

### 15.3 기타 [구현 결정]

- CORS: `CORS_ORIGIN` 하나만, `credentials: true`. iOS 앱은 CORS와 무관하다.
- 기본 보안 헤더(`X-Content-Type-Options: nosniff` 등)는 `SecurityHeadersFilter`가 붙인다.
- Swagger는 운영에서도 열어 둔다(레포가 Public이라 숨길 정보 없음, 앱 타입 생성용). 팀이 원하면 production에서 끔.

---

## 16. 테스트

### 16.1 레벨

| 레벨 | 대상 | 도구 | 실행 |
|---|---|---|---|
| 단위 | `AiTransform`, `AiErrorMapper`, `AiResponseParser`, `AnalysisPresenter`, `SurveyParser`, `ImageInspector`, 토큰·쿠키·시간 유틸, env 검증, `HttpAiClient` 오류 분류(로컬 가짜 HTTP 서버) | JUnit + AssertJ (`*Test`) | `./gradlew test`, CI |
| 계약 | fixture JSON(skincare-ai 기록)이 AI 응답 검증·변환을 통과하는지, 변환한 요청이 §12.4 모양인지 | JUnit + `src/main/resources/fixtures/ai` | CI, AI 응답이 바뀔 때 |
| API 통합 | 백엔드 + DB, 엔드포인트별 (실제 HTTP로 Tomcat·필터·인터셉터 순서까지) | `@SpringBootTest` + PostgreSQL 16 (`*IT`, `@Tag("integration")`) | `./gradlew integrationTest`, CI 서비스 컨테이너 |
| 성능 | 응답 시간, 동시 요청 | k6 (`load/`) | 스테이징, 11/10–11/24 |
| 실제 AI 계약 [구현 결정] | 백엔드 요청을 실제 skincare-ai가 인식하는지(분류 JSON 왕복, 고민 8종·피부 타입 4종, 보류 3상태), 응답이 검증을 통과하는지 | `AiContractIT` (`@Tag("ai-contract")`, `AI_CONTRACT_URL`이 있을 때만) | `./gradlew aiContractTest`. AI 서버가 바뀔 때 |
| 스모크 [구현 결정] | 실행 중인 서버의 전체 흐름(상태 → 로그인 → 제출 → 추천 완료·보류 → 이력 → refresh) | `./gradlew smoke` (`SMOKE_BASE_URL`) | 배포 직후, AI 연동 확인 |

통합 테스트 기반 [구현 결정]:

- `AI_MODE=fixture`, `APP_ENV=test`, 실제 PostgreSQL. DB는 `DATABASE_URL`(CI) 또는 `.env`의 DB 이름 + `_test`(로컬)이고 이름에 test가 없으면 실행을 거부한다. 스키마는 Flyway가 만들고, 테스트마다 테이블 TRUNCATE(`users … CASCADE`).
- 워커·헬스체크는 자동 시작하지 않고 `RecommendationWorker.tick()`을 직접 호출. 지연은 `Sleeper`를 `FakeSleeper`로 바꿔 기다리지 않는다(rec-slow는 `holdSlow()`로 붙잡아 처리 중 상태를 본다).
- 실패·지연·보류는 `X-Fixture-Scenario` 헤더로 재현. `ai-down`·`AI_MODE=real`처럼 설정이 다른 테스트는 `@TestPropertySource`로 따로 띄운다.
- 테스트 이미지는 코드로 생성(`TestImages`: 1080×1440 PNG, 600×600 PNG, 헤더만 있는 JPEG, GIF·HEIC 헤더, 11MB JPEG). 실제 얼굴 사진 금지.
- 로그 검사는 `OutputCaptureExtension`으로 콘솔 JSON을 캡처해서 한다.
- 테스트 이름에 TC ID를 넣는다: `@DisplayName("TC-ANA-06: 진행 중 분석이 있으면 429 E4004 in_progress (Retry-After 30)")`. SC-08에 없는 개정 시험은 `(개정)`을 붙인다.

### 16.2 백엔드가 자동화하는 테스트 케이스 (SC-08, 개정 5.2)

| TC | 내용 | 방법 |
|---|---|---|
| TC-AUTH-01~03 | 가입 성공(동의 시각 4개) / 동의 누락 422 E4002 / 대소문자 다른 이메일 409 E1004 | 통합 |
| TC-AUTH-04 | 로그인 200, 액세스 토큰, httpOnly 쿠키(속성 전부 확인) | 통합 |
| TC-AUTH-05 | 만료 토큰 → 401 E1001, refresh 후 원 요청 성공 (앱 동작의 백엔드 쪽) | 통합 (만료된 토큰 서명) |
| TC-AUTH-06 | 로그아웃 후 이전 리프레시로 refresh → 401 E1001 | 통합 |
| TC-AUTH-07 | 분석 3건 계정 탈퇴 → 사용자·토큰·분석·설문·추천 5개 테이블 0건 | 통합 |
| (추가) | 교체된 리프레시 토큰 재사용 → 401 + 그 사용자 토큰 전부 폐기 | 통합 |
| (추가) | `X-Client: ios` 로그인·refresh → `data.refreshToken` 있음(헤더 없으면 없음), 쿠키 없이 본문 토큰으로 refresh·logout, 본문으로 교체된 토큰 재사용 → 401 + 전부 폐기, 쿠키·본문 둘 다 있으면 쿠키 우선 | 통합 |
| TC-ANA-01 | 202, `QUEUED`, `disease` 객체, queuePosition ≥ 1, analyses(분류)·surveys 저장 | 통합 |
| TC-ANA-02 | gender 누락 → 422 E4002, detail에 `survey.gender` | 통합 |
| TC-ANA-03 | fixture `no-face` → 422 E2001, analyses 행 없음 (AI는 아직 E2001을 주지 않음 — 오류 경로 확인) | 통합 |
| TC-ANA-04 | QUEUED → GENERATING → COMPLETED, 완료 시 추천 필드 모두 | 통합 + `tick()` |
| TC-ANA-05 | `rec-timeout` → FAILED E3002(attempt_count 1, 재시도 없음), 분류 유지 → ok로 재요청 → COMPLETED (저장한 분류 재사용) | 통합 |
| TC-ANA-06 | 진행 중 분석 있을 때 두 번째 제출 → 429 E4004 | 통합 |
| TC-ANA-07 | 11MB → 413 E2003, GIF → 415 E2004 | 통합 |
| TC-ANA-08 | GENERATING 상태에서 재시작(복구 함수 호출) → QUEUED → COMPLETED | 통합 |
| TC-ANA-10 | AI 401 → 클라이언트 503 E3001, ERROR 로그 | 단위(매퍼·분류) + 통합(`AI_MODE=real`, 로컬 가짜 AI 서버) |
| (개정) | 분류 불확실(`uncertain`) → WITHHELD, `recommendationStatus: uncertain`, `recommendation: null`, guidance, 추천 행 없음, 재요청 409 | 통합 |
| (개정) | 정상·고민 없음(`no-concern`) → WITHHELD `no_concern` | 통합 |
| (개정) | 추천 요청 = 저장한 분류 JSON 전체(6개 확률 그대로) + 설문(고민 라벨, `selfReportedConcerns`), `metrics` 없음 | 단위 + 통합 (FixtureAiClient가 받은 요청 기록) |
| (개정) | AI 503 E3001(분류기 비활성) → 503 E3001 | 단위(매퍼) |
| TC-HIS-01·03 | 목록 20/5, 최신순 / 삭제 후 목록에서 제외 | 통합 (시드 또는 팩토리) |
| TC-SEC-04 | 같은 CF-Connecting-IP 11번째 429, 다른 IP 정상 | 통합 |
| TC-SYS-01 | `ai-down` → aiAvailable false, 제출 503 E3001, 로그인·이력 정상 | 통합 |
| TC-SEC-01 | A 토큰으로 B 분석 조회·삭제·재요청 → 404 E4003 | 통합 |
| TC-SEC-02 | password_hash가 `$2b$12$`로 시작 | 통합 |
| TC-PRI-01 | 분석 처리 후 업로드 관련 파일이 디스크에 없음 | 통합 (임시 디렉터리·build에서 표식 검색) + 코드 검사(`transferTo`·임시 파일·파일 쓰기 API 없음, multipart 임계값 > 최대 크기) |
| TC-PRI-02 | 전체 흐름의 로그 출력에 설문 note·토큰·비밀번호·이메일 원문·분류 라벨 없음, 로그 줄이 빠지지 않음 | 통합 (콘솔 출력 캡처 후 문자열 검색) |
| TC-PERF-04 | `rec-slow`로 큐를 채운 뒤 15개 계정 제출 → 12번째부터 503 E3003 | 통합(로컬) 또는 k6. **첫 건이 GENERATING으로 넘어간 뒤** 나머지를 보내야 "처리 1 + 대기 10"이 성립(아니면 11번째부터 거절) |

TC-HIS-02(추이)·TC-ANA-09(데모 측정 → metrics null)는 회귀 지표가 빠져 삭제했다.

스테이징에서 수동·k6로 하는 것 [개정 2.1]: TC-PERF-01(제출 = 분류 포함 p95 ≤ 3초), TC-PERF-02(`recommend_ms` p95 ≤ 120초, 큐 대기 제외), TC-PERF-03(10건 동시 접수 → 누락 0·중복 0, 대기·생성·완료·실패 상태 일관), TC-E2E-01~03(앱 담당 — 접수·분류·대기·추천 완료·보류·실패 화면).

### 16.3 CI (`.github/workflows/ci.yml`)

```yaml
# TRD §16.3 — develop·main 대상 PR과 푸시에서 형식 검사·단위 테스트·빌드·통합 테스트
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
        ports: ['5432:5432']
        options: --health-cmd pg_isready --health-interval 5s --health-retries 10
    env:
      # 통합 테스트 DB (이름에 test가 있어야 돈다). 그 밖의 설정은 테스트 코드가 고정 값으로 넣는다
      DATABASE_URL: postgres://test:test@localhost:5432/test
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 21 }
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew spotlessCheck
      - run: ./gradlew test
      - run: ./gradlew bootJar
      - run: ./gradlew integrationTest
```

통합 테스트의 앱 설정(가짜 JWT 비밀키 등)은 테스트 코드가 고정 값으로 넣으므로 CI에는 `DATABASE_URL`만 준다.

---

## 17. 배포 (SC-02 §7)

### 17.1 Dockerfile [구현 결정]

- 멀티 스테이지: build `eclipse-temurin:21-jdk-alpine`(Gradle wrapper로 `bootJar`, 의존성 층 캐시) → run `eclipse-temurin:21-jre-alpine`(jar만 복사, 비root 사용자, `APP_ENV=production` 기본). AI fixture JSON은 jar 안(`fixtures/ai`)에 들어 있고 배포 서버에서 `AI_MODE=fixture`로 띄울 때만 쓴다.
- 시작: `java -XX:MaxRAMPercentage=75 -jar app.jar`. 마이그레이션은 앱 기동 시 Flyway가 먼저 적용한다(실패하면 기동하지 않음 → compose가 재시작).
- `HEALTHCHECK`: `GET /api/v1/system/status` (DB까지 확인).
- 시드: `docker compose run --rm -e APP_ENV=development backend --seed` (§8.4).

### 17.2 docker-compose.yml (이 레포에 둠)

```
docker-compose.yml
├─ web          nginx:1.27-alpine (/api 프록시, 업로드 12MB 제한 — 앱만 쓰므로 정적 파일 서빙 없음)
├─ backend      eclipse-temurin:21-jre-alpine (Spring Boot, 포트 3000 — 외부 비공개)
├─ db           postgres:16-alpine (볼륨 pgdata, 외부 비공개)
└─ cloudflared  (app.<도메인> → web:80)
```

- 외부에 여는 포트 없음. 로컬 개발을 위해 db만 `127.0.0.1:5432:5432`로 바인드(서버에서도 외부에 노출되지 않음) [구현 결정].
- 로컬 개발은 `docker compose up -d db`만 쓴다.
- 로그 로테이션: `logging: { driver: json-file, options: { max-size: "10m", max-file: "14" } }` (보존 14일 근사).
- 배포: main 병합 → 서버에서 `git pull && docker compose up -d --build` (자동화는 S). V2 마이그레이션이 처음 적용될 때 기존 분석 기록이 지워진다(§8.3).
- DB 백업(매일 03:00 pg_dump, 7일 보관)은 WBS 6.2 정도희 담당. 컴포즈에 백업 사이드카를 넣을지 함께 정한다.

아래 세 기록은 개정 전 파이프라인(회귀 측정 + `/infer/recommend`) 기준이다.

> 10/10 로컬에서 compose(`web`·`backend`·`db`, cloudflared 제외)로 확인: 컨테이너 시작 시 마이그레이션, 헬스체크 healthy, nginx 경유 `/api` 200·그 밖 404, `CF-Connecting-IP`가 백엔드까지 전달돼 로그인 제한이 IP별로 동작, 12MB 초과 업로드는 nginx가 413 E2003 봉투로 응답, production 쿠키 `Secure`.

> 10/12 전체 실행 확인 (NestJS 버전): 위 구성 + skincare-ai(mock 추천·stub 측정)를 같은 compose 네트워크에 붙이고 `AI_MODE=real`로 연결. nginx 경유 스모크 32개 항목(13개 엔드포인트, 오류 경로, 토큰 교체·재사용 감지, 탈퇴 정리) 통과, 추천 실패 → 재요청 → done, AI 중지 21초 만에 비가용·제출 503 E3001·로그인/이력 정상·재시작 29초 만에 복구, k6 PERF-03(10명 동시) 실패 0.

> 10/13 Spring Boot 버전으로 같은 구성 재확인: Docker 이미지 빌드·헬스체크 healthy·기동 시 Flyway, nginx 경유 `./gradlew smoke` 32개 항목 통과(실제 AI HTTP 클라이언트 → skincare-ai mock), 시드 절차(production 거부, `-e APP_ENV=development`로 실행), k6 PERF-03(10명 동시) 체크 30/30·실패 0·마지막 완료 12.2초, 컨테이너 로그에 토큰·설문 원문 없음.

### 17.3 로컬에서 iOS 앱과 연동 [구현 결정]

- 백엔드(Tomcat)는 기본으로 모든 인터페이스(`0.0.0.0`)에서 받는다 — 실기기에서 Mac의 LAN IP로 붙을 수 있다.
- iOS 시뮬레이터: `http://localhost:3000/api/v1`.
- 실기기: 같은 Wi-Fi에서 Mac의 LAN IP(`http://192.168.x.x:3000/api/v1`). 평문 HTTP라 앱 Info.plist에 ATS 예외가 필요하다(앱 쪽 설정). 또는 임시 터널(`cloudflared tunnel --url http://localhost:3000`)의 HTTPS 주소를 쓴다.
- 로컬 HTTP로 붙을 때는 `Secure` 쿠키가 전달되지 않으므로 `COOKIE_SECURE=false` (터널 HTTPS면 true 그대로). iOS 앱은 본문 `refreshToken`(§9.1)을 쓰므로 이 설정과 관계없이 refresh가 동작한다.
- 개발 중 보류·실패 재현은 앱이 `X-Fixture-Scenario` 헤더를 붙이면 된다(`uncertain`·`no-concern`·`rec-timeout`·`ai-down` 등, production에선 무시).

### 17.4 AI 서버 연동 절차 (김우주 PC가 켜져 있을 때)

1. 김우주: AI 서버 기동 — DenseNet121(CPU) + Qwen2.5 v2(GPU) (`$env:AI_INTERNAL_TOKEN=…; .\scripts\run_disease_pipeline.ps1 -ListenAddress 0.0.0.0`) + 터널(`cloudflared tunnel run skincare-ai`). AI 학습 중에는 추론 서버를 띄우지 않는다(개정 2.2)
2. 백엔드 `.env`: `AI_MODE=real`, `AI_BASE_URL`, `AI_INTERNAL_TOKEN` 설정 후 재시작
3. `GET /api/v1/system/status`가 `aiAvailable: true`인지 확인 (AI `/health`의 `disease_loaded: true`가 필요, §12.6)
4. `SMOKE_BASE_URL=https://app.<도메인>/api/v1 ./gradlew smoke`로 API 전체 확인 [구현 결정] — 임시 계정을 가입시켜 엔드포인트·주요 오류 경로를 돌고 탈퇴까지 한다 (`SMOKE_IMAGE`로 본인이 동의한 사진을 줄 수 있다)

로컬에서 미리 확인하려면 skincare-ai를 `REC_BACKEND=mock`(GPU 불필요) + `DISEASE_BACKEND=densenet`(CPU, 가중치 `model/weights/densenet121_s2.pt`는 AI 레포에 포함, torch·torchvision 필요)으로 띄우고 `AI_MODE=real`로 붙인다. `DISEASE_BACKEND=disabled`(AI 서버 기본값)면 분류 API가 503 E3001이고 `disease_loaded: false`라 `aiAvailable=false`가 정상이다. 요청·응답 JSON만 보려면 `./gradlew aiContractTest`의 추천 시험을 쓴다.

연동 테스트가 필요하면 김우주에게 **하루 전** 요청한다(PC 학습 일정과 겹침 방지).

---

## 18. 구현 단계

### 18.1 단계별 계획

> 10/10 진행: Phase 0–7 구현·테스트 완료 (단위 + e2e, fixture 모드). 로컬에서 실제 skincare-ai(mock 추천·stub 측정)와 `AI_MODE=real` 연동도 확인했다.
>
> 10/13: 같은 API·DDL로 Spring Boot 전환(Phase 8). Phase 0–7의 체크는 기능 기준이며 Spring Boot 버전에서 다시 확인했다. NestJS 구현은 `archive/nestjs` 브랜치.
>
> 10/16: 개정 수행계획서로 분석 파이프라인을 바꿨다(Phase 9). Phase 2·3·5의 회귀 측정·점수·추이 항목은 Phase 9로 대체됐다.

**Phase 0 — 골격 (WBS 4.1·4.2)**
- [x] 프로젝트 골격, 형식 검사 (NestJS 10 → 10/13 Spring Boot 4.1 · Java 21 · Spotless)
- [x] `config/` env 스키마·검증(§6), `.env.example`
- [x] `common/`: 에러 코드 표·`ApiException`·전역 예외 처리, 응답 봉투, X-Request-Id, JSON 로깅, 시간 유틸, 열거형(부록 A)
- [x] prefix `api/v1`, CORS, 프록시 뒤 IP, 422 E4002 검증, Swagger `/api/docs`, 모든 인터페이스에서 listen
- [x] DB 연결, §8.1 마이그레이션 (TypeORM → 10/13 Flyway `V1`)
- [x] `docker-compose.yml`(db), 실행·테스트·시드 명령 (README)
- [x] CI(§16.3), 통합 테스트 기반(§16.1)

**Phase 1 — 인증 (WBS 4.3, M)**
- [x] signup, login(로그인 제한 포함), refresh(교체·재사용 감지), logout, 액세스 토큰 인증, GET /users/me
- [x] 시드(test01~40, test01 완료 분석 25건)
- 완료 기준: TC-AUTH-01~06, TC-SEC-02·04

**Phase 2 — 분석 생성·조회 (WBS 4.4, M)** — 10/16 Phase 9로 다시 구현
- [x] AiClient 인터페이스, FixtureAiClient(시나리오, 헤더 오버라이드)
- [x] POST /analyses(검사 순서, 메모리 업로드, 시그니처·크기·짧은 변, survey 검증, 트랜잭션 저장), GET /analyses/{id} presenter
- [x] HttpAiClient(타임아웃·재시도·오류 분류 §12.2)

**Phase 3 — 추천 큐 (WBS 4.5, M)**
- [x] 큐 SQL 리포지토리, 워커(`tick`), 재시도 정책, 기동 복구, 장기 대기 만료, 취소 처리
- [x] POST /analyses/{id}/recommendation/retry (S)

**Phase 4 — 상태·제한 (WBS 4.6)**
- [x] AiHealthService, GET /system/status, AI 가용성 가드
- [x] 일일 한도(KST), 진행 중 1건 + 처리 중 제출 잠금, 대기열 상한, Retry-After
- 완료 기준: TC-SYS-01, TC-ANA-06, TC-PERF-04

**Phase 5 — 이력·탈퇴 (WBS 4.7, S)**
- [x] GET /analyses(페이지), DELETE /analyses/{id}, DELETE /users/me (추이는 10/16 삭제)
- [x] 리프레시 토큰 정리 배치(03:30 KST)

**Phase 6 — 배포 구성 (WBS 6.1 정도희와 협업)**
- [x] Dockerfile, 운영 compose(web·backend·db·cloudflared), 로그 로테이션, 시작 시 마이그레이션

**Phase 7 — 부하 테스트 (WBS 4.8)**
- [x] k6 스크립트(`load/`): TC-PERF-01·03·04, 계정별 토큰 미리 발급(로그인 제한 회피), 스테이징 `ANALYSIS_DAILY_LIMIT=100`
- [x] 응답 시간 분포 집계 쿼리(classify_ms, queue_wait_ms, recommend_ms) — `load/latency-report.sql`·`latency-rows.sql`
- [ ] 스테이징 실측 (11/10–11/24, 실제 AI 서버) — 실행 방법은 `load/README.md`

**Phase 8 — Spring Boot 전환 (10/13) [구현 결정]**
- [x] 같은 API 계약·DDL로 전체 재구현 (Java 21, Spring Boot 4.1, JdbcClient, Flyway), NestJS 구현은 `archive/nestjs` 브랜치에 보관
- [x] OpenAPI 3.0 출력 유지(앱 타입 생성), multipart 본문 스키마·nullable 참조 정리
- [x] Dockerfile(JDK 빌드 → JRE 실행), CI(Gradle), 스모크(`./gradlew smoke`)·합성 이미지(`./gradlew loadImage`)·지연 집계(SQL)
- [ ] 팀 문서(SC-02·SC-06·SC-07·개발운영계획서)의 NestJS 표기 수정 — 개정 수행계획서는 Spring Boot로 반영됨

**Phase 9 — 개정 파이프라인 전환 (10/16) [개정]**
- [x] TRD·CLAUDE·README·iOS 연동 가이드에 개정 수행계획서 반영
- [x] DB V2(기존 분석 삭제, `skin_metrics`·회귀 컬럼 삭제, 분류 컬럼·`recommendation_status`·`guidance`, 상태 대문자 5종) — 데이터가 있는 V1 DB 복사본에서 적용 확인
- [x] AI 클라이언트 `/infer/disease`·`/infer/disease-recommend`, 응답 검증, 오류 매핑(503 E3001), health `disease_loaded`
- [x] 분석 API 새 응답(`disease`·`recommendationStatus`·`guidance`), `measurement`·추이·`deviceType`·점수 계산 삭제, 이력 항목
- [x] 워커 COMPLETED / WITHHELD, fixture `disease-pipeline/` 응답 + 시나리오 `uncertain`·`no-concern`, 시드
- [x] 단위 144개·통합 86개(§16.2), 스모크·k6·지연 집계 SQL 갱신 — jar + fixture로 스모크 31개 항목 통과(ok·uncertain) (10/16)
- [ ] 실제 AI(DenseNet + Qwen2.5 v2)와 `./gradlew aiContractTest` — 김우주 PC, 하루 전 요청
- [ ] 앱(Swift) DTO·화면 갱신 — 정도희 (부록 B 계약, [ios-integration.md](ios-integration.md))
- [ ] SC-03·SC-04·SC-08 문서 개정 — 팀 (`skincare-ai`에 PR)

### 18.2 완료 기준 (Definition of Done, SC-06 §4.3)

- [ ] 요구사항 ID의 수용 기준을 만족한다
- [ ] 단위 테스트가 있고 CI(spotlessCheck·test·bootJar·integrationTest)가 통과한다
- [ ] API·DB·화면이 바뀌었다면 해당 문서도 같은 PR에서 고쳤다
- [ ] 로그에 이미지·설문 원문·토큰이 남지 않는다
- [ ] 사용자 문구가 문구 규칙을 지킨다

### 18.3 협업 규칙 요약 (SC-06, 개정 4.3)

- 브랜치: `main`·`develop` 직접 푸시 금지. `feat/{이슈번호}-{영문-요약}`, `fix/…`, `docs/…`, `chore/…`, `hotfix/…`(main에서 출발 → main + develop).
- 커밋: `<타입>: <무엇을 했는지 한 줄, 한국어> (#이슈번호)` — 타입 feat·fix·docs·refactor·test·chore. 한 커밋에 한 가지 변경.
- PR: develop 대상, 변경 400줄 이하 권장, 템플릿(무엇을·왜·테스트 방법), 리뷰어 정도희 1명 승인, CI 통과 후 Squash merge.
- **명세 변경 절차(문서 먼저, 코드 나중)**: spec 라벨 이슈 → `skincare-ai`에 SC-03·04 수정 PR, 영향 담당자 전원 리뷰 → 병합 후 버전 올림(호환 깨짐 v1.1, 단순 추가 v1.0.1) → 각 레포가 그 PR 번호를 적고 구현. **시연 1주 전(10/13, 12/2)부터 호환이 깨지는 변경 금지.** 10/16 개정은 팀이 낸 개정 수행계획서를 따른 것이라 예외다.
- API 필드와 상태를 바꾸면 예시 JSON과 계약 시험을 함께 갱신하고, AI·백엔드·앱이 같은 버전의 요청·응답 예시를 쓴다 [개정 4.1·4.3].
- 릴리스 태그: v0.1.0, v0.2.0(11/24), v1.0.0(12/8).

---

## 부록 A. 열거형 (SC-03 §6, 개정 부록 A·B) — `common/enums/`의 단일 출처

### A.1 상태 [개정 B.1]

| 필드 | 값 |
|---|---|
| 분석 `status` (DB 값 같음) | `QUEUED`(생성 대기) · `GENERATING`(추천 호출 중) · `COMPLETED`(추천 완료) · `WITHHELD`(추천 보류) · `FAILED` — **대문자** |
| `recommendationStatus` (AI 값) | `generated` · `uncertain` · `no_concern` — COMPLETED·WITHHELD일 때만, 그 밖에는 `null` |

### A.2 이미지 분류 라벨 (6종, AI 클래스 순서) [개정 3.1]

| `labelEn` (DB `disease_label`) | `label` (AI 값) | Qwen에 우선 전달하는 고민 — AI 서버 규칙(참고용, 백엔드 코드에 두지 않음) |
|---|---|---|
| `psoriasis` | 건선 | 민감성(트러블/자극감) |
| `atopic` | 아토피 | 민감성(트러블/자극감) |
| `acne` | 여드름 | 여드름/뾰루지 |
| `normal` | 정상 | 사용자가 선택한 지원 고민 (없으면 `no_concern`) |
| `rosacea` | 주사 | 붉어짐(홍조), 민감성(트러블/자극감) |
| `seborrheic` | 지루 | 민감성(트러블/자극감) |

`probabilities`의 키는 `label`(한글)이고 이 표의 순서로 놓는다. 매핑 열의 원본은 skincare-ai `configs/disease_mapping.yaml`이다.

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

### A.6 기타

| 항목 | 값 |
|---|---|
| gender | `male` · `female` |
| ageBand | `10s` `20s` `30s` `40s` `50s` `60s` `70s` |
| 수준 (수분 섭취·자외선·스트레스) | `low` · `medium` · `high` |

10/16 삭제: ROI, 지표 metric, 등급 grade, deviceType (회귀 지표·촬영 환경 보정이 범위에서 빠짐).

---

## 부록 B. 앱·백엔드 연동 계약 요약 (개정 부록 B)

개정 수행계획서 부록 B의 백엔드 쪽 요약이다. 앱 쪽 세부는 [ios-integration.md](ios-integration.md).

| 구간 | 초기 설정 (개정 B.2) | 이 레포의 값 |
|---|---|---|
| Spring → 이미지 분류 | 읽기 제한 20초 | `AI_CLASSIFY_TIMEOUT_MS=20000` |
| FastAPI 추천 생성 | 120초 (생성 슬롯 획득 후) | AI 서버 설정 |
| Spring → 추천 호출 | 130초 이상, 앱 요청과 분리한 작업자 | `AI_REC_TIMEOUT_MS=130000`, 워커 |
| Spring 작업자 | 생성 동시 1건, 대기 최대 10건 | `REC_WORKER_CONCURRENCY=1`, `REC_QUEUE_LIMIT=10` |
| Swift 상태 조회 | 처음 3분 3초, 이후 10초 간격 | 앱 |
| 사용자 대기 안내 | 150초 이후 지연 안내, 최대 30분 조회 | 앱. 서버 쪽 장기 대기 만료는 60분(`REC_QUEUE_MAX_WAIT_MIN`) |

B.4 백엔드 변경 범위(분류·추천 DTO, snake_case → camelCase, 내부 토큰, AI 클라이언트, 작업 큐·조회 API, nullable 추천 저장, 모델 버전 기록)는 §10–§13에 반영했다.
