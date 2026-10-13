package com.aiskincare.backend.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 실제 HTTP로 서버를 부른다 (Tomcat의 multipart 한도·필터·인터셉터 순서까지 그대로). */
public final class TestHttp {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final HttpClient client = HttpClient.newHttpClient();
  private final String base;

  public TestHttp(int port) {
    this.base = "http://localhost:" + port;
  }

  public record Res(int status, JsonNode body, String raw, Map<String, List<String>> headers) {
    public String header(String name) {
      List<String> values = headers(name);
      return values.isEmpty() ? null : values.get(0);
    }

    public List<String> headers(String name) {
      for (var entry : headers.entrySet()) {
        if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
      }
      return List.of();
    }

    public JsonNode data() {
      return body.get("data");
    }

    public String errorCode() {
      return body == null || body.get("error") == null
          ? null
          : body.get("error").get("code").stringValue();
    }
  }

  public static final class Req {
    private final TestHttp http;
    private final String method;
    private final String path;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private byte[] body;
    private String contentType;

    Req(TestHttp http, String method, String path) {
      this.http = http;
      this.method = method;
      this.path = path;
    }

    public Req header(String name, String value) {
      headers.put(name, value);
      return this;
    }

    public Req bearer(String token) {
      return header("Authorization", "Bearer " + token);
    }

    public Req json(Object value) {
      this.body =
          value instanceof String s
              ? s.getBytes(StandardCharsets.UTF_8)
              : JSON.writeValueAsBytes(value);
      this.contentType = "application/json";
      return this;
    }

    public Req multipart(Multipart form) {
      this.body = form.bytes();
      this.contentType = form.contentType();
      return this;
    }

    public Res send() {
      var builder = HttpRequest.newBuilder(URI.create(http.base + path));
      headers.forEach(builder::header);
      if (contentType != null) builder.header("Content-Type", contentType);
      builder.method(
          method,
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofByteArray(body));
      try {
        HttpResponse<String> response =
            http.client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        String raw = response.body();
        JsonNode parsed = raw == null || raw.isEmpty() ? null : JSON.readTree(raw);
        return new Res(response.statusCode(), parsed, raw, response.headers().map());
      } catch (IOException e) {
        throw new java.io.UncheckedIOException(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
  }

  public Req get(String path) {
    return new Req(this, "GET", path);
  }

  public Req post(String path) {
    return new Req(this, "POST", path);
  }

  public Req delete(String path) {
    return new Req(this, "DELETE", path);
  }

  public Req put(String path) {
    return new Req(this, "PUT", path);
  }

  /** multipart/form-data 본문 */
  public static final class Multipart {
    private final String boundary = "Boundary-" + UUID.randomUUID();
    private final List<byte[]> parts = new ArrayList<>();

    public Multipart field(String name, String value) {
      parts.add(
          ("--"
                  + boundary
                  + "\r\nContent-Disposition: form-data; name=\""
                  + name
                  + "\"\r\n\r\n"
                  + value
                  + "\r\n")
              .getBytes(StandardCharsets.UTF_8));
      return this;
    }

    public Multipart file(String name, String filename, String type, byte[] data) {
      var out = new ByteArrayOutputStream();
      out.writeBytes(
          ("--"
                  + boundary
                  + "\r\nContent-Disposition: form-data; name=\""
                  + name
                  + "\"; filename=\""
                  + filename
                  + "\"\r\nContent-Type: "
                  + type
                  + "\r\n\r\n")
              .getBytes(StandardCharsets.UTF_8));
      out.writeBytes(data);
      out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
      parts.add(out.toByteArray());
      return this;
    }

    byte[] bytes() {
      var out = new ByteArrayOutputStream();
      parts.forEach(out::writeBytes);
      out.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
      return out.toByteArray();
    }

    String contentType() {
      return "multipart/form-data; boundary=" + boundary;
    }
  }

  public static JsonMapper json() {
    return JSON;
  }
}
