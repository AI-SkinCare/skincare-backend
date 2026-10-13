package com.aiskincare.backend.aiclient;

import com.aiskincare.backend.aiclient.AiModels.AiCallContext;
import com.aiskincare.backend.aiclient.AiModels.AiHealth;
import com.aiskincare.backend.aiclient.AiModels.MeasureSkinInput;
import com.aiskincare.backend.aiclient.AiModels.RecommendRequest;
import com.aiskincare.backend.aiclient.AiModels.RecommendResult;
import com.aiskincare.backend.aiclient.AiModels.SkinMetricsResult;

/**
 * AI_MODE=real → {@link HttpAiClient}, fixture → {@link FixtureAiClient}. 오류는 {@link AiError}로 던지고,
 * 백엔드 코드로의 변환은 호출하는 쪽이 {@link AiErrorMapper}로 한다 (TRD §12.1).
 */
public interface AiClient {
  AiHealth health();

  Object modelInfo();

  SkinMetricsResult measureSkin(MeasureSkinInput input, AiCallContext ctx);

  RecommendResult recommend(RecommendRequest request, AiCallContext ctx);
}
