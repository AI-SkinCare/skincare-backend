> 프로젝트 공통 문서(요구사항·설계·API·DB·협업 규칙): [skincare-ai/docs/project](https://github.com/AI-SkinCare/skincare-ai/tree/feat/rec-sllm-v2/docs/project) · 백엔드 구현 기준: [docs/TRD.md](docs/TRD.md)

# skincare-backend

얼굴 사진과 설문으로 피부 지표를 측정하고 스킨케어 성분·루틴을 추천하는 서비스의 백엔드 저장소입니다.

## 역할
iOS 앱과 AI 추론 서버 사이의 데이터 흐름을 담당하는 API 서버입니다. 사용자 요청 처리, 데이터 저장·관리, AI 서버와의 연동을 담당합니다. 앱은 AI 서버를 모르고 모든 요청은 백엔드를 거칩니다.

## 주요 기능
- 회원가입(동의 4종)·로그인·토큰 재발급(리프레시 토큰 교체·재사용 감지)·로그아웃·탈퇴
- 분석 요청: 사진 + 설문 수신 → AI 측정(요청 안에서 동기) → 저장 → 추천 대기열 등록
- 추천 대기열: PostgreSQL 기반 큐, 워커 1개가 AI 서버에 한 건씩 추천 요청
- 결과·이력·추이·삭제 API, AI 서버 상태(`GET /system/status`)
- 얼굴 이미지는 디스크에 저장하지 않음 (메모리로만 받아 AI 서버로 넘기고 측정 직후 폐기)

## 기술 스택
- Java 21, Spring Boot 4.1 (Spring MVC)
- PostgreSQL 16, JdbcClient, Flyway (마이그레이션)
- JWT(액세스 1시간 · 리프레시 14일, httpOnly 쿠키 + 네이티브 앱용 본문 전달), bcrypt
- 구조화 JSON 로그, Swagger(`/api/docs`, OpenAPI 3.0)
- JUnit · AssertJ (단위·PostgreSQL 통합), Gradle, GitHub Actions CI

> 10/13 NestJS에서 Spring Boot로 전환했습니다 (API·DB 그대로). 이전 구현은 `archive/nestjs` 브랜치에 있습니다.

## 로컬 실행

Java 21이 필요합니다 (macOS: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`). Gradle은 wrapper(`./gradlew`)가 받습니다.

```bash
docker compose up -d db          # 로컬 PostgreSQL
cp .env.example .env             # AI_MODE=fixture가 기본 (AI 서버 없이 개발)
./gradlew seed                   # test01~40@skincare.dev, test01에 완료 분석 25건
./gradlew bootRun                # http://localhost:3000/api/docs (기동 시 마이그레이션 자동 적용)
```

| 명령 | 내용 |
|---|---|
| `./gradlew spotlessApply` / `spotlessCheck` | 코드 형식 (google-java-format) |
| `./gradlew test` | 단위 테스트 (DB 없음) |
| `./gradlew integrationTest` | 통합 테스트 (DB 필요, `<DB이름>_test`를 자동 생성·마이그레이션) |
| `./gradlew bootJar` | 실행 jar (`build/libs/`) |
| `./gradlew smoke` | 실행 중인 서버의 전체 흐름 점검 (`SMOKE_BASE_URL`, 배포 직후·AI 연동 확인용) |
| `./gradlew aiContractTest` | 실제 AI 서버와 계약 확인 (`AI_CONTRACT_URL`, `AI_CONTRACT_TOKEN`) |
| `load/latency-report.sql` | 측정·대기·추천 소요 시간 분포 (psql로 실행, [load/README.md](load/README.md)) |

마이그레이션은 `src/main/resources/db/migration/`에 `V<번호>__<설명>.sql`로 추가합니다 (이미 적용된 파일은 고치지 않음).

부하 테스트(k6)는 [load/README.md](load/README.md), iOS 앱 연동 규칙은 [docs/ios-integration.md](docs/ios-integration.md)를 본다.

- API: `http://localhost:3000/api/v1`, Swagger JSON: `/api/docs-json` (앱 타입 생성용)
- iOS 시뮬레이터는 `localhost`, 실기기는 같은 Wi-Fi에서 Mac의 LAN IP로 접속 (모든 인터페이스에서 listen)
- AI 서버 대신 고정 응답: `AI_MODE=fixture`, 요청마다 `X-Fixture-Scenario: ok | no-face | rec-timeout | rec-slow | ai-down` (production에선 무시)

## 배포
정도희 자택 서버에서 Docker Compose(`web` nginx · `backend` · `db` · `cloudflared`)로 운영합니다. 외부에 여는 포트는 없고 Cloudflare Tunnel로만 들어옵니다.

```bash
docker compose up -d --build     # 앱 기동 시 Flyway 마이그레이션 자동 적용
```

실제 AI 서버 연동은 `.env`에 `AI_MODE=real`, `AI_BASE_URL`, `AI_INTERNAL_TOKEN`을 넣고 재시작한 뒤 `GET /api/v1/system/status`의 `aiAvailable`을 확인합니다 (TRD §17.4).

## 협업
- 브랜치: `main`(배포·발표, 직접 푸시 금지) ← `develop`(개발). 백엔드 작업은 `develop`에 올리고, `main` 반영은 develop → main PR로 한다
- 커밋: `<타입>: <무엇을 했는지 한 줄> (#이슈번호)` — feat · fix · docs · refactor · test · chore
- `.env`·실제 얼굴 사진·학습 데이터·가중치는 커밋하지 않습니다 (Public 레포)

## 관련 저장소
- [skincare-ai](https://github.com/AI-SkinCare/skincare-ai) — AI 모델 / 추론 서버, 프로젝트 공통 문서
- [skincare-frontend](https://github.com/AI-SkinCare/skincare-frontend) — 프론트엔드

팀 소개 및 팀원 역할은 [AI-SkinCare 조직 프로필](https://github.com/AI-SkinCare)에서 확인해 주세요.
