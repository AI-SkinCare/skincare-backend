package com.aiskincare.backend.aiclient;

import com.aiskincare.backend.aiclient.AiError.Issue;
import com.aiskincare.backend.aiclient.AiModels.AiHealth;
import com.aiskincare.backend.aiclient.AiModels.Ingredient;
import com.aiskincare.backend.aiclient.AiModels.MetricRatios;
import com.aiskincare.backend.aiclient.AiModels.RecommendResult;
import com.aiskincare.backend.aiclient.AiModels.RoutineStep;
import com.aiskincare.backend.aiclient.AiModels.SkinMetricsResult;
import com.aiskincare.backend.common.enums.Concern;
import com.aiskincare.backend.common.enums.Metric;
import com.aiskincare.backend.common.enums.Roi;
import com.aiskincare.backend.common.enums.RoutineTime;
import com.aiskincare.backend.common.enums.SkinType;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * TRD §12.3·12.4 — AI 응답 검증. AI 출력은 그대로 믿지 않는다 (설계 원칙 5). 위반은 경로와 이유만 모으고 값은 남기지 않는다. 라벨은 여기서 코드로
 * 바꾼다.
 */
public final class AiResponseParser {
  private AiResponseParser() {}

  private static final int MODEL_VERSION_MAX = 40;

  /** POST /infer/skin-metrics 응답. ROI 이름·0–1 범위·빈 metrics 검사 */
  public static SkinMetricsResult skinMetrics(JsonNode body) {
    Checker c = new Checker();
    JsonNode root = c.root(body);

    List<Roi> detected = new ArrayList<>();
    JsonNode detectedNode = c.array(root.get("roi_detected"), "roi_detected");
    if (detectedNode != null) {
      int i = 0;
      for (JsonNode item : detectedNode) {
        String path = "roi_detected[" + i++ + "]";
        String name = c.string(item, path, null);
        if (name == null) continue;
        Roi roi = Roi.fromCode(name);
        if (roi == null) c.fail(path, "unknown_roi");
        else detected.add(roi);
      }
    }

    Map<Roi, MetricRatios> metrics = new EnumMap<>(Roi.class);
    JsonNode metricsNode = c.object(root.get("metrics"), "metrics");
    if (metricsNode != null) {
      if (metricsNode.size() == 0) c.fail("metrics", "empty");
      for (String name : metricsNode.propertyNames()) {
        Roi roi = Roi.fromCode(name);
        if (roi == null) {
          c.fail("metrics." + name, "unknown_roi");
          continue;
        }
        JsonNode values = c.object(metricsNode.get(name), "metrics." + name);
        if (values == null) continue;
        double[] v = new double[4];
        boolean valid = true;
        for (Metric metric : Metric.values()) {
          Double value =
              c.number(
                  values.get(metric.code()), "metrics." + name + "." + metric.code(), 0.0, 1.0);
          if (value == null) valid = false;
          else v[metric.ordinal()] = value;
        }
        if (valid) metrics.put(roi, new MetricRatios(v[0], v[1], v[2], v[3]));
      }
    }

    Double confidence = c.number(root.get("confidence"), "confidence", 0.0, 1.0);
    Boolean calibration = c.bool(root.get("calibration_applied"), "calibration_applied");
    Boolean isStub = c.bool(root.get("is_stub"), "is_stub");
    String modelVersion = c.string(root.get("model_version"), "model_version", MODEL_VERSION_MAX);
    Double inferenceMs = c.number(root.get("inference_ms"), "inference_ms", 0.0, null);
    c.throwIfInvalid();
    return new SkinMetricsResult(
        detected,
        metrics,
        confidence,
        calibration,
        isStub,
        modelVersion,
        (int) Math.round(inferenceMs));
  }

