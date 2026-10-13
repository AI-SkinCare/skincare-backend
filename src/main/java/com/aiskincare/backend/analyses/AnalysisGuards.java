package com.aiskincare.backend.analyses;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 분석 제출·재요청 앞단 검사 (TRD §10.1 처리 순서 2–4): 요청 제한(E4004) → 대기열 상한(E3003) → AI 가용성(E3001, 재요청은 안 봄).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AnalysisGuards {
  boolean checkAiAvailability() default true;
}
