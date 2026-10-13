package com.aiskincare.backend.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

/**
 * 요청 본문 타입을 엄격하게: 숫자를 문자열로, "true"를 불리언으로, 6.5를 정수로 슬쩍 바꾸지 않고 422 E4002로 거절한다. 모르는 필드는 무시한다(Spring
 * 기본값).
 */
@Configuration
public class JacksonConfig {
  @Bean
  JsonMapperBuilderCustomizer strictCoercion() {
    return builder ->
        builder
            .withCoercionConfig(
                LogicalType.Textual,
                cfg ->
                    cfg.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .withCoercionConfig(
                LogicalType.Boolean,
                cfg ->
                    cfg.setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail))
            .withCoercionConfig(
                LogicalType.Integer,
                cfg ->
                    cfg.setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.String, CoercionAction.Fail));
  }
}
