# iOS 앱 연동 가이드

백엔드 API를 Swift 앱에서 쓸 때 지켜야 할 것만 모았습니다. 계약의 기준은 Swagger(`/api/docs-json`)이고, 근거는 [TRD](TRD.md)와 팀 문서 SC-03입니다.

## 1. 주소와 공통 헤더

| 환경 | Base URL |
|---|---|
| 시뮬레이터 | `http://localhost:3000/api/v1` |
| 실기기 (같은 Wi-Fi) | `http://<Mac LAN IP>:3000/api/v1` — 평문 HTTP라 Info.plist ATS 예외 필요 |
| 배포 | `https://app.<도메인>/api/v1` |

| 헤더 | 언제 | 값 |
|---|---|---|
| `Authorization` | 인증이 필요한 API | `Bearer <accessToken>` |
| `X-Client` | **모든 요청** | `ios` |
| `X-Request-Id` | 모든 요청 (권장) | 요청마다 새로 생성. `^[A-Za-z0-9._-]{1,64}$` (예: UUID). 응답 헤더에 그대로 돌아오고 서버·AI 로그를 이 값으로 찾는다 |
| `X-Fixture-Scenario` | 개발 서버에서 실패 재현할 때만 | `ok` · `no-face` · `rec-timeout` · `rec-slow` · `ai-down` (production은 무시) |

`Origin`을 보내지 않으므로 CORS는 신경 쓰지 않아도 됩니다.

## 2. 응답 봉투

```json
{ "success": true, "data": { … } }
{ "success": false, "error": { "code": "E2001", "message": "얼굴을 찾지 못했어요. 정면에서 다시 찍어 주세요.", "detail": null } }
```

- `message`는 **그대로 화면에 보여도 되는 문장**입니다. 코드별 문구를 따로 만들 필요가 없습니다.
- `detail`은 개발용입니다(화면 비표시). 검증 실패(E4002)면 `{ "fields": [{ "field": "survey.gender", "reason": "required" }] }`.
- 204(로그아웃·탈퇴·삭제)는 본문이 없습니다.
- 시간은 `2026-10-15T09:12:33Z`(UTC, 밀리초 없음). 화면에는 KST로 바꿔 표시합니다.

## 3. 인증 — 리프레시 토큰은 본문으로 (X-Client: ios)

| 단계 | 앱 동작 |
|---|---|
| 로그인 `POST /auth/login` | 응답 `data.accessToken`은 **메모리**에, `data.refreshToken`은 **Keychain**에 저장 |
| API 호출 | `Authorization: Bearer <accessToken>` |
| **401 E1001** | `POST /auth/refresh` 본문 `{ "refreshToken": "<Keychain 값>" }` → 새 `accessToken`·`refreshToken`으로 **둘 다** 교체 → 원래 요청 1회 재시도 |
| refresh도 401 | Keychain 토큰 삭제 → 로그인 화면 |
| 앱 시작 | 화면 진입 전에 refresh로 로그인 상태 복원 |
| 로그아웃 `POST /auth/logout` | 액세스 토큰 + 본문 `{ "refreshToken": … }` → Keychain 삭제 |

반드시 지킬 것:

- **refresh는 한 번만 (single-flight).** 여러 요청이 동시에 E1001을 받아도 refresh는 하나만 보내고 나머지는 그 결과를 기다립니다. 리프레시 토큰은 쓸 때마다 교체되므로, 같은 토큰을 두 번 보내면 **재사용으로 판정돼 그 사용자의 모든 토큰이 폐기**됩니다(강제 로그아웃).
- **E1003에서는 refresh하지 않습니다.** E1003은 로그인 실패(이메일·비밀번호 불일치)이고, refresh 대상은 E1001뿐입니다.
- 서버는 `Set-Cookie`로도 같은 토큰을 보내고, **쿠키가 있으면 쿠키를 우선**합니다. URLSession이 오래된 쿠키를 자동으로 붙이면 Keychain 값과 어긋나 재사용 판정이 날 수 있으니, auth 요청에서는 쿠키 자동 처리를 끄는 것을 권장합니다(`URLRequest.httpShouldHandleCookies = false` 또는 쿠키 저장소를 쓰지 않는 세션).
- 액세스 토큰 수명 3600초, 리프레시 토큰 14일.

## 4. 분석 제출 `POST /analyses` (multipart/form-data)

| 필드 | 값 |
|---|---|
| `image` | 파일. **JPEG로 변환** (HEIC면 415 E2004), **회전을 픽셀에 반영**(EXIF 방향 태그만 두지 않음), 긴 변 1600px·품질 0.9로 줄임. 10MB 이하, 짧은 변 720px 이상 |
| `survey` | **JSON 문자열** (객체가 아님). 필수 `gender`, `ageBand` / 나머지 선택. 모르는 값은 생략하거나 `null` |
| `deviceType` | 항상 `smartphone` |

```json
{ "gender": "female", "ageBand": "20s", "skinType": "combination", "sleepHours": 6,
  "waterIntakeLevel": "low", "sunExposureLevel": "high", "stressLevel": "medium",
  "concerns": ["pore", "pigmentation"], "currentProducts": ["토너"], "note": "…" }
```

