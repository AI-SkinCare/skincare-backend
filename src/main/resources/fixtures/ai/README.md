# AI fixture

`AI_MODE=fixture`일 때 `FixtureAiClient`가 돌려주는 AI 서버 응답 (TRD §12.7, SC-03 §7).
원본은 skincare-ai `docs/fixtures/disease-pipeline/` (브랜치 `feat/rec-sllm-v2`, 커밋 `d09e597`) — 그쪽이 바뀌면 그대로 복사해 온다.

| 파일 | API | 내용 |
|---|---|---|
| `disease-pipeline/generated.response.json` | `POST /infer/disease-recommend` | 아토피 0.9 시험용 분류 → 실제 Qwen2.5 `rec_sllm_v2` 생성 기록 (2026-10-15, 생성 47초, `purpose: null` 포함) |
| `disease-pipeline/uncertain.response.json` | 〃 | 아토피 0.55 → `uncertain` 보류 (GPU 생성 없음) |
| `disease-pipeline/no_concern.response.json` | 〃 | 정상 0.9 + 고민 없음 → `no_concern` 보류 |

`POST /infer/disease`(분류) 응답은 따로 없고 각 파일의 `disease` 객체를 쓴다. 분류 입력은 시험용 JSON이라
`model_version`이 `quality_test_fixture_not_a_patient_prediction`이다 — 실제 환자 판정이 아니다.

fixture 응답도 `HttpAiClient`와 같은 검증·변환 코드(`AiResponseParser`)를 통과한다.
복사 후 `./gradlew test`의 계약 테스트(`AiResponseParserTest`)가 깨지면 AI 응답 형식이 바뀐 것이다.
