package com.aiskincare.backend.analyses;

/**
 * 확장자·mimetype이 아니라 파일 시그니처로 판별하고, 헤더에서 가로·세로만 읽는다 (TRD §10.1). 디코딩·임시 파일 없음 — 얼굴 이미지는 디스크에 닿지 않는다.
 * JPEG {@code FF D8 FF}, PNG {@code 89 50 4E 47}. HEIC·GIF 등은 null → 415 E2004.
 */
public final class ImageInspector {
  private ImageInspector() {}

  public record Dimensions(int width, int height) {
    public int shortSide() {
      return Math.min(width, height);
    }
  }

  public static String detectMimeType(byte[] b) {
    if (b == null) return null;
    if (b.length >= 3 && u8(b, 0) == 0xFF && u8(b, 1) == 0xD8 && u8(b, 2) == 0xFF) {
      return "image/jpeg";
    }
    if (b.length >= 4
        && u8(b, 0) == 0x89
        && u8(b, 1) == 0x50
        && u8(b, 2) == 0x4E
        && u8(b, 3) == 0x47) {
      return "image/png";
    }
    return null;
  }

  /** 읽지 못하면 null */
  public static Dimensions dimensions(byte[] b, String mimeType) {
    if ("image/png".equals(mimeType)) return png(b);
    if ("image/jpeg".equals(mimeType)) return jpeg(b);
    return null;
  }

  /** IHDR는 항상 첫 청크: 8바이트 시그니처 + 길이(4) + "IHDR"(4) + 가로(4) + 세로(4) */
  private static Dimensions png(byte[] b) {
    if (b.length < 24 || b[12] != 'I' || b[13] != 'H' || b[14] != 'D' || b[15] != 'R') return null;
    int width = u32(b, 16);
    int height = u32(b, 20);
    return width > 0 && height > 0 ? new Dimensions(width, height) : null;
  }

  /** SOFn 마커까지 세그먼트를 건너뛴다. 스캔(SOS)이나 끝(EOI)을 먼저 만나면 null */
  private static Dimensions jpeg(byte[] b) {
    int i = 2;
    while (i + 3 < b.length) {
      if (u8(b, i) != 0xFF) return null;
      int marker = u8(b, i + 1);
      if (marker == 0xFF) {
        i++; // 채움 바이트
        continue;
      }
      if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
        i += 2; // 길이 없는 마커
        continue;
      }
      if (marker == 0xD9 || marker == 0xDA) return null;
      boolean sof =
          marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
      if (sof) {
        if (i + 8 >= b.length) return null;
        int height = u16(b, i + 5);
        int width = u16(b, i + 7);
        return width > 0 && height > 0 ? new Dimensions(width, height) : null;
      }
      int length = u16(b, i + 2);
      if (length < 2) return null;
      i += 2 + length;
    }
    return null;
  }

  private static int u8(byte[] b, int i) {
    return b[i] & 0xFF;
  }

  private static int u16(byte[] b, int i) {
    return (u8(b, i) << 8) | u8(b, i + 1);
  }

  private static int u32(byte[] b, int i) {
    return (u8(b, i) << 24) | (u8(b, i + 1) << 16) | (u8(b, i + 2) << 8) | u8(b, i + 3);
  }
}
