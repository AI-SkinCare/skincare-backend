package com.aiskincare.backend.aiclient;

import com.aiskincare.backend.aiclient.AiModels.EvidenceSource;
import com.aiskincare.backend.aiclient.AiModels.IngredientEvidence;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Optional citations are kept in the saved ingredient row, bound to this generation. */
final class IngredientEvidenceParser {
  private IngredientEvidenceParser() {}

  static IngredientEvidence forIngredient(JsonNode recommendation, String name) {
    JsonNode bindings = recommendation.path("ingredient_evidence");
    if (!"source_linked".equals(text(recommendation.path("evidence_status")))
        || !bindings.isArray()) return null;
    JsonNode match = null;
    for (JsonNode row : bindings) {
      if (!name.equals(text(row.path("ingredient")))) continue;
      if (match != null) return null;
      match = row;
    }
    if (match == null) return null;
    String claim = text(match.path("claim_id"));
    String effect = text(match.path("effect"));
    String limitations = text(match.path("limitations"));
    if (claim == null
        || effect == null
        || limitations == null
        || !match.path("sources").isArray()
        || !match.path("usage_conditions").isArray()) return null;
    List<String> conditions = new ArrayList<>();
    for (JsonNode raw : match.path("usage_conditions")) {
      String condition = text(raw);
      if (condition == null) return null;
      conditions.add(condition);
    }
    List<EvidenceSource> sources = new ArrayList<>();
    for (JsonNode raw : match.path("sources")) {
      String title = text(raw.path("title"));
      String url = text(raw.path("url"));
      if (title == null || !isSourceUrl(url)) continue;
      sources.add(
          new EvidenceSource(title, url, text(raw.path("kind")), text(raw.path("summary"))));
    }
    if (sources.isEmpty() || conditions.isEmpty()) return null;
    return new IngredientEvidence(
        claim,
        text(recommendation.path("evidence_version")),
        effect,
        limitations,
        List.copyOf(conditions),
        List.copyOf(sources));
  }

  private static String text(JsonNode node) {
    return node.isString() && !node.stringValue().isBlank() ? node.stringValue() : null;
  }

  private static boolean isSourceUrl(String value) {
    if (value == null) return false;
    try {
      URI url = URI.create(value);
      return "https".equalsIgnoreCase(url.getScheme())
          && url.getHost() != null
          && url.getUserInfo() == null;
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }
}
