package com.aiskincare.backend.aiclient;

import com.aiskincare.backend.common.util.Sleeper;
import com.aiskincare.backend.config.AppProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** 빈 하나(AiClient)로 real / fixture를 바꾼다 (TRD §12.1) */
@Configuration
public class AiClientConfig {

  @Bean
  Sleeper sleeper() {
    return Sleeper.REAL;
  }

  @Bean
  AiClient aiClient(AppProperties props, Sleeper sleeper, JsonMapper json) {
    if (props.aiReal()) {
      var ai = props.ai();
      return new HttpAiClient(
          new HttpAiClient.Options(
              ai.baseUrl(), ai.internalToken(), ai.skinTimeoutMs(), ai.recTimeoutMs()),
          json);
    }
    return new FixtureAiClient(props.defaultScenario(), sleeper, json);
  }
}
