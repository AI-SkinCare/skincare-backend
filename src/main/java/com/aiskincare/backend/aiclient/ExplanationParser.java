package com.aiskincare.backend.aiclient;

import com.aiskincare.backend.aiclient.AiModels.Explanation;
import com.aiskincare.backend.common.enums.DiseaseLabel;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import tools.jackson.databind.JsonNode;

/** Optional attribution data is bounded and may not change the original classification. */
public final class ExplanationParser {
  private ExplanationParser() {}

  public static Explanation parse(JsonNode node, DiseaseLabel label, String modelVersion) {
    if (node == null || !node.isObject()) return null;
    JsonNode flag = node.get("available");
    String method = text(node, "method", 30);
    String target = text(node, "target_label", 30);
    String model = text(node, "model_version", 80);
    String interpretation = text(node, "interpretation", 500);
    if (flag == null
        || !flag.isBoolean()
        || !"grad_cam".equals(method)
        || !label.label().equals(target)
        || !modelVersion.equals(model)
        || interpretation == null) return null;
    boolean available = flag.booleanValue();
    String reason = text(node, "unavailable_reason", 40);
    String encoded = text(node, "heatmap_png_base64", 120_000);
    if (!available) {
      if (encoded != null
          || !Set.of("unsupported", "constant_map", "failed")
              .contains(reason == null ? "" : reason)) return null;
      return new Explanation(false, method, target, model, null, interpretation, reason);
    }
    if (reason != null || encoded == null || !validGrayscalePng(encoded)) return null;
    return new Explanation(true, method, target, model, encoded, interpretation, null);
  }

  private static String text(JsonNode node, String key, int limit) {
    JsonNode value = node.get(key);
    if (value == null || !value.isString()) return null;
    String text = value.stringValue();
    return !text.isBlank() && text.length() <= limit ? text : null;
  }

  static boolean validGrayscalePng(String encoded) {
    try {
      byte[] bytes = Base64.getDecoder().decode(encoded);
      byte[] signature = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
      if (bytes.length < 33 || bytes.length > 90_000) return false;
      for (int i = 0; i < signature.length; i++) if (bytes[i] != signature[i]) return false;
      // Validate dimensions before any decompression. Only server-created maps,
      // with 8-bit grayscale pixels and no original-image overlay, are accepted.
      if (bytes[12] != 'I'
          || bytes[13] != 'H'
          || bytes[14] != 'D'
          || bytes[15] != 'R'
          || java.nio.ByteBuffer.wrap(bytes, 16, 4).getInt() != 256
          || java.nio.ByteBuffer.wrap(bytes, 20, 4).getInt() != 256
          || bytes[24] != 8
          || bytes[25] != 0) return false;
      try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
        var readers = ImageIO.getImageReaders(input);
        if (!readers.hasNext()) return false;
        var reader = readers.next();
        try {
          reader.setInput(input);
          var image = reader.read(0);
          return image != null && image.getWidth() == 256 && image.getHeight() == 256;
        } finally {
          reader.dispose();
        }
      }
    } catch (IllegalArgumentException | IOException exception) {
      return false;
    }
  }
}
