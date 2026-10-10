# AI fixture

`AI_MODE=fixture`일 때 `FixtureAiClient`가 돌려주는 AI 서버 응답 (TRD §12.7, SC-03 §7).
원본은 `skincare-ai/docs/fixtures/` — 그쪽이 바뀌면 그대로 복사해 온다.

| 파일 | API | 내용 |
|---|---|---|
| `skin-metrics.ok.json` | `POST /infer/skin-metrics` | stub 회귀기 실제 응답 (`is_stub: true`, `full_face`만, confidence 0.0) |
| `recommend.ok.json` | `POST /infer/recommend` | v2 모델(`rec_sllm_v2`) 실제 출력 (2026-10-02, 생성 58.4초) |

fixture 응답도 `HttpAiClient`와 같은 검증·변환 코드(`src/ai-client/ai-response.schemas.ts`)를 통과한다.
복사 후 `npm run test`의 계약 테스트가 깨지면 AI 응답 형식이 바뀐 것이다.
