# Grad-CAM 설명 전달 계약 — 2026-11-13

사용자 요청에 따라 기존 분류 → 추천 흐름에 선택형 설명을 추가한다.
기존 분석 상태·경로·추천 필드는 유지한다.

Spring은 사진 버퍼를 해제하기 전에 FastAPI `/infer/disease`에
`include_explanation=true`를 함께 보낸다. FastAPI는 같은 사진과 모델로 분류와
Grad-CAM을 직렬 실행한다. 설명 실패는 분류·추천 실패로 전파하지 않는다.

분류 응답의 선택 필드 `explanation`은 `available`, `method=grad_cam`,
`target_label`, `model_version`, `heatmap_png_base64`, `interpretation`,
`unavailable_reason`을 갖는다. Spring·Swift 응답은 camelCase다.
대상 라벨과 모델 버전은 원래 분류와 같아야 한다. 히트맵은 256×256 8-bit
grayscale PNG이며 원본 사진이나 원본을 섞은 overlay를 포함하지 않는다.
형식·크기·대상 검증에 실패한 선택형 설명은 버리고 기존 분류는 유지한다.

설명은 nullable JSONB `analyses.disease_explanation`에 분류와 함께 저장한다.
이미지·설문·응답 본문은 로그에 남기지 않는다. 얼굴 사진은 기존처럼 메모리에서만
처리하고 AI 호출 후 해제한다. 설명에는 원본 얼굴 픽셀을 저장하지 않는다.
분석 소유권·삭제·조회 규칙을 그대로 적용한다. 구버전 행은 설명이 null이다.
추천 재시도에서는 설명을 다시 만들거나 Qwen 입력에 보내지 않는다.
접수·상세 응답에는 저장한 설명을 포함하고 이력 목록에는 null로 내려 전송량을 줄인다.

앱은 결과 화면에 모델 분류에 영향을 준 영역을 표시한다. 밝은 영역을 병변의
정답·진단 근거·중증도·성분 효능으로 표현하지 않는다. unavailable/null이면
시각화를 표시하지 않거나 생성 불가 안내를 표시한다.

검증: 형식 검사·빌드·단위 154개·PostgreSQL/HTTP 통합 90개 통과.
별도 테스트 DB로 저장/조회, 타인 조회·삭제 404, 소유자 삭제, 구버전 행을 확인했다.
실제 Swift 통신 코드 → Spring → FastAPI 세 건에서도 분류·히트맵 보존과 보류/완료를 확인했다.
추천 완료 1건은 근거 카드 대체 응답이며 모델 원문 성공이나 임상 검증으로 세지 않는다.
이전 생성 출처 변경과 함께 AI → Spring(DB V3·V4) → Swift 순으로 적용한다.
