package com.aiskincare.backend.analyses;

import com.aiskincare.backend.common.enums.AgeBand;
import com.aiskincare.backend.common.enums.Concern;
import com.aiskincare.backend.common.enums.Gender;
import com.aiskincare.backend.common.enums.Level;
import com.aiskincare.backend.common.enums.SkinType;
import com.aiskincare.backend.common.validation.FieldIssue;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Function;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * multipart 필드 {@code survey}(JSON 문자열)를 파싱·검증·정규화한다 (TRD §10.1). 오류 필드는 {@code survey.gender}처럼
 * 접두를 붙인다. 모르는 키는 버린다. 원문은 어디에도 남기지 않는다.
 */
public final class SurveyParser {
  private SurveyParser() {}

  public record Result(NormalizedSurvey survey, List<FieldIssue> errors) {
    public boolean ok() {
      return errors.isEmpty();
    }
  }

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  public static Result parse(String raw) {
    if (raw == null || raw.isEmpty()) return fail("survey", "required");
    JsonNode root;
    try {
      root = MAPPER.readTree(raw);
    } catch (JacksonException e) {
      return fail("survey", "invalid_format");
    }
    if (root == null || !root.isObject()) return fail("survey", "invalid_format");

    List<FieldIssue> errors = new ArrayList<>();
    Gender gender = required(root, "gender", Gender::fromCode, errors);
    AgeBand ageBand = required(root, "ageBand", AgeBand::fromCode, errors);
    SkinType skinType = optional(root, "skinType", SkinType::fromCode, errors);
    Level water = optional(root, "waterIntakeLevel", Level::fromCode, errors);
    Level sun = optional(root, "sunExposureLevel", Level::fromCode, errors);
    Level stress = optional(root, "stressLevel", Level::fromCode, errors);

    Integer sleepHours = null;
    JsonNode sleep = root.get("sleepHours");
    if (present(sleep)) {
      if (!sleep.isIntegralNumber()) {
        errors.add(issue("sleepHours", "invalid_type"));
      } else if (sleep.intValue() < 0 || sleep.intValue() > 14) {
        errors.add(issue("sleepHours", "out_of_range"));
      } else {
        sleepHours = sleep.intValue();
      }
    }

    List<Concern> concerns = new ArrayList<>();
    JsonNode concernsNode = root.get("concerns");
    if (present(concernsNode)) {
      if (!concernsNode.isArray()) {
        errors.add(issue("concerns", "invalid_type"));
      } else if (concernsNode.size() > 3) {
        errors.add(issue("concerns", "too_many"));
      } else {
        var seen = new HashSet<String>();
        for (JsonNode item : concernsNode) {
          Concern concern = item.isString() ? Concern.fromCode(item.stringValue()) : null;
          if (concern == null) {
            errors.add(issue("concerns", "invalid_value"));
            break;
          }
          if (!seen.add(concern.code())) {
            errors.add(issue("concerns", "duplicate"));
            break;
          }
          concerns.add(concern);
        }
      }
    }

    List<String> products = new ArrayList<>();
    JsonNode productsNode = root.get("currentProducts");
    if (present(productsNode)) {
      if (!productsNode.isArray()) {
        errors.add(issue("currentProducts", "invalid_type"));
      } else if (productsNode.size() > 5) {
        errors.add(issue("currentProducts", "too_many"));
      } else {
        for (JsonNode item : productsNode) {
          if (!item.isString()) {
            errors.add(issue("currentProducts", "invalid_type"));
            break;
          }
          String value = item.stringValue();
          if (value.isEmpty() || value.length() > 30) {
            errors.add(issue("currentProducts", "invalid_length"));
            break;
          }
          products.add(value);
        }
      }
    }

    String note = null;
    JsonNode noteNode = root.get("note");
    if (present(noteNode)) {
      if (!noteNode.isString()) {
        errors.add(issue("note", "invalid_type"));
      } else if (noteNode.stringValue().length() > 300) {
        errors.add(issue("note", "too_long"));
      } else {
        note = noteNode.stringValue();
      }
    }

    if (!errors.isEmpty()) return new Result(null, errors);
    return new Result(
        new NormalizedSurvey(
            gender, ageBand, skinType, sleepHours, water, sun, stress, concerns, products, note),
        List.of());
  }

  private static boolean present(JsonNode node) {
    return node != null && !node.isNull();
  }

  private static <T> T required(
      JsonNode root, String field, Function<String, T> fromCode, List<FieldIssue> errors) {
    JsonNode node = root.get(field);
    if (!present(node)) {
      errors.add(issue(field, "required"));
      return null;
    }
    return parseCode(node, field, fromCode, errors);
  }

  private static <T> T optional(
      JsonNode root, String field, Function<String, T> fromCode, List<FieldIssue> errors) {
    JsonNode node = root.get(field);
    return present(node) ? parseCode(node, field, fromCode, errors) : null;
  }

  private static <T> T parseCode(
      JsonNode node, String field, Function<String, T> fromCode, List<FieldIssue> errors) {
    T value = node.isString() ? fromCode.apply(node.stringValue()) : null;
    if (value == null) errors.add(issue(field, "invalid_value"));
    return value;
  }

  private static FieldIssue issue(String field, String reason) {
    return new FieldIssue("survey." + field, reason);
  }

  private static Result fail(String field, String reason) {
    return new Result(null, List.of(new FieldIssue(field, reason)));
  }
}
