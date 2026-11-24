package com.aiskincare.backend.knowledge;

import com.aiskincare.backend.aiclient.AiClient;
import com.aiskincare.backend.aiclient.AiError;
import com.aiskincare.backend.aiclient.AiErrorMapper;
import com.aiskincare.backend.common.error.ApiException;
import com.aiskincare.backend.common.web.ApiEnvelope;
import com.aiskincare.backend.knowledge.Knowledge.Answer;
import com.aiskincare.backend.knowledge.Knowledge.Query;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class KnowledgeController {
  private final AiClient ai;

  public KnowledgeController(AiClient ai) {
    this.ai = ai;
  }

  @PostMapping("/knowledge/search")
  public ApiEnvelope<Answer> search(@Valid @RequestBody Query query) {
    try {
      return ApiEnvelope.ok(ai.searchKnowledge(query));
    } catch (AiError error) {
      throw new ApiException(AiErrorMapper.recommend(error).code());
    }
  }
}
