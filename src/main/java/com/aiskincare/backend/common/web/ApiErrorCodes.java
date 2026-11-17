package com.aiskincare.backend.common.web;

import com.aiskincare.backend.common.error.ErrorCode;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Swagger에 실패 봉투 응답을 코드별로 적는다 (같은 HTTP 상태는 한 응답으로 묶음). 인증 API는 E1001이 자동으로 붙는다. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiErrorCodes {
  ErrorCode[] value();
}
