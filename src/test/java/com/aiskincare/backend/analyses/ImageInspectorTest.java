package com.aiskincare.backend.analyses;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiskincare.backend.analyses.ImageInspector.Dimensions;
import com.aiskincare.backend.support.TestImages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TRD §10.1 — 형식은 확장자·mimetype이 아니라 시그니처로 */
class ImageInspectorTest {
  @Test
  @DisplayName("JPEG FF D8 FF / PNG 89 50 4E 47")
  void signatures() {
    assertThat(ImageInspector.detectMimeType(TestImages.headerOnlyJpeg(10, 10)))
        .isEqualTo("image/jpeg");
    assertThat(ImageInspector.detectMimeType(TestImages.png(10, 10))).isEqualTo("image/png");
  }

  @Test
  @DisplayName("GIF·HEIC·빈 버퍼는 null → 415 E2004")
  void unsupported() {
    assertThat(ImageInspector.detectMimeType(TestImages.gif())).isNull();
    assertThat(ImageInspector.detectMimeType(TestImages.heic())).isNull();
    assertThat(ImageInspector.detectMimeType(new byte[0])).isNull();
  }

  @Test
  @DisplayName("헤더에서 가로·세로만 읽는다")
  void dimensions() {
    assertThat(ImageInspector.dimensions(TestImages.headerOnlyJpeg(1080, 1440), "image/jpeg"))
        .isEqualTo(new Dimensions(1080, 1440));
    assertThat(ImageInspector.dimensions(TestImages.png(600, 720), "image/png"))
        .isEqualTo(new Dimensions(600, 720));
    assertThat(
            ImageInspector.dimensions(
                new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}, "image/jpeg"))
        .isNull();
  }
}