- 업로드 전에 형식·크기·해상도를 화면에서 먼저 막습니다(FR-CAP-02). 서버 검사는 마지막 방어선입니다.
- 응답 **202** `{ analysisId, status: "queued", queuePosition, estimatedWaitSec }` → 결과 화면으로 이동해 폴링 시작.
- 측정(약 3초)이 실패하면 분석이 만들어지지 않고 바로 오류가 옵니다.

## 5. 결과 폴링 `GET /analyses/{id}`

- `status`가 `queued`·`recommending`이면 다시 호출: **처음 3분은 3초, 이후 10초, 15분이 지나면 중단**.
- `measurement`(측정 결과)는 상태와 관계없이 항상 있습니다. 추천을 기다리는 동안에도 바로 보여 줍니다.
- 대기 중이면 `queuePosition`·`estimatedWaitSec`("대기 2번째 · 약 2분"). `recommending`이면 둘 다 `null`.
- `recommendation.status`: `pending` · `done` · `failed`. 실패면 `error.code`(E3001·E3002·E3003·E4001)와 `message`, 그리고 `POST /analyses/{id}/recommendation/retry`로 다시 요청합니다(202).
- `measurement.isDemo`가 `true`면 "데모 값" 배지와 안내 문구를 표시합니다.
- `summary[].score`는 좋은 방향으로 통일한 값(막대 길이), `value`는 원래 값(색소침착·모공은 낮을수록 좋음), `grade`는 `good`/`normal`/`care` → 양호/보통/관리 필요.
- 고민 `code`의 화면 표기는 앱이 정합니다(예: `acne` → "트러블"). `label`은 AI 라벨이라 그대로 쓰지 않습니다.
- `survey`는 저장된 설문이라 다음 측정의 기본값으로 씁니다.

## 6. 오류 코드별 처리

| HTTP·코드 | 상황 | 앱 동작 |
|---|---|---|
| 401 E1001 | 토큰 없음·만료·위조 | refresh 후 1회 재시도 (§3) |
| 401 E1003 | 로그인 실패 | 폼 아래 `message` |
| 409 E1004 | 이미 가입된 이메일 | 가입 폼 이메일 필드 오류 |
| 422 E2001 / E2002 | 얼굴 미검출 / 품질·해상도 미달 | 촬영 화면으로, 사유 배너 (설문 값 유지) |
| 413 E2003 / 415 E2004 | 10MB 초과 / JPEG·PNG 아님 | 업로드 전에 막는 것이 원칙 |
| 503 E3001 | AI 서버 점검 중 | "분석 서버 점검 중" 다이얼로그 (`Retry-After: 30`) |
| 503 E3003 | 대기열 가득 | `message` 다이얼로그 (`Retry-After: 60`) |
| 429 E4004 | 진행 중 분석 있음 / 일일 20건 / 로그인 분당 10회 | `message` 다이얼로그. `detail.reason`: `in_progress`·`daily_limit`·`login_rate`, `Retry-After` 초 |
| 404 E4003 | 없거나 남의 분석 | "결과를 찾을 수 없어요" |
| 409 E4005 | 실패가 아닌 추천을 재요청 | 화면 새로고침 |
| 422 E4002 | 입력값 오류 | `detail.fields`로 해당 필드 표시 |
| 500 E4001 | 내부 오류 | 공통 오류 + 요청 ID 앞 8자리(응답 헤더 `X-Request-Id`) |

## 7. 그 밖의 API

| API | 용도 |
|---|---|
| `GET /system/status` (인증 없음) | `aiAvailable=false`면 홈·촬영 화면에 점검 배너, 측정 시작 버튼 비활성. `checkedAt`은 서버의 첫 헬스체크 전이면 `null`일 수 있으니 Optional로 받는다 |
| `GET /analyses?page=1&size=20` | 이력 (최신순). 홈의 최근 결과는 `size=1` |
| `GET /analyses/{id}/trend` | 직전 분석 대비. `previous: null`이면 "비교할 이전 측정이 없습니다". 색소침착·모공은 `delta`가 음수면 좋아진 것 |
| `DELETE /analyses/{id}` | 이력 삭제 (204) |
| `GET /users/me` · `DELETE /users/me` | 내 정보 · 탈퇴 (204, 모든 데이터 즉시 삭제) |

## 8. 개발할 때

- 로컬 백엔드는 `AI_MODE=fixture`라 AI 서버 없이 전체 흐름이 됩니다. 실패 화면은 `X-Fixture-Scenario` 헤더로 재현합니다(`no-face` → E2001, `rec-timeout` → 추천 실패, `ai-down` → 점검 중).
- 테스트 계정: `test01`~`test40@skincare.dev` (비밀번호는 담당자에게 1:1로). `test01`에는 완료 분석 25건이 있어 이력·추이 화면 확인용입니다.
- 타입 생성: `GET /api/docs-json`(OpenAPI 3.0, Redocly 린트 통과)을 swift-openapi-generator 등에 넣습니다. multipart 제출의 `survey`는 문자열 필드라 직접 JSON 인코딩해 넣습니다.
