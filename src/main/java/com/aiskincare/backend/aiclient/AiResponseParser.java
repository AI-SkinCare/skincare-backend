package com.aiskincare.backend.aiclient;

import com.aiskincare.backend.aiclient.AiError.Issue;
import com.aiskincare.backend.aiclient.AiModels.AiHealth;
import com.aiskincare.backend.aiclient.AiModels.DiseaseRecommendResult;
import com.aiskincare.backend.aiclient.AiModels.DiseaseResult;
import com.aiskincare.backend.aiclient.AiModels.Ingredient;
import com.aiskincare.backend.aiclient.AiModels.RecommendResult;
import com.aiskincare.backend.aiclient.AiModels.RoutineStep;
import com.aiskincare.backend.common.enums.Concern;
import com.aiskincare.backend.common.enums.DiseaseLabel;
import com.aiskincare.backend.common.enums.RecommendationStatus;
import com.aiskincare.backend.common.enums.RoutineTime;
import com.aiskincare.backend.common.enums.SkinType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * TRD §12.3·12.4 — AI 응답 검증. AI 출력은 그대로 믿지 않는다 (설계 원칙 5). 위반은 경로와 이유만 모으고 값은 남기지 않는다. 라벨은 여기서 코드로
 * 바꾼다.
 */
public final class AiResponseParser {
  private AiResponseParser() {}

  /** recommendations.model_version */
  private static final int MODEL_VERSION_MAX = 40;

  /** analyses.disease_model_version — 시험용 분류 fixture는 47자 */
  private static final int DISEASE_MODEL_VERSION_MAX = 80;

  /**
   * POST /infer/disease 응답. 라벨·코드가 표에 있고 짝이 맞는지, 점수가 0–1인지, 확률에 6종 키가 모두(그 밖의 키 없이) 있는지 본다. 확률 합·최고
   * 점수 일치는 AI가 추천 요청 때 검증하므로 다시 보지 않는다.
   */
  public static DiseaseResult disease(JsonNode body) {
    Checker c = new Checker();
    JsonNode root = c.root(body);

    String labelValue = c.string(root.get("label"), "label", null);
    DiseaseLabel byLabel = labelValue == null ? null : DiseaseLabel.fromLabel(labelValue);
    if (labelValue != null && byLabel == null) c.fail("label", "unknown_label");
    String codeValue = c.string(root.get("label_en"), "label_en", null);
    DiseaseLabel label = codeValue == null ? null : DiseaseLabel.fromCode(codeValue);
    if (codeValue != null && label == null) c.fail("label_en", "unknown_label");
    if (byLabel != null && label != null && byLabel != label) c.fail("label_en", "label_mismatch");

    Double confidence = c.number(root.get("confidence"), "confidence", 0.0, 1.0);
    Boolean uncertain = c.bool(root.get("uncertain"), "uncertain");

    Map<String, Double> probabilities = new LinkedHashMap<>();
    JsonNode probabilitiesNode = c.object(root.get("probabilities"), "probabilities");
    if (probabilitiesNode != null) {
      for (DiseaseLabel each : DiseaseLabel.values()) {
        Double value =
            c.number(probabilitiesNode.get(each.label()), "probabilities." + each.code(), 0.0, 1.0);
        if (value != null) probabilities.put(each.label(), value);
      }
      for (String name : probabilitiesNode.propertyNames()) {
        if (DiseaseLabel.fromLabel(name) == null) c.fail("probabilities", "unknown_label");
      }
    }

    String modelVersion =
        c.string(root.get("model_version"), "model_version", DISEASE_MODEL_VERSION_MAX);
    Double inferenceMs = c.number(root.get("inference_ms"), "inference_ms", 0.0, null);
    c.throwIfInvalid();
    return new DiseaseResult(
        label,
        confidence,
        uncertain,
        Collections.unmodifiableMap(probabilities),
        modelVersion,
        (int) Math.round(inferenceMs));
  }

  /**
   * POST /infer/disease-recommend 응답 (TRD §12.4 표). generated면 recommendation 객체,
   * 보류(uncertain·no_concern)면 null이어야 한다. 되돌려 준 disease는 쓰지 않는다 — 저장한 분류가 기준.
   */
  public static DiseaseRecommendResult diseaseRecommend(JsonNode body) {
    Checker c = new Checker();
    JsonNode root = c.root(body);

    String statusValue = c.string(root.get("recommendation_status"), "recommendation_status", null);
    RecommendationStatus status =
        statusValue == null ? null : RecommendationStatus.fromCode(statusValue);
    if (statusValue != null && status == null) c.fail("recommendation_status", "unknown_status");

    JsonNode recommendationNode = root.get("recommendation");
    RecommendResult recommendation = null;
    if (status == RecommendationStatus.GENERATED) {
      JsonNode item = c.object(recommendationNode, "recommendation");
      if (item != null) recommendation = recommendation(c, item, "recommendation.");
    } else if (status != null && recommendationNode != null && !recommendationNode.isNull()) {
      c.fail("recommendation", "not_null");
    }

    List<String> guidance = c.stringArray(root.get("guidance"), "guidance");
    c.throwIfInvalid();
    return new DiseaseRecommendResult(status, recommendation, List.copyOf(guidance));
  }

