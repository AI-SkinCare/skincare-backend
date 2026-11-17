package com.aiskincare.backend.aiclient;

import com.aiskincare.backend.aiclient.AiModels.AiCallContext;
import com.aiskincare.backend.aiclient.AiModels.AiHealth;
import com.aiskincare.backend.aiclient.AiModels.ClassifyInput;
import com.aiskincare.backend.aiclient.AiModels.DiseaseRecommendRequest;
import com.aiskincare.backend.aiclient.AiModels.DiseaseRecommendResult;
import com.aiskincare.backend.aiclient.AiModels.DiseaseResult;

/**
 * AI_MODE=real → {@link HttpAiClient}, fixture → {@link FixtureAiClient}. 오류는 {@link AiError}로 던지고,
 * 백엔드 코드로의 변환은 호출하는 쪽이 {@link AiErrorMapper}로 한다 (TRD §12.1).
 */
public interface AiClient {
  AiHealth health();

  Object modelInfo();

  /** POST /infer/disease — 6종 이미지 분류 */
  DiseaseResult classify(ClassifyInput input, AiCallContext ctx);

  /** POST /infer/disease-recommend — 저장한 분류 + 설문 → 추천 또는 보류 */
  DiseaseRecommendResult recommend(DiseaseRecommendRequest request, AiCallContext ctx);
}
