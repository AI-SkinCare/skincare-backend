# AI 추천 보류·출처 계약 (2026-11-12)

사용자 요청에 따라 Swift → Spring → FastAPI 계약을 확장한다. 기존 필드는 유지한다.

- `recommendationStatus`: `generated`, `uncertain`, `no_concern`, **`quality_rejected`**.
- 품질 보류는 `status=WITHHELD`, `recommendation=null`, `error=null`이다. 폴링을 종료하며 추천 재시도 API 대상이 아니다. AI `guidance`를 표시한다.
- 상세 응답에 `generation: {origin, qualityStatus, evidenceStatus, qualityIssues}`를 추가한다. AI 값을 저장해서 반환한다. 누락된 구버전 출처를 모델 생성으로 추정하지 않는다.
- `origin`: `model`, `source_cards`, `rule_fallback`, `not_generated` 또는 미제공 시 null. **not_generated는 recommendationStatus가 아니다.** 생성 완료인데 not_generated/withheld이면 모순된 AI 응답으로 E4001 처리한다.
- `source_cards`는 근거 카드로 구성한 대체 안내이고 모델 생성 성공과 구별해 표시한다. `rule_fallback`도 별도 대체 안내다.
- 성분의 `purposeSource`, `purposeStatus`를 보존한다. 추천 모델 버전은 160자까지 허용한다.
- 선택 설문 `avoidIngredients`: 최대 30개, 각 1–100자(양끝 공백 제거), AI에 같은 이름으로 전달한다. 미입력은 빈 배열. 기존 설문/응답은 계속 읽을 수 있다.
- V3는 기존 행을 삭제하지 않고 generation JSONB와 새 상태 제약, 모델 버전 길이만 추가한다. V1/V2 체크섬은 변경하지 않는다.
- 데이터 삭제를 포함하는 기존 V2 때문에 통합 시험은 반드시 별도의 빈 시험 DB에서 진행한다.

불확실·고민 없음·품질 보류의 구분은 FastAPI가 결정한다. Spring에서 질환 점수나 의학적 조건을 재해석하지 않는다. 분류 점수는 개인 질환 확률이나 중증도가 아니다.

## 검증 (2026-11-12)

`spotlessCheck test bootJar integrationTest` 통과: 단위 150개, 별도 PostgreSQL HTTP 통합 88개.
실제 Swift APIClient → Spring → FastAPI 모델 호출도 4건 통과했다. 품질 보류 1건·정상 무고민 보류 1건은 `generation.origin=not_generated`, 추천 제공 2건은 `source_cards`였다. 두 대체 안내의 긴 모델 버전과 성분 purpose 출처가 DB 저장·조회 후에도 유지됐다. 이력·인증 갱신·삭제, 보류 재요청 거절도 확인했다.
이 검증은 기능 통합 시험이다. 모델 원문 생성 성공률·임상 정확도 또는 iOS 화면 빌드 성공을 의미하지 않는다.
