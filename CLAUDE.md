# CLAUDE.md — skincare-backend

스마트폰 얼굴 사진 + 설문 → 피부 지표 4종(수분·탄력·색소침착·모공) 측정 → 성분·루틴 추천을 하는 서비스의 **백엔드**다. NestJS 10 · TypeORM · PostgreSQL 16. 담당 이현승, 리뷰어 정도희.

클라이언트는 **Swift iOS 앱**이다(팀 문서에는 React 웹으로 적혀 있음). 백엔드 API·인증은 팀 문서 그대로이고, 네이티브 앱용 리프레시 토큰 본문 전달(`X-Client: ios`, TRD §9.1)만 **[앱 협의]**로 더했다. React·Vite 관련 내용은 프론트 쪽 사항이라 백엔드와 무관하다. 앱은 `Origin`을 보내지 않아 CORS와도 무관하다.

## 먼저 읽을 것

- **`docs/TRD.md`가 구현 기준이다.** 기능을 만들기 전에 해당 절을 읽는다. 이 파일에는 요약과 규칙만 있다.
  - 공통 규약 §7 · DB §8 · 인증 §9 · 분석 API §10 · 점수 §11 · AI 연동 §12 · 추천 큐 §13 · 상태 §14 · 보안 §15 · 테스트 §16 · 배포 §17 · 단계 §18
- 팀 원본 문서는 `skincare-ai/docs/project/` (SC-01 요구사항 ~ SC-08 테스트 계획). 우선순위: **팀 문서 > TRD > 추측**.
- 로컬 참고용 클론 (읽기 전용으로 쓰고 이 레포에 코드를 복사하지 않는다):
  - `../AI` — skincare-ai, 개발 브랜치 `feat/rec-sllm-v2`. 팀 문서 `../AI/docs/project/`, AI 서버 스키마 `../AI/server/schemas.py`, fixture 원본 `../AI/docs/fixtures/`
  - `../AI_FE` — skincare-frontend (`main`, 아직 README만)
- 이 레포 브랜치: `main`(배포·발표, **건드리지 않음**) ← `develop`(백엔드 개발 브랜치). 작업은 `develop`에서 하고 `develop`에 푸시한다 (10/12 이현승 결정).
- 진행 상황은 TRD §18.1 체크리스트로 관리한다. 항목을 끝내면 체크한다.

## 명령어

```bash
docker compose up -d db          # 로컬 PostgreSQL
cp .env.example .env             # AI_MODE=fixture 가 기본
npm ci
npm run migration:run
npm run seed                     # test01~40@skincare.dev, test01에 완료 분석 25건
npm run start:dev                # http://localhost:3000/api/docs

npm run lint
npm run test                     # 단위
npm run test:e2e                 # DB 필요, AI_MODE=fixture
npm run build
npm run migration:generate -- src/database/migrations/<동사><대상>

npm run smoke                    # 실행 중인 서버 전체 흐름 점검 (SMOKE_BASE_URL)
AI_CONTRACT_URL=http://127.0.0.1:8000 AI_CONTRACT_TOKEN=… npm run test:e2e -- ai-contract   # 실제 AI 계약
npm run load:image               # k6용 합성 이미지 → load/README.md
npm run report:latency -- --since <ISO>   # measure/queue_wait/recommend ms 분포
```

스크립트가 아직 없으면 Phase 0(TRD §18.1)에서 이 이름 그대로 만든다.

## 구조 한눈에

```
src/common/        에러 코드·ApiException·전역 필터, 응답 봉투, X-Request-Id, 로깅, 열거형(코드↔라벨 단일 출처)
src/auth, users/   가입·로그인·refresh(교체+재사용 감지)·로그아웃·내 정보·탈퇴
src/analyses/      POST·GET·이력·추이·삭제, scoring.ts(순수 함수), guards/, pipes/, presenter
src/recommendation/ PostgreSQL 큐 워커(동시성 1), 기동 복구, 60분 만료
src/ai-client/     AiClient 인터페이스 → HttpAiClient(real) / FixtureAiClient(fixture), 오류 매퍼, 응답 검증·변환
src/system/        AI 헬스체크(30초), GET /system/status
```

흐름: `POST /analyses` → (요청 안에서) AI 측정 → 저장(status=queued) → 202 → 워커가 1초마다 queued 1건 획득 → AI 추천 → done/failed. 앱은 `GET /analyses/{id}`를 폴링한다.

## 절대 규칙

