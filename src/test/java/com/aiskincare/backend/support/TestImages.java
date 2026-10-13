package com.aiskincare.backend.support;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.imageio.ImageIO;

/** 테스트 이미지는 코드로 합성한다 — 실제 얼굴 사진을 레포에 넣지 않는다 (NFR-DAT-01). */
public final class TestImages {
  private TestImages() {}

  static {
    // 디스크 캐시 파일을 만들지 않는다
    ImageIO.setUseCache(false);
  }

  /** 디코딩 가능한 회색 PNG. marker를 주면 끝에 붙여 디스크 잔존 검사(TC-PRI-01)에 쓴다 */
  public static byte[] png(int width, int height, String marker) {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
    try (var out = new ByteArrayOutputStream()) {
      ImageIO.write(image, "png", out);
      byte[] png = out.toByteArray();
      if (marker == null) return png;
      byte[] tail = marker.getBytes(StandardCharsets.UTF_8);
      byte[] withMarker = Arrays.copyOf(png, png.length + tail.length);
      System.arraycopy(tail, 0, withMarker, png.length, tail.length);
      return withMarker;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static byte[] png(int width, int height) {
    return png(width, height, null);
  }

  /**
   * 헤더만 있는 JPEG (SOI + APP0 + SOF0 + EOI). 백엔드는 디코딩하지 않고 시그니처와 가로·세로만 읽으므로 충분하다. padTo를 주면 그 크기까지
   * 0으로 채운다.
   */
  public static byte[] headerOnlyJpeg(int width, int height, int padTo) {
    byte[] header = {
      (byte) 0xFF,
      (byte) 0xD8, // SOI
      (byte) 0xFF,
      (byte) 0xE0,
      0x00,
      0x10,
      'J',
      'F',
      'I',
      'F',
      0x00,
      0x01,
      0x01,
      0x00,
      0x00,
      0x01,
      0x00,
      0x01,
      0x00,
      0x00, // APP0
      (byte) 0xFF,
      (byte) 0xC0,
      0x00,
      0x11,
      0x08,
      (byte) (height >> 8),
      (byte) height,
      (byte) (width >> 8),
      (byte) width,
      0x03,
      0x01,
      0x22,
      0x00,
      0x02,
      0x11,
      0x01,
      0x03,
      0x11,
      0x01,
      (byte) 0xFF,
      (byte) 0xD9 // EOI
    };
    return padTo > header.length ? Arrays.copyOf(header, padTo) : header;
  }

  public static byte[] headerOnlyJpeg(int width, int height) {
    return headerOnlyJpeg(width, height, 0);
  }

  public static byte[] gif() {
    return Arrays.copyOf("GIF89a".getBytes(StandardCharsets.US_ASCII), 38);
  }

  /** iPhone 기본 형식 — 백엔드는 변환하지 않고 415 E2004 */
  public static byte[] heic() {
    byte[] out = new byte[44];
    out[3] = 0x18;
    System.arraycopy("ftypheic".getBytes(StandardCharsets.US_ASCII), 0, out, 4, 8);
    return out;
  }
}
