package com.aiskincare.backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record SignupResponse(@Schema(example = "42") long userId) {}