  /**
   * 추천 객체. 열거형·타입 위반은 invalid_response(→ FAILED E4001 + ERROR), 개수 위반(ingredients > 5, concerns >
   * 2, reasoning ≠ 3)은 WARN만 남기고 저장한다. 위반이 있으면 null — 호출한 쪽이 곧 throwIfInvalid로 던진다.
   */
  private static RecommendResult recommendation(Checker c, JsonNode root, String p) {
    int before = c.issueCount();
    List<String> warnings = new ArrayList<>();

    SkinType skinType = null;
    String skinTypeValue = c.string(root.get("skin_type"), p + "skin_type", null);
    if (skinTypeValue != null) {
      skinType = SkinType.fromLabel(skinTypeValue);
      if (skinType == null) skinType = SkinType.fromCode(skinTypeValue); // 영문 코드도 인식
      if (skinType == null) c.fail(p + "skin_type", "unknown_skin_type");
    }

    List<Concern> concerns = new ArrayList<>();
    List<String> concernLabels = c.stringArray(root.get("concerns"), p + "concerns");
    if (concernLabels != null) {
      for (int i = 0; i < concernLabels.size(); i++) {
        Concern concern = Concern.fromLabel(concernLabels.get(i));
        if (concern == null) c.fail(p + "concerns[" + i + "]", "unknown_concern");
        else if (!concerns.contains(concern)) concerns.add(concern); // 중복 고민은 하나로
      }
      if (concernLabels.size() > 2) warnings.add("concerns_count:" + concernLabels.size());
    }

    List<Ingredient> ingredients = new ArrayList<>();
    JsonNode ingredientsNode = c.array(root.get("ingredients"), p + "ingredients");
    if (ingredientsNode != null) {
      int i = 0;
      for (JsonNode raw : ingredientsNode) {
        String path = p + "ingredients[" + i++ + "]";
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
    JsonNode routineNode = c.array(root.get("routine"), p + "routine");
    if (routineNode != null) {
      int i = 0;
      for (JsonNode raw : routineNode) {
        String path = p + "routine[" + i++ + "]";
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

    List<String> reasoning = c.stringArray(root.get("reasoning"), p + "reasoning");
    if (reasoning != null && reasoning.size() != 3) {
      warnings.add("reasoning_count:" + reasoning.size());
    }
    String answer = c.string(root.get("answer"), p + "answer", null);
    String disclaimer = c.string(root.get("disclaimer"), p + "disclaimer", null);
    Boolean filtered = c.bool(root.get("filtered"), p + "filtered");
    Boolean usedFallback = c.bool(root.get("used_fallback"), p + "used_fallback");
    JsonNode notesNode = root.get("input_notes");
    List<String> inputNotes =
        notesNode == null || notesNode.isNull()
            ? List.of()
            : c.stringArray(notesNode, p + "input_notes");
    String modelVersion =
        c.string(root.get("model_version"), p + "model_version", MODEL_VERSION_MAX);
    Double inferenceMs = c.number(root.get("inference_ms"), p + "inference_ms", 0.0, null);
    if (c.issueCount() > before) return null;

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

  /** GET /health — disease_loaded가 없으면(분류 API가 없는 이전 AI 서버) false */
  public static AiHealth health(JsonNode body) {
    Checker c = new Checker();
    JsonNode root = c.root(body);
    String status = c.string(root.get("status"), "status", null);
    c.throwIfInvalid();
    JsonNode diseaseLoaded = root.get("disease_loaded");
    return new AiHealth(
        status, diseaseLoaded != null && diseaseLoaded.isBoolean() && diseaseLoaded.booleanValue());
  }

  /** 필드마다 확인하며 위반을 모은다 */
  private static final class Checker {
    private final List<Issue> issues = new ArrayList<>();

    JsonNode root(JsonNode body) {
      if (body != null && body.isObject()) return body;
      issues.add(new Issue("$", "not_object"));
      throw AiError.invalid(issues);
    }

    int issueCount() {
      return issues.size();
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
