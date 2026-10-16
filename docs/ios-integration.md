# iOS 앱 연동 가이드

백엔드 API를 Swift 앱에서 쓸 때 지켜야 할 것만 모았습니다. 계약의 기준은 Swagger(`/api/docs-json`)이고, 근거는 [TRD](TRD.md)와 개정 수행계획서(2026-10-15) 부록 B, 팀 문서 SC-03입니다.

> **10/16 분석 API가 바뀌었습니다 (호환 깨짐).** 피부 지표 측정(`measurement`)·추이(`/trend`)·`deviceType`이 없어지고, 이미지 분류(`disease`)와 추천 상태(`status`·`recommendationStatus`·`guidance`)로 바뀌었습니다. 인증·오류 봉투는 그대로입니다. 바뀐 곳: §4·§5·§6·§7·§8.

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
| `X-Fixture-Scenario` | 개발 서버에서 보류·실패 재현할 때만 | `ok` · `uncertain` · `no-concern` · `no-face` · `rec-timeout` · `rec-slow` · `ai-down` (production은 무시) |

`Origin`을 보내지 않으므로 CORS는 신경 쓰지 않아도 됩니다. AI 서버의 내부 토큰은 앱에 넣지 않습니다 — 앱은 백엔드만 호출합니다.

## 2. 응답 봉투

```json
{ "success": true, "data": { … } }
{ "success": false, "error": { "code": "E2004", "message": "JPEG 또는 PNG 사진만 올릴 수 있어요.", "detail": null } }
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
| `image` | 파일. **JPEG로 변환** (HEIC면 415 E2004 — HEIC 원본은 보내지 않음), **회전을 픽셀에 반영**(EXIF 방향 태그만 두지 않음 — 분류기가 회전을 보정하지 않음), 긴 변 1600px·품질 0.9로 줄임. 10MB 이하, 짧은 변 720px 이상 |
| `survey` | **JSON 문자열** (객체가 아님). 필수 `gender`, `ageBand` / 나머지 선택. 모르는 값은 생략하거나 `null` |

`deviceType`은 더 이상 보내지 않습니다(보내도 무시).

```json
{ "gender": "female", "ageBand": "20s", "skinType": "combination", "sleepHours": 6,
  "waterIntakeLevel": "low", "sunExposureLevel": "high", "stressLevel": "medium",
  "concerns": ["pore", "pigmentation"], "currentProducts": ["토너"], "note": "…" }
