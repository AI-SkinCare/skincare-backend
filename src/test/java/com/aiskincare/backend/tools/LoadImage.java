package com.aiskincare.backend.tools;

import com.aiskincare.backend.support.TestImages;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** k6가 올릴 합성 이미지(1080×1440 PNG)를 만든다 — 실제 얼굴 사진은 레포에 두지 않는다. stub 측정에서만 통과한다. */
public final class LoadImage {
  private LoadImage() {}

  public static void main(String[] args) throws IOException {
    Path out = Path.of(args.length > 0 ? args[0] : "load/.tmp/face.png");
    Files.createDirectories(out.toAbsolutePath().getParent());
    Files.write(out, TestImages.png(1080, 1440));
    System.out.println(out + " (" + Files.size(out) + " bytes)");
  }
}
