package com.aiskincare.backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Locale;

public record LoginRequest(
    @Schema(example = "user@example.com") @NotBlank @Size(max = 255) String email,
    @Schema(example = "Skin!2026") @NotBlank @Size(max = 255) String password) {

  public LoginRequest {
    email = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
  }
}