  /**
   * POST /infer/recommend 응답 (TRD §12.4 표). 열거형·타입 위반은 invalid_response(→ failed E4001 + ERROR), 개수
   * 위반(ingredients > 5, concerns > 2, reasoning ≠ 3)은 WARN만 남기고 저장한다.
   */
  public static RecommendResult recommend(JsonNode body) {
    Checker c = new Checker();
    JsonNode root = c.root(body);
    List<String> warnings = new ArrayList<>();

    SkinType skinType = null;
    String skinTypeValue = c.string(root.get("skin_type"), "skin_type", null);
    if (skinTypeValue != null) {
      skinType = SkinType.fromLabel(skinTypeValue);
      if (skinType == null) skinType = SkinType.fromCode(skinTypeValue); // 영문 코드도 인식
      if (skinType == null) c.fail("skin_type", "unknown_skin_type");
    }

    List<Concern> concerns = new ArrayList<>();
    List<String> concernLabels = c.stringArray(root.get("concerns"), "concerns");
    if (concernLabels != null) {
      for (int i = 0; i < concernLabels.size(); i++) {
        Concern concern = Concern.fromLabel(concernLabels.get(i));
        if (concern == null) c.fail("concerns[" + i + "]", "unknown_concern");
        else if (!concerns.contains(concern)) concerns.add(concern); // 중복 고민은 하나로
      }
      if (concernLabels.size() > 2) warnings.add("concerns_count:" + concernLabels.size());
    }

    List<Ingredient> ingredients = new ArrayList<>();
    JsonNode ingredientsNode = c.array(root.get("ingredients"), "ingredients");
    if (ingredientsNode != null) {
      int i = 0;
      for (JsonNode raw : ingredientsNode) {
        String path = "ingredients[" + i++ + "]";
        JsonNode item = c.object(raw, path);
        if (item == null) continue;
        String name = c.string(item.get("name"), path + ".name", null);
        JsonNode purposeNode = item.get("purpose");
        String purpose =
            purposeNode == null || purposeNode.isNull()
                ? null
                : c.string(purposeNode, path + ".purpose", null);
        boolean purposeOk = purposeNode == null || purposeNode.isNull() || purpose != null;
        Integer priority = c.integer(item.get("priority"), path + ".priority");
        if (name != null && purposeOk && priority != null) {
          ingredients.add(new Ingredient(name, purpose, priority));
        }
      }
      if (ingredients.size() > 5) warnings.add("ingredients_count:" + ingredients.size());
    }

    List<RoutineStep> routine = new ArrayList<>();
    JsonNode routineNode = c.array(root.get("routine"), "routine");
    if (routineNode != null) {
      int i = 0;
      for (JsonNode raw : routineNode) {
        String path = "routine[" + i++ + "]";
        JsonNode item = c.object(raw, path);
        if (item == null) continue;
        Integer step = c.integer(item.get("step"), path + ".step");
        String timeValue = c.string(item.get("time"), path + ".time", null);
        RoutineTime time = timeValue == null ? null : RoutineTime.fromCode(timeValue);
        if (timeValue != null && time == null) c.fail(path + ".time", "unknown_routine_time");
        String action = c.string(item.get("action"), path + ".action", null);
        if (step != null && time != null && action != null) {
          routine.add(new RoutineStep(step, time, action));
        }
      }
    }

    List<String> reasoning = c.stringArray(root.get("reasoning"), "reasoning");
    if (reasoning != null && reasoning.size() != 3) {
      warnings.add("reasoning_count:" + reasoning.size());
    }
    String answer = c.string(root.get("answer"), "answer", null);
    String disclaimer = c.string(root.get("disclaimer"), "disclaimer", null);
    Boolean filtered = c.bool(root.get("filtered"), "filtered");
    Boolean usedFallback = c.bool(root.get("used_fallback"), "used_fallback");
    JsonNode notesNode = root.get("input_notes");
    List<String> inputNotes =
        notesNode == null || notesNode.isNull()
            ? List.of()
            : c.stringArray(notesNode, "input_notes");
    String modelVersion = c.string(root.get("model_version"), "model_version", MODEL_VERSION_MAX);
    Double inferenceMs = c.number(root.get("inference_ms"), "inference_ms", 0.0, null);
    c.throwIfInvalid();

    return new RecommendResult(
        skinType,
        List.copyOf(concerns),
        List.copyOf(ingredients),
        List.copyOf(routine),
        List.copyOf(reasoning),
        answer,
        disclaimer,
        filtered,
        usedFallback,
        List.copyOf(inputNotes),
        modelVersion,
        (int) Math.round(inferenceMs),
        List.copyOf(warnings));
  }

  /** GET /health */
  public static AiHealth health(JsonNode body) {
    Checker c = new Checker();
    JsonNode root = c.root(body);
    String status = c.string(root.get("status"), "status", null);
    c.throwIfInvalid();
    JsonNode skin = root.get("skin_backend");
    return new AiHealth(status, skin != null && skin.isString() ? skin.stringValue() : null);
  }

  /** 필드마다 확인하며 위반을 모은다 */
  private static final class Checker {
    private final List<Issue> issues = new ArrayList<>();

    JsonNode root(JsonNode body) {
      if (body != null && body.isObject()) return body;
      issues.add(new Issue("$", "not_object"));
      throw AiError.invalid(issues);
    }

    void fail(String path, String reason) {
      issues.add(new Issue(path, reason));
    }

    JsonNode object(JsonNode node, String path) {
      if (node != null && node.isObject()) return node;
      fail(path, "not_object");
      return null;
    }

    JsonNode array(JsonNode node, String path) {
      if (node != null && node.isArray()) return node;
      fail(path, "not_array");
      return null;
    }

    String string(JsonNode node, String path, Integer maxLength) {
      if (node == null || !node.isString()) {
        fail(path, "not_string");
        return null;
      }
      String value = node.stringValue();
      if (maxLength != null && value.length() > maxLength) {
        fail(path, "too_long");
        return null;
      }
      return value;
    }

    Boolean bool(JsonNode node, String path) {
      if (node != null && node.isBoolean()) return node.booleanValue();
      fail(path, "not_boolean");
      return null;
    }

    Double number(JsonNode node, String path, Double min, Double max) {
      if (node == null || !node.isNumber() || !Double.isFinite(node.doubleValue())) {
        fail(path, "not_number");
        return null;
      }
      double value = node.doubleValue();
      if ((min != null && value < min) || (max != null && value > max)) {
        fail(path, "out_of_range");
        return null;
      }
      return value;
    }

    Integer integer(JsonNode node, String path) {
      Double value = number(node, path, null, null);
      if (value == null) return null;
      if (value % 1 != 0) {
        fail(path, "not_integer");
        return null;
      }
      return value.intValue();
    }

    List<String> stringArray(JsonNode node, String path) {
      JsonNode array = array(node, path);
      if (array == null) return null;
      int before = issues.size();
      List<String> result = new ArrayList<>();
      int i = 0;
      for (JsonNode item : array) result.add(string(item, path + "[" + i++ + "]", null));
      return issues.size() == before ? result : null;
    }

    void throwIfInvalid() {
      if (!issues.isEmpty()) throw AiError.invalid(issues);
    }
  }
}
