package com.aiskincare.backend.aiclient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TRD §12.2 오류 분류.
 *
 * <ul>
 *   <li>fetch가 응답 전에 실패 (연결 거부, DNS, TLS 등) → connection
 *   <li>상태 ≥ 500이고 본문에 error.code가 없음 (Cloudflare HTML 502/521/522/523/530 등) → connection
 *   <li>상태 524 (Cloudflare: AI가 125초 안에 응답 안 함) → timeout — 504처럼 재시도 금지
 *   <li>우리 타임아웃 초과 → timeout
 *   <li>상태 ≥ 400이고 본문에 error.code 있음 → http
 *   <li>2xx인데 JSON 파싱 실패 또는 스키마 위반 → invalid_response
 * </ul>
 */
public class AiError extends RuntimeException {
  public enum Kind {
    HTTP("http"),
    CONNECTION("connection"),
    TIMEOUT("timeout"),
    INVALID_RESPONSE("invalid_response");

    private final String code;

    Kind(String code) {
      this.code = code;
    }

    public String code() {
      return code;
    }
  }

  /** 검증 실패 필드의 경로와 이유 (값은 넣지 않는다) */
  public record Issue(String path, String reason) {}

  private final Kind kind;
  private final Integer status;
  private final String code;
  private final transient String aiMessage;
  private final transient List<Issue> issues;

  public AiError(Kind kind, Integer status, String code, String aiMessage, List<Issue> issues) {
    // 메시지에 AI 응답 본문을 넣지 않는다
    super(
        "AI "
            + kind.code()
            + (status != null ? " " + status : "")
            + (code != null ? " " + code : ""));
    this.kind = kind;
    this.status = status;
    this.code = code;
    this.aiMessage = aiMessage;
    this.issues = issues == null ? List.of() : List.copyOf(issues);
  }

  public static AiError of(Kind kind) {
    return new AiError(kind, null, null, null, null);
  }

  public static AiError http(int status, String code, String aiMessage) {
    return new AiError(Kind.HTTP, status, code, aiMessage, null);
  }

  public static AiError invalid(List<Issue> issues) {
    return new AiError(Kind.INVALID_RESPONSE, null, null, null, issues);
  }

  public Kind kind() {
    return kind;
  }

  public Integer status() {
    return status;
  }

  public String code() {
    return code;
  }

  public String aiMessage() {
    return aiMessage;
  }

  public List<Issue> issues() {
    return issues;
  }

  /** 로그용 요약 — 상태·코드·이슈 경로만 */
  public Map<String, Object> toLog() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("kind", kind.code());
    if (status != null) out.put("status", status);
    if (code != null) out.put("code", code);
    if (!issues.isEmpty()) out.put("issues", issues);
    return out;
  }
}