1. **얼굴 이미지는 디스크에 닿지 않는다.** multer `memoryStorage`만. `dest`·`diskStorage`·임시 파일·캐시 금지. AI 호출 직후 버퍼 참조 해제. 이미지를 로그·예외 메시지·에러 detail에 넣지 않는다.
2. **로그 금지 항목**: 이미지, 설문 원문(`note`·`currentProducts` 포함 전체), 비밀번호, 액세스·리프레시 토큰, 쿠키, `X-Internal-Token`, 요청/응답 본문. 이메일은 `d***@gmail.com`으로 마스킹.
3. **응답은 봉투로만.** 성공 `{ success: true, data }`(204는 본문 없음), 실패는 `throw new ApiException(ErrorCode.Exxxx)` → 전역 필터가 `{ success: false, error: { code, message, detail } }`. Nest·라이브러리 예외가 그대로 나가지 않게 필터에서 변환. 검증 실패는 400이 아니라 **422 E4002**.
4. **IDOR**: 분석 조회·삭제·재요청·추이 쿼리는 전부 `user_id` 조건. 없거나 남의 것이면 똑같이 404 E4003. ULID 형식이 아닌 id도 404.
5. **AI 오류는 반드시 변환한다** (TRD §12.5). AI 401 → 클라이언트에 **503 E3001**(401을 내보내면 앱이 로그아웃시킴). 워커는 연결 오류·503만 5초 후 1회 재시도. **504·422·우리 쪽 타임아웃은 재시도 금지.** 본문에 `error.code`가 없는 5xx(Cloudflare HTML)는 연결 실패로 본다.
6. **0–1 원본을 저장하고 응답할 때 변환한다.** 점수는 `scoring.ts`에서 정수 basis point(0–10000)로 계산 — `0.565 * 100 = 56.49999…` 함정. 반올림은 `Math.round`.
7. **`is_demo=true` 분석의 추천 요청은 `metrics: null`.**
8. **TypeORM**: NUMERIC·BIGINT 컬럼에 number transformer 필수. `synchronize: true` 금지. 스키마 변경은 마이그레이션으로만.
9. **큐는 PostgreSQL**(`FOR UPDATE SKIP LOCKED`). Redis·Bull 추가 금지. 워커 동시성은 1.
10. **API 계약(경로·필드·열거형·에러 코드·상태 코드)과 DDL을 마음대로 바꾸지 않는다.** 명세 동결 기간(10/13~)이다. 바꿔야 할 것 같으면 구현을 멈추고 사용자에게 알린다 — 명세 변경은 문서 먼저(TRD §18.3).
11. **비밀값·데이터 커밋 금지.** 레포가 Public이다. `.env`는 커밋하지 않고 `.env.example`엔 가짜 값만. 실제 얼굴 사진·AI허브 데이터·가중치 금지. 테스트 이미지는 코드로 합성.
12. 사용자에게 보이는 문구(에러 message 등)는 문구 규칙을 지킨다: "진단·치료·처방·완치·개선됩니다·효과가 있습니다·질환명·정상/비정상" 금지 → "측정·관리·도움을 줄 수 있어요·트러블/민감함/붉은기·양호/보통/관리 필요".
13. TRD §4에 없는 의존성을 추가할 때는 이유를 말한다.

## 코드 컨벤션

- 파일 이름 kebab-case (`ai-client.service.ts`, `analysis-presenter.ts`). Prettier printWidth 100, singleQuote. NestJS 기본 ESLint.
- TypeScript strict, `any` 금지 — 외부 입력(AI 응답 포함)은 `unknown`으로 받아 검증한다.
- JSON 필드 camelCase, 열거형 값 소문자 snake_case, DB snake_case(엔티티에 `name:` 명시). AI 응답은 snake_case → 변환.
- 열거형·라벨 표는 `src/common/enums/` 한 곳에만 둔다. 고민 라벨은 괄호·슬래시까지 원문 그대로("미백(색소침착/기미/칙칙함)").
- 시간은 UTC로 저장, 응답은 `toIsoUtc()`(밀리초 없음). KST는 일일 한도·배치 시각에만, UTC+9 고정.
- 여러 테이블을 쓰는 작업은 트랜잭션 하나로.
- TRD의 **[구현 결정]**은 팀 문서에 없어서 TRD가 정한 값이다. 그대로 따르고, 팀 문서와 어긋나 보이면 구현을 멈추고 사용자에게 알린다.
- TRD의 **[앱 협의]**는 앱 담당과 합의해 API 계약에 더한 것이다(기존 동작 유지). SC-03에도 반영돼야 하므로 바꿀 때는 TRD 개정 이력에 남긴다.

## 테스트

- 기능마다 단위 테스트 + e2e(supertest). 테스트 이름에 TC ID를 넣는다: `it('TC-ANA-06: 진행 중 분석이 있으면 429 E4004', …)`. 백엔드 TC 목록은 TRD §16.2.
- e2e는 `AI_MODE=fixture`, `NODE_ENV=test`. 실패·지연은 `X-Fixture-Scenario` 헤더(ok · no-face · rec-timeout · rec-slow · ai-down).
- 워커는 자동 시작하지 않는다. 테스트에서 `RecommendationWorker.tick()`을 직접 부르고, 지연(재시도 5초·fixture 1초/60초)은 `SLEEP` 토큰을 즉시 끝나는 가짜로 바꿔 넘긴다. 실제로 기다리는 테스트를 만들지 않는다.
- `scoring.ts`는 TRD §11.3 표를 그대로 단위 테스트로 넣는다.
- "끝났다"고 하기 전에 `npm run lint && npm run test && npm run build && npm run test:e2e`가 통과해야 한다.

