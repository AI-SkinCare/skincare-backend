package com.aiskincare.backend.common.util;

import java.security.SecureRandom;
import java.util.regex.Pattern;

/** 분석 ID — ULID 26자 (48비트 시각 + 80비트 난수, Crockford Base32). */
public final class Ulid {
  private Ulid() {}

  private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
  private static final Pattern PATTERN = Pattern.compile("^[0-9A-HJKMNP-TV-Z]{26}$");
  private static final SecureRandom RANDOM = new SecureRandom();

  public static boolean isValid(String value) {
    return value != null && PATTERN.matcher(value).matches();
  }

  public static String generate() {
    return generate(System.currentTimeMillis());
  }

  public static String generate(long epochMillis) {
    char[] out = new char[26];
    long time = epochMillis;
    for (int i = 9; i >= 0; i--) {
      out[i] = ALPHABET[(int) (time & 31)];
      time >>>= 5;
    }
    byte[] random = new byte[10];
    RANDOM.nextBytes(random);
    // 80비트를 5비트씩 16글자로
    long high = 0;
    for (int i = 0; i < 5; i++) high = (high << 8) | (random[i] & 0xff);
    long low = 0;
    for (int i = 5; i < 10; i++) low = (low << 8) | (random[i] & 0xff);
    for (int i = 0; i < 8; i++) out[17 - i] = ALPHABET[(int) ((high >>> (5 * i)) & 31)];
    for (int i = 0; i < 8; i++) out[25 - i] = ALPHABET[(int) ((low >>> (5 * i)) & 31)];
    return new String(out);
  }
}
