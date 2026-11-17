package com.aiskincare.backend.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.reflect.RecordComponent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

/**
 * OpenAPI 3.0에서는 $ref 옆의 nullable·description이 무시되고, swagger-core는 참조 속성의 {@code @Schema(nullable =
 * true)}를 버린다. 그래서 문서가 다 만들어진 뒤 우리 record 중 {@code @Schema(nullable = true)}인 참조 속성(trend의
 * previous·delta, 상세의 error)을 {@code { type: object, nullable: true, allOf: [$ref] }}로 바꾼다 — 앱 타입
 * 생성기가 Optional로 만들게.
 */
@Component
public class NullableRefCustomizer implements OpenApiCustomizer {
  private static final String BASE_PACKAGE = "com.aiskincare.backend";

  @Override
  public void customise(OpenAPI openApi) {
    if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) return;
    Map<String, Schema> schemas = openApi.getComponents().getSchemas();
    recordsByName()
        .forEach(
            (name, type) -> {
              Schema<?> schema = schemas.get(name);
              if (schema == null || schema.getProperties() == null) return;
              for (RecordComponent component : type.getRecordComponents()) {
                var annotation = annotationOf(type, component);
                Schema<?> property = schema.getProperties().get(component.getName());
                if (annotation == null
                    || !annotation.nullable()
                    || property == null
                    || property.get$ref() == null) {
                  continue;
                }
                Schema<Object> wrapped = new ObjectSchema();
                wrapped.setAllOf(List.of(new Schema<>().$ref(property.get$ref())));
                wrapped.setNullable(true);
                if (!annotation.description().isEmpty())
                  wrapped.setDescription(annotation.description());
                schema.getProperties().put(component.getName(), wrapped);
              }
            });
  }

  private static io.swagger.v3.oas.annotations.media.Schema annotationOf(
      Class<?> type, RecordComponent component) {
    try {
      return type.getDeclaredField(component.getName())
          .getAnnotation(io.swagger.v3.oas.annotations.media.Schema.class);
    } catch (NoSuchFieldException e) {
      return null;
    }
  }

  /** 우리 패키지의 record (중첩 포함) — 문서 스키마 이름(@Schema(name) 또는 클래스 이름) → 클래스 */
  private static Map<String, Class<?>> recordsByName() {
    var scanner =
        new ClassPathScanningCandidateComponentProvider(false) {
          @Override
          protected boolean isCandidateComponent(
              org.springframework.beans.factory.annotation.AnnotatedBeanDefinition definition) {
            return true;
          }
        };
    scanner.addIncludeFilter(new AssignableTypeFilter(Record.class));
    Map<String, Class<?>> out = new HashMap<>();
    for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
      Class<?> type =
          ClassUtils.resolveClassName(
              definition.getBeanClassName(), NullableRefCustomizer.class.getClassLoader());
      var named = type.getAnnotation(io.swagger.v3.oas.annotations.media.Schema.class);
      out.put(named != null && !named.name().isEmpty() ? named.name() : type.getSimpleName(), type);
    }
    return out;
  }
}
