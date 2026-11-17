package com.aiskincare.backend.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** IP당 분당 LOGIN_RATE_PER_MIN회 제한 (TRD §9.3) */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface LoginRateLimited {}
