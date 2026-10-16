package com.aiskincare.backend.aiclient;

import com.aiskincare.backend.aiclient.AiModels.AiCallContext;
import com.aiskincare.backend.aiclient.AiModels.AiHealth;
import com.aiskincare.backend.aiclient.AiModels.ClassifyInput;
import com.aiskincare.backend.aiclient.AiModels.DiseaseRecommendRequest;
import com.aiskincare.backend.aiclient.AiModels.DiseaseRecommendResult;
import com.aiskincare.backend.aiclient.AiModels.DiseaseResult;
import com.aiskincare.backend.config.Constants;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** AI 서버 HTTP 클라이언트 (TRD §12.2). 재시도는 하지 않는다 — 재시도 정책은 호출하는 쪽(분류 경로·워커)이 §12.5로 정한다. */
public class HttpAiClient implements AiClient {
  /** Cloudflare Error 524 — 원 서버가 연결은 받았지만 125초 안에 응답하지 않음 */
  private static final int CLOUDFLARE_ORIGIN_TIMEOUT = 524;

  public record Options(
      String baseUrl, String internalToken, int classifyTimeoutMs, int recTimeoutMs) {}

  private record Raw(int status, byte[] body) {}

  private final Options options;
  private final JsonMapper json;
  private final RestClient healthClient;
  private final RestClient classifyClient;
  private final RestClient recClient;

  public HttpAiClient(Options options, JsonMapper json) {
    this.options = options;
    this.json = json;
    this.healthClient = client(Constants.AI_HEALTH_TIMEOUT);
    this.classifyClient = client(Duration.ofMillis(options.classifyTimeoutMs()));
    this.recClient = client(Duration.ofMillis(options.recTimeoutMs()));
  }

  private RestClient client(Duration timeout) {
    HttpClient http =
        HttpClient.newBuilder()
            .connectTimeout(Constants.AI_HEALTH_TIMEOUT)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(timeout);
    return RestClient.builder().requestFactory(factory).baseUrl(options.baseUrl()).build();
  }

  @Override
  public AiHealth health() {
    // /health는 내부 토큰 없이
    return AiResponseParser.health(send(healthClient, HttpMethod.GET, "/health", null, null, null));
  }

  @Override
  public Object modelInfo() {
    return send(healthClient, HttpMethod.GET, "/model-info", authHeaders(null), null, null);
  }

  /** multipart image 하나 (TRD §12.3). Content-Type은 시그니처로 판별한 값 */
  @Override
  public DiseaseResult classify(ClassifyInput input, AiCallContext ctx) {
    String filename = "image/png".equals(input.mimeType()) ? "image.png" : "image.jpg";
    HttpHeaders fileHeaders = new HttpHeaders();
    fileHeaders.setContentType(MediaType.parseMediaType(input.mimeType()));
    fileHeaders.setContentDispositionFormData("image", filename);
    MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
    parts.add(
        "image",
        new HttpEntity<>(
            new ByteArrayResource(input.image()) {
              @Override
              public String getFilename() {
                return filename;
              }
            },
            fileHeaders));
    JsonNode body =
        send(
            classifyClient,
            HttpMethod.POST,
            "/infer/disease",
            authHeaders(ctx.requestId()),
            parts,
            MediaType.MULTIPART_FORM_DATA);
    return AiResponseParser.disease(body);
  }

  @Override
  public DiseaseRecommendResult recommend(DiseaseRecommendRequest request, AiCallContext ctx) {
    String payload = json.writeValueAsString(request);
    JsonNode body =
        send(
            recClient,
            HttpMethod.POST,
            "/infer/disease-recommend",
            authHeaders(ctx.requestId()),
            payload,
            MediaType.APPLICATION_JSON);
    return AiResponseParser.diseaseRecommend(body);
  }

  private Consumer<HttpHeaders> authHeaders(String requestId) {
    return headers -> {
      headers.set("X-Internal-Token", options.internalToken());
      if (requestId != null) headers.set("X-Request-Id", requestId);
    };
  }

  private JsonNode send(
      RestClient client,
      HttpMethod method,
      String path,
      Consumer<HttpHeaders> headers,
      Object body,
      MediaType contentType) {
    Raw raw;
    try {
      var spec = client.method(method).uri(path).accept(MediaType.APPLICATION_JSON);
      if (headers != null) spec = spec.headers(headers);
      if (body != null) spec = spec.contentType(contentType).body(body);
      raw =
          spec.exchange(
              (req, res) -> new Raw(res.getStatusCode().value(), res.getBody().readAllBytes()));
    } catch (RuntimeException e) {
      throw classify(e);
    }

    JsonNode parsed = parseJson(raw.body());
    int status = raw.status();
    if (status >= 400) {
      JsonNode error = parsed == null ? null : parsed.get("error");
      JsonNode code = error == null ? null : error.get("code");
      if (code != null && code.isString() && !code.stringValue().isEmpty()) {
        JsonNode message = error.get("message");
        throw AiError.http(
            status,
            code.stringValue(),
            message != null && message.isString() ? message.stringValue() : null);
      }
      // Cloudflare 524: AI는 GPU 생성을 계속하므로 504와 같이 재시도하면 안 된다 → timeout [구현 결정]
      if (status == CLOUDFLARE_ORIGIN_TIMEOUT) {
        throw new AiError(AiError.Kind.TIMEOUT, status, null, null, null);
      }
      // 본문에 error.code가 없는 5xx = Cloudflare HTML(502/521/522/523/530 등) → 연결 실패로 본다.
      // Cloudflare의 503을 AI의 503 E3003으로 읽으면 "요청이 많아요"가 잘못 뜬다.
      if (status >= 500) throw new AiError(AiError.Kind.CONNECTION, status, null, null, null);
      throw new AiError(AiError.Kind.HTTP, status, null, null, null);
    }
    if (parsed == null) {
      throw AiError.invalid(List.of(new AiError.Issue("$", "invalid_json")));
    }
    return parsed;
  }

  private JsonNode parseJson(byte[] body) {
    if (body == null || body.length == 0) return null;
    try {
      return json.readTree(body);
    } catch (JacksonException e) {
      return null;
    }
  }

  /** 응답 전 실패: 우리 타임아웃이면 timeout, 그 밖(연결 거부·DNS·TLS·연결 시간 초과)은 connection */
  private static AiError classify(RuntimeException e) {
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      if (cause instanceof HttpConnectTimeoutException) break;
      if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
        return AiError.of(AiError.Kind.TIMEOUT);
      }
    }
    return AiError.of(AiError.Kind.CONNECTION);
  }
}
