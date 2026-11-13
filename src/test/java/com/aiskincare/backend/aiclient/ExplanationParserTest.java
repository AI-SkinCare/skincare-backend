package com.aiskincare.backend.aiclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiskincare.backend.analyses.AnalysisPresenter;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class ExplanationParserTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  public static String syntheticMap(boolean color) throws IOException {
    var image =
        new BufferedImage(
            256, 256, color ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_BYTE_GRAY);
    for (int x = 0; x < 256; x++) for (int y = 0; y < 256; y++) image.setRGB(x, y, x * 0x010101);
    var output = new ByteArrayOutputStream();
    ImageIO.write(image, "PNG", output);
    return Base64.getEncoder().encodeToString(output.toByteArray());
  }

  private ObjectNode disease() throws IOException {
    try (var input =
        new ClassPathResource("fixtures/ai/disease-pipeline/generated.response.json")
            .getInputStream()) {
      var root = (ObjectNode) JSON.readTree(input).get("disease");
      var explanation = root.putObject("explanation");
      explanation
          .put("available", true)
          .put("method", "grad_cam")
          .put("target_label", root.get("label").stringValue())
          .put("model_version", root.get("model_version").stringValue())
          .put("heatmap_png_base64", syntheticMap(false))
          .put("interpretation", "모델 분류에 영향을 준 참고 영역")
          .put("overlay_png_base64", "untrusted-face-image-must-not-be-stored");
      return root;
    }
  }

  @Test
  void validatedMapReachesAppButIsNotSentToQwen() throws Exception {
    var result = AiResponseParser.disease(disease());
    assertThat(result.explanation().available()).isTrue();
    assertThat(AnalysisPresenter.disease(result).explanation()).isEqualTo(result.explanation());
    assertThat(AiTransform.toAiDisease(result)).doesNotContainKey("explanation");
    assertThat(JSON.writeValueAsString(result.explanation()))
        .doesNotContain("untrusted-face", "overlay");
  }

  @Test
  void corruptOptionalMetadataDoesNotRejectTheOriginalClassification() throws Exception {
    for (String field : new String[] {"target_label", "model_version", "method"}) {
      var root = disease();
      ((ObjectNode) root.get("explanation")).put(field, "mismatch");
      var result = AiResponseParser.disease(root);
      assertThat(result.explanation()).isNull();
      assertThat(result.label().label()).isEqualTo(root.get("label").stringValue());
    }
  }

  @Test
  void oversizedAndColorImagePayloadsAreDiscarded() throws Exception {
    for (String encoded : new String[] {syntheticMap(true), "x".repeat(120_001), "not-base64"}) {
      var root = disease();
      ((ObjectNode) root.get("explanation")).put("heatmap_png_base64", encoded);
      assertThat(AiResponseParser.disease(root).explanation()).isNull();
    }
  }

  @Test
  void unavailableMapAndLegacyResponseRemainUsable() throws Exception {
    var root = disease();
    var explanation = (ObjectNode) root.get("explanation");
    explanation
        .put("available", false)
        .put("unavailable_reason", "constant_map")
        .putNull("heatmap_png_base64");
    assertThat(AiResponseParser.disease(root).explanation().available()).isFalse();
    root.remove("explanation");
    assertThat(AiResponseParser.disease(root).explanation()).isNull();
  }
}
