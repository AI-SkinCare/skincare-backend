package com.aiskincare.backend.config;

import com.aiskincare.backend.common.error.ErrorCode;
import com.aiskincare.backend.common.web.ApiErrorCodes;
import com.aiskincare.backend.common.web.CurrentUser;
import com.aiskincare.backend.common.web.ErrorEnvelope;
import com.aiskincare.backend.common.web.PublicApi;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger: /api/docs, JSON /api/docs-json (prefix 밖). 앱이 이 JSON으로 타입을 만든다 — 봉투 구조·에러 응답·보안이 문서에 그대로
 * 나와야 한다 (TRD §7.2).
 */
@Configuration
public class OpenApiConfig {
  public static final String BEARER = "bearer";
  public static final String REFRESH_COOKIE = Constants.REFRESH_COOKIE_NAME;

  static {
    // @CurrentUser AuthUser는 인증 인터셉터가 넣는 값이지 요청 매개변수가 아니다
    SpringDocUtils.getConfig().addAnnotationsToIgnore(CurrentUser.class);
  }

  @Bean
  OpenAPI openApi() {
    Map<String, Schema> errorSchemas = ModelConverters.getInstance().readAll(ErrorEnvelope.class);
    Components components =
        new Components()
            .addSecuritySchemes(
                BEARER,
                new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT"))
            .addSecuritySchemes(
                REFRESH_COOKIE,
                new SecurityScheme()
                    .type(SecurityScheme.Type.APIKEY)
                    .in(SecurityScheme.In.COOKIE)
                    .name(Constants.REFRESH_COOKIE_NAME));
    errorSchemas.forEach(components::addSchemas);
    return new OpenAPI()
        .info(
            new Info()
                .title("skincare-backend API")
                .version("0.1.0")
                .description(
                    "피부 지표 측정·성분 추천 서비스 백엔드. 응답은 항상 `{ success, data }` / `{ success: false, error }`"
                        + " 봉투. 명세: skincare-ai/docs/project/03-api-spec.md, docs/TRD.md"))
        .servers(List.of(new Server().url("/").description("현재 서버")))
        .components(components);
  }

  /**
   * 작업마다: ① 보안 — @PublicApi면 security: [] (재발급은 쿠키), 나머지는 bearer ② 실패 응답 — @ApiErrorCodes + 인증 API의
   * E1001을 HTTP 상태별로 묶어 ErrorEnvelope로.
   */
  @Bean
  OperationCustomizer operationCustomizer() {
    return (operation, handlerMethod) -> {
      boolean isPublic =
          handlerMethod.hasMethodAnnotation(PublicApi.class)
              || handlerMethod.getBeanType().isAnnotationPresent(PublicApi.class);
      if (operation.getSecurity() == null || operation.getSecurity().isEmpty()) {
        operation.setSecurity(
            isPublic ? new ArrayList<>() : List.of(new SecurityRequirement().addList(BEARER)));
      }

      List<ErrorCode> codes = new ArrayList<>();
      if (!isPublic) codes.add(ErrorCode.E1001);
      ApiErrorCodes annotation = handlerMethod.getMethodAnnotation(ApiErrorCodes.class);
      if (annotation != null) codes.addAll(List.of(annotation.value()));
      Map<Integer, List<ErrorCode>> byStatus =
          codes.stream()
              .distinct()
              .collect(
                  Collectors.groupingBy(
                      ErrorCode::status, LinkedHashMap::new, Collectors.toList()));
      byStatus.forEach(
          (status, group) ->
              operation
                  .getResponses()
                  .addApiResponse(
                      String.valueOf(status),
                      new ApiResponse()
                          .description(
                              group.stream()
                                  .map(code -> code.name() + ": " + code.message())
                                  .collect(Collectors.joining(" / ")))
                          .content(
                              new Content()
                                  .addMediaType(
                                      "application/json",
                                      new MediaType()
                                          .schema(
                                              new Schema<>()
                                                  .$ref("#/components/schemas/ErrorEnvelope"))))));
      return operation;
    };
  }
}
