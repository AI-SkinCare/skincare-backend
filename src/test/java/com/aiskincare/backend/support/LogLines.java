package com.aiskincare.backend.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/** 캡처한 콘솔 출력(logstash JSON 한 줄씩)을 읽는다 */
public final class LogLines {
  private final String raw;
  private final List<JsonNode> lines = new ArrayList<>();

  public LogLines(String raw) {
    this.raw = raw;
    for (String line : raw.split("\\R")) {
      if (!line.startsWith("{")) continue;
      try {
        lines.add(TestHttp.json().readTree(line));
      } catch (JacksonException e) {
        // JSON이 아닌 줄은 건너뛴다
      }
    }
  }

  public String raw() {
    return raw;
  }

  public List<JsonNode> lines() {
    return lines;
  }

  public Optional<JsonNode> find(String message) {
    return lines.stream().filter(l -> message.equals(l.path("message").asString(null))).findFirst();
  }
}
