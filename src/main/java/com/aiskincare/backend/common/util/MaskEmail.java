package com.aiskincare.backend.common.util;

/** TRD §7.6 — {@code dohee@gmail.com} → {@code d***@gmail.com} */
public final class MaskEmail {
  private MaskEmail() {}

  public static String mask(String email) {
    if (email == null) return "***";
    int at = email.lastIndexOf('@');
    if (at <= 0) return "***";
    return email.charAt(0) + "***" + email.substring(at);
  }
}