## Git

- 사용자가 요청할 때만 커밋·푸시한다. 백엔드 작업은 `develop`에 푸시하고 **`main`에는 절대 푸시하지 않는다** (main 반영은 develop → main PR, 배포·발표 시점).
- 푸시는 SSH(`origin`의 push URL)로 한다. HTTPS 토큰에는 `workflow` 권한이 없어 `.github/workflows/` 변경이 거절된다.
- 따로 브랜치가 필요하면 `feat/{이슈번호}-{영문-요약}` (fix/ docs/ chore/ 도 같은 형식), develop에서 출발.
- 커밋 `<타입>: <무엇을 했는지 한 줄, 한국어> (#이슈번호)` — feat · fix · docs · refactor · test · chore. 한 커밋에 한 가지 변경.
- PR은 develop 대상, 400줄 이하 권장. 본문: 무엇을 · 왜 · 테스트 방법. API·DB가 바뀌면 문서도 같은 PR에서.

## 자주 틀리는 것

- Nest에서 **Guard는 Interceptor보다 먼저** 돈다. POST /analyses의 순서(인증 → E4004 → E3003 → E3001 → 파일 수신 → 형식 검증 → AI 측정)는 제한 검사를 Guard로, multer를 `FileInterceptor`로 둬서 지킨다.
- multer `LIMIT_FILE_SIZE` → 413 E2003 (500이 아님). 이미지 형식은 확장자·mimetype이 아니라 시그니처(`FF D8 FF`, `89 50 4E 47`)로 판별.
- `survey`는 multipart 안의 JSON 문자열이다. 파싱 실패도 E4002. 오류 필드는 `survey.gender`처럼 접두.
- 설문 → AI 변환: `skinType` 코드 → 한글 라벨, `concerns` → **`selfReportedConcerns`** 한글 라벨. 코드를 그대로 보내면 AI가 인식하지 못한다.
- AI는 `skin_type`·`concerns`를 한글 라벨로 준다 → 코드로 바꿔 저장. 표에 없는 값이면 failed E4001 + ERROR 로그.
- 리프레시 쿠키 `Path=/api/v1/auth`. 지울 때도 같은 Path. 교체된 토큰이 다시 오면 그 사용자 토큰 전부 폐기.
- `X-Client: ios`면 login·refresh 응답 `data`에 `refreshToken`을 더한다(쿠키도 그대로 보냄). refresh·logout은 쿠키가 없으면 본문 `{ refreshToken }`을 받고, 둘 다 있으면 **쿠키 우선**. 본문 토큰도 교체·재사용 감지·해시 저장 규칙이 똑같다. 허용 `X-Client` 값은 `auth/client-type.ts` 한 곳에만 (Android는 나중에 `android` 추가).
- 로그인 실패는 E1003, 토큰 없음·만료·위조는 E1001. 앱은 E1001에서만 refresh하므로 섞지 않는다.
- 로컬 HTTP(시뮬레이터·실기기)에서는 `Secure` 쿠키가 전달되지 않는다 → 개발 중엔 `COOKIE_SECURE=false`. production은 항상 true. (iOS 앱은 본문 `refreshToken`을 쓰므로 이 설정과 무관하게 refresh가 된다.)
- 클라이언트 IP는 `CF-Connecting-IP` → 없으면 `req.ip`. 모든 요청이 nginx·터널에서 와서 `req.ip`만 보면 전원이 같은 IP다.
- 앱은 HEIC를 JPEG로 바꿔 보내야 한다. HEIC가 오면 415 E2004가 정상 동작이다(백엔드가 변환하지 않는다).
- 로컬 실기기 테스트를 위해 `app.listen(PORT, '0.0.0.0')`.
- `pg`는 NUMERIC·BIGINT·`COUNT(*)`를 문자열로 준다.
- 워커가 결과를 저장할 때 `UPDATE … WHERE id=$1 AND status='recommending'`이 0행이면(탈퇴·삭제됨) 결과를 버린다. FK 오류로 500을 내지 않는다.
- POST·retry의 `X-Fixture-Scenario`는 워커까지 전달돼야 한다 → 분석 id별 메모리 맵. production에선 헤더를 무시.
- 데모 값만 있는 지금(회귀 모델 연결 10/17 전)은 trend가 항상 `previous: null`이다. 시드 데이터(`is_demo=false`)로 테스트한다.
- `nest build`는 `deleteOutDir`로 dist를 지운다. `tsBuildInfoFile`을 dist 밖으로 옮기면 재빌드 때 dist가 빈다 (tsconfig.build.json 참고).