```

- `concerns`(고민)는 분류가 **정상**일 때 추천 대상이 됩니다. 정상인데 고민이 없으면 추천이 보류(`no_concern`)되므로, 설문에서 고민을 고를 수 있게 둡니다.
- 업로드 전에 형식·크기·해상도를 화면에서 먼저 막습니다(FR-CAP-02). 서버 검사는 마지막 방어선입니다.
- 응답 **202** — `GET /analyses/{id}`와 **같은 형태**(§5). 처음엔 `status: "QUEUED"`, `disease`(분류 결과)가 이미 들어 있으니 바로 결과 화면에서 분류 참고 결과와 대기 안내를 보여 주고 폴링을 시작합니다.
- 이미지 분류(목표 3초)가 실패하면 분석이 만들어지지 않고 바로 오류가 옵니다.

## 5. 결과 조회 `GET /analyses/{id}`

```json
{
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
    "concerns": [{ "code": "sensitivity", "label": "민감성(트러블/자극감)" }],
    "ingredients": [{ "name": "쑥잎 추출물", "purpose": "…", "priority": 1 }, { "name": "대나무추출물", "purpose": null, "priority": 2 }],
    "routine": [{ "step": 1, "time": "anytime", "action": "…" }],
    "reasoning": ["…"], "answer": "…", "inputNotes": ["…"], "disclaimer": "…",
    "filtered": true, "usedFallback": false, "modelVersion": "rec_sllm_v2", "inferenceMs": 47038
  },
  "guidance": ["이미지 모델의 6종 분류 참고 결과이며 의학적 진단이 아닙니다. …", "…"],
  "error": null,
  "queuePosition": null, "estimatedWaitSec": null,
  "createdAt": "2026-10-16T09:12:33Z", "completedAt": "2026-10-16T09:13:21Z",
  "survey": { … }
}
```

### 상태별 처리

| `status` | 의미 | 화면 | 폴링 |
|---|---|---|---|
| `QUEUED` | 추천 생성 대기 | 분류 참고 결과 + 대기 안내("대기 2번째 · 약 2분" — `queuePosition`·`estimatedWaitSec`) | 계속 |
| `GENERATING` | 추천 생성 중 (약 45초) | 진행 표시, 다시 제출 막기 (`queuePosition`·`estimatedWaitSec`는 `null`) | 계속 |
| `COMPLETED` | 추천 완료 (`recommendationStatus: "generated"`) | 추천(성분·관리 단계·요약) + `guidance` | 끝 |
| `WITHHELD` | 추천 보류 (`recommendationStatus: "uncertain"` 또는 `"no_concern"`, `recommendation: null`) | 추천 없이 사유와 다음 행동 + `guidance`. `uncertain` → 재촬영·전문의 상담 안내, `no_concern` → 설문에서 고민을 골라 다시 분석 | 끝 |
| `FAILED` | 통신·생성·저장 실패 | `error.message` + 재요청 버튼 → `POST /analyses/{id}/recommendation/retry`(202, 저장된 분류로 추천만 다시) | 끝 |

- 폴링 간격: **처음 3분은 3초, 이후 10초**. 150초가 지나면 "생각보다 오래 걸리고 있어요" 같은 지연 안내, **최대 30분** 조회 후 중단하고 이력에서 다시 보게 합니다. 한 번의 요청을 오래 붙잡지 않습니다(각 GET은 바로 끝남).
- 앱을 껐다 켜거나 화면을 나가도 서버 작업은 계속됩니다. 같은 `analysisId`로 다시 조회합니다.
- **보류는 오류가 아닙니다.** `WITHHELD`를 실패 화면으로 보내거나 빈 추천으로 그리지 않습니다. 재요청 대상도 아닙니다(409 E4005).

### 표시 규칙 (개정 부록 A.3)

- `disease`는 상태와 관계없이 항상 있습니다. **참고 결과**로 보여 주고 진단처럼 표현하지 않습니다. `confidence`(0–1)를 보여 줄 때는 백분율로 바꾸고 반드시 **"모델 분류 점수"**라고 적습니다(질환 확률로 표시하지 않음). `probabilities`는 6종 키가 고정 순서로 옵니다.
- `guidance`(문자열 배열)는 서버가 준 안내문이라 그대로 보여 줍니다. COMPLETED·WITHHELD에서 채워지고 그 밖에는 `[]`.
- `ingredients`는 개수가 정해져 있지 않습니다(배열 길이대로). `purpose`가 `null`이면 성분명만 표시하고 효능을 지어내지 않습니다.
- `routine[].time`: `morning` · `evening` · `daily` · `weekly` · `anytime`.
- `reasoning`은 생성된 설명입니다. 검증된 근거처럼 표시하지 않습니다.
- `filtered`·`usedFallback`·`modelVersion`·`inferenceMs`는 화면에 쓰지 않습니다(분석·운영용). `usedFallback: true`는 규칙 기반 대체 답변이라는 뜻입니다.
- 고민 `code`의 화면 표기는 앱이 정합니다(예: `sensitivity` → "민감함"). `label`은 AI 라벨이라 그대로 쓰지 않습니다.
- `survey`는 저장된 설문이라 다음 분석의 기본값으로 씁니다.

## 6. 오류 코드별 처리

| HTTP·코드 | 상황 | 앱 동작 |
|---|---|---|
| 401 E1001 | 토큰 없음·만료·위조 | refresh 후 1회 재시도 (§3) |
| 401 E1003 | 로그인 실패 | 폼 아래 `message` |
| 409 E1004 | 이미 가입된 이메일 | 가입 폼 이메일 필드 오류 |
| 422 E2002 | 해상도 부족·사진을 읽을 수 없음(디코딩 실패) | 촬영 화면으로, `message` 배너 (설문 값 유지) |
| 422 E2001 | 얼굴 미검출 — **AI에 아직 없음**(개발 서버 `no-face`로만 재현) | E2002와 같이 처리 |
| 413 E2003 / 415 E2004 | 10MB 초과 / JPEG·PNG 아님 | 업로드 전에 막는 것이 원칙. 압축·재선택·JPEG 변환 안내 |
| 503 E3001 | AI 서버 점검 중·준비 안 됨 | "분석 서버 점검 중" 다이얼로그 (`Retry-After: 30`) |
| 503 E3003 | 대기열 가득 | `message` 다이얼로그 (`Retry-After: 60`) |
| 429 E4004 | 진행 중 분석 있음 / 일일 20건 / 로그인 분당 10회 | `message` 다이얼로그. `detail.reason`: `in_progress`·`daily_limit`·`login_rate`, `Retry-After` 초 |
| 404 E4003 | 없거나 남의 분석 | "결과를 찾을 수 없어요" |
| 409 E4005 | FAILED가 아닌 분석을 재요청 | 화면 새로고침 |
| 422 E4002 | 입력값 오류 | `detail.fields`로 해당 필드 표시 |
| 500 E4001 | 내부 오류 | 공통 오류 + 요청 ID 앞 8자리(응답 헤더 `X-Request-Id`) |

FAILED 분석의 `error.code`는 E3001(AI 서버 점검·장시간 대기)·E3002(추천 시간 초과)·E3003(혼잡)·E4001(내부 오류) 중 하나이고, 모두 `message`를 그대로 보여 주고 재요청을 허용합니다.

## 7. 그 밖의 API

| API | 용도 |
|---|---|
| `GET /system/status` (인증 없음) | `aiAvailable=false`면 홈·촬영 화면에 점검 배너, 분석 시작 버튼 비활성. `checkedAt`은 서버의 첫 헬스체크 전이면 `null`일 수 있으니 Optional로 받는다 |
| `GET /analyses?page=1&size=20` | 이력 (최신순). 항목: `analysisId`·`createdAt`·`status`·`recommendationStatus`·`disease`·`concerns`(COMPLETED면 고민 코드, 아니면 `[]`). 홈의 최근 결과는 `size=1` |
| `DELETE /analyses/{id}` | 이력 삭제 (204) |
| `GET /users/me` · `DELETE /users/me` | 내 정보 · 탈퇴 (204, 모든 데이터 즉시 삭제) |

`GET /analyses/{id}/trend`(추이)는 없어졌습니다(비교할 지표가 없음).

## 8. 개발할 때

- 로컬 백엔드는 `AI_MODE=fixture`라 AI 서버 없이 전체 흐름이 됩니다. 화면별 상태는 `X-Fixture-Scenario` 헤더로 재현합니다(POST /analyses와 retry에 붙이면 그 분석의 추천까지 따라감):

  | 헤더 | 결과 |
  |---|---|
  | (없음)·`ok` | 아토피 0.9 분류 → 약 1초 뒤 `COMPLETED` (실제 Qwen 생성 기록, `purpose: null` 항목 포함) |
  | `uncertain` | 아토피 0.55(`uncertain: true`) → `WITHHELD` / `uncertain` |
  | `no-concern` | 정상 0.9 → `WITHHELD` / `no_concern` |
  | `rec-timeout` | `FAILED` E3002 → 헤더 없이 재요청하면 `COMPLETED` |
  | `rec-slow` | 60초 뒤 `COMPLETED` (대기·생성 중 화면) |
  | `no-face` | 제출 422 E2001 |
  | `ai-down` | 제출 503 E3001 |

  fixture의 분류는 시험용 JSON이라 `modelVersion`이 `quality_test_fixture_not_a_patient_prediction`입니다.
- 테스트 계정: `test01`~`test40@skincare.dev` (비밀번호는 담당자에게 1:1로). `test01`에는 완료 분석 25건이 있어 이력 화면 확인용입니다.
- 타입 생성: `GET /api/docs-json`(OpenAPI 3.0)을 swift-openapi-generator 등에 넣습니다. multipart 제출의 `survey`는 문자열 필드라 직접 JSON 인코딩해 넣습니다. `recommendation`·`error`·`recommendationStatus`·`queuePosition`·`estimatedWaitSec`·`completedAt`·`ingredients[].purpose`는 Optional입니다.
