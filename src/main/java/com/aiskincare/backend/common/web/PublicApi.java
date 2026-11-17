package com.aiskincare.backend.common.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 인증 없이 부를 수 있는 API (가입·로그인·재발급·시스템 상태). 나머지는 모두 액세스 토큰이 필요하다. */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface PublicApi {}
