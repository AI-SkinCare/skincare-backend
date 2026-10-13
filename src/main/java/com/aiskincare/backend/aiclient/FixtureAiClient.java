package com.aiskincare.backend.aiclient;

import com.aiskincare.backend.aiclient.AiModels.AiCallContext;
import com.aiskincare.backend.aiclient.AiModels.AiHealth;
import com.aiskincare.backend.aiclient.AiModels.MeasureSkinInput;
import com.aiskincare.backend.aiclient.AiModels.RecommendRequest;
import com.aiskincare.backend.aiclient.AiModels.RecommendResult;
import com.aiskincare.backend.aiclient.AiModels.SkinMetricsResult;
import com.aiskincare.backend.common.enums.FixtureScenario;
import com.aiskincare.backend.common.util.Sleeper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

/**
 * AI 서버가 꺼져 있어도 개발·테스트할 수 있게 하는 클라이언트 (TRD §12.7). 응답은 HttpAiClient와 같은 검증·변환 코드를 거친다 (계약 테스트 겸용).
 *
 * <pre>
 * | 시나리오     | 측정          | 추천                  | health |
 * | ok          | skin-metrics | recommend, 1초 지연    | ok     |
 * | no-face     | 422 E2001    | —                     | ok     |
 * | rec-timeout | 정상          | 504 E3002 즉시         | ok     |
 * | rec-slow    | 정상          | 60초 지연 후 정상       | ok     |
 * | ai-down     | connection   | connection            | 실패    |
 * </pre>
 */
public class FixtureAiClient implements AiClient {
  public static final Duration REC_DELAY = Duration.ofSeconds(1);
  public static final Duration REC_SLOW_DELAY = Duration.ofSeconds(60);
  private static final int RECORDED_LIMIT = 50;

  private final FixtureScenario defaultScenario;
  private final Sleeper sleeper;
  private final JsonMapper json;
  private final byte[] skinMetricsBody;
  private final byte[] recommendBody;

  /** 받은 추천 요청 (TC-ANA-09: 데모 측정이면 metrics: null). 최근 것만 보관 */
  private final List<RecommendRequest> recommendRequests = new ArrayList<>();

  public FixtureAiClient(FixtureScenario defaultScenario, Sleeper sleeper, JsonMapper json) {
    this.defaultScenario = defaultScenario;
    this.sleeper = sleeper;
    this.json = json;
    this.skinMetricsBody = read("fixtures/ai/skin-metrics.ok.json");
    this.recommendBody = read("fixtures/ai/recommend.ok.json");
  }

  private static byte[] read(String path) {
    try (var in = new ClassPathResource(path).getInputStream()) {
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("fixture를 읽지 못했습니다: " + path, e);
    }
  }

  public synchronized List<RecommendRequest> recommendRequests() {
    return List.copyOf(recommendRequests);
  }

  public synchronized void clearRecordedRequests() {
    recommendRequests.clear();
  }

  /** health는 env 시나리오만 따른다 */
  @Override
  public AiHealth health() {
    if (defaultScenario == FixtureScenario.AI_DOWN) throw AiError.of(AiError.Kind.CONNECTION);
    return new AiHealth("ok", "stub");
  }

  @Override
  public Object modelInfo() {
    if (defaultScenario == FixtureScenario.AI_DOWN) throw AiError.of(AiError.Kind.CONNECTION);
    return Map.of("mode", "fixture");
  }

  @Override
  public SkinMetricsResult measureSkin(MeasureSkinInput input, AiCallContext ctx) {
    return switch (scenario(ctx)) {
      case NO_FACE -> throw AiError.http(422, "E2001", "얼굴을 찾지 못했어요. 정면에서 다시 찍어 주세요.");
      case AI_DOWN -> throw AiError.of(AiError.Kind.CONNECTION);
      default -> AiResponseParser.skinMetrics(json.readTree(skinMetricsBody));
    };
  }

  @Override
  public RecommendResult recommend(RecommendRequest request, AiCallContext ctx) {
    synchronized (this) {
      recommendRequests.add(request);
      if (recommendRequests.size() > RECORDED_LIMIT) recommendRequests.remove(0);
    }
    switch (scenario(ctx)) {
      case REC_TIMEOUT -> throw AiError.http(504, "E3002", "추천을 만들지 못했어요. 다시 시도해 주세요.");
      case AI_DOWN -> throw AiError.of(AiError.Kind.CONNECTION);
      case REC_SLOW -> sleep(REC_SLOW_DELAY);
      default -> sleep(REC_DELAY);
    }
    return AiResponseParser.recommend(json.readTree(recommendBody));
  }

  private FixtureScenario scenario(AiCallContext ctx) {
    return ctx.scenario() != null ? ctx.scenario() : defaultScenario;
  }

  private void sleep(Duration duration) {
    try {
      sleeper.sleep(duration);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw AiError.of(AiError.Kind.CONNECTION);
    }
  }
}
