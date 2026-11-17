package com.aiskincare.backend.aiclient;

import com.aiskincare.backend.aiclient.AiModels.AiCallContext;
import com.aiskincare.backend.aiclient.AiModels.AiHealth;
import com.aiskincare.backend.aiclient.AiModels.ClassifyInput;
import com.aiskincare.backend.aiclient.AiModels.DiseaseRecommendRequest;
import com.aiskincare.backend.aiclient.AiModels.DiseaseRecommendResult;
import com.aiskincare.backend.aiclient.AiModels.DiseaseResult;
import com.aiskincare.backend.common.enums.FixtureScenario;
import com.aiskincare.backend.common.util.Sleeper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * AI 서버가 꺼져 있어도 개발·테스트할 수 있게 하는 클라이언트 (TRD §12.7). 응답은 skincare-ai의 기록({@code
 * fixtures/ai/disease-pipeline/})이고 HttpAiClient와 같은 검증·변환 코드를 거친다 (계약 테스트 겸용). 분류 응답은 각 기록의 {@code
 * disease} 객체다.
 *
 * <pre>
 * | 시나리오     | 분류                      | 추천                         | health |
 * | ok          | generated의 disease       | generated, 1초 지연           | ok     |
 * | uncertain   | uncertain의 disease       | uncertain (보류, 지연 없음)    | ok     |
 * | no-concern  | no_concern의 disease      | no_concern (보류, 지연 없음)   | ok     |
 * | no-face     | 422 E2001 (AI에는 아직 없음) | —                          | ok     |
 * | rec-timeout | 정상                      | 504 E3002 즉시               | ok     |
 * | rec-slow    | 정상                      | 60초 지연 후 generated        | ok     |
 * | ai-down     | connection               | connection                  | 실패    |
 * </pre>
 */
public class FixtureAiClient implements AiClient {
  public static final Duration REC_DELAY = Duration.ofSeconds(1);
  public static final Duration REC_SLOW_DELAY = Duration.ofSeconds(60);
  private static final int RECORDED_LIMIT = 50;

  private final FixtureScenario defaultScenario;
  private final Sleeper sleeper;
  private final JsonMapper json;
  private final byte[] generatedBody;
  private final byte[] uncertainBody;
  private final byte[] noConcernBody;

  /** 받은 추천 요청 (저장한 분류·설문 라벨이 그대로 가는지 확인용). 최근 것만 보관 */
  private final List<DiseaseRecommendRequest> recommendRequests = new ArrayList<>();

  public FixtureAiClient(FixtureScenario defaultScenario, Sleeper sleeper, JsonMapper json) {
    this.defaultScenario = defaultScenario;
    this.sleeper = sleeper;
    this.json = json;
    this.generatedBody = read("fixtures/ai/disease-pipeline/generated.response.json");
    this.uncertainBody = read("fixtures/ai/disease-pipeline/uncertain.response.json");
    this.noConcernBody = read("fixtures/ai/disease-pipeline/no_concern.response.json");
  }

  private static byte[] read(String path) {
    try (var in = new ClassPathResource(path).getInputStream()) {
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("fixture를 읽지 못했습니다: " + path, e);
    }
  }

  public synchronized List<DiseaseRecommendRequest> recommendRequests() {
    return List.copyOf(recommendRequests);
  }

  public synchronized void clearRecordedRequests() {
    recommendRequests.clear();
  }

  /** health는 env 시나리오만 따른다 */
  @Override
  public AiHealth health() {
    if (defaultScenario == FixtureScenario.AI_DOWN) throw AiError.of(AiError.Kind.CONNECTION);
    return new AiHealth("ok", true);
  }

  @Override
  public Object modelInfo() {
    if (defaultScenario == FixtureScenario.AI_DOWN) throw AiError.of(AiError.Kind.CONNECTION);
    return Map.of("mode", "fixture");
  }

  @Override
  public DiseaseResult classify(ClassifyInput input, AiCallContext ctx) {
    return switch (scenario(ctx)) {
      case NO_FACE -> throw AiError.http(422, "E2001", "얼굴을 찾지 못했어요. 정면에서 다시 찍어 주세요.");
      case AI_DOWN -> throw AiError.of(AiError.Kind.CONNECTION);
      case UNCERTAIN -> AiResponseParser.disease(diseaseOf(uncertainBody));
      case NO_CONCERN -> AiResponseParser.disease(diseaseOf(noConcernBody));
      default -> AiResponseParser.disease(diseaseOf(generatedBody));
    };
  }

  @Override
  public DiseaseRecommendResult recommend(DiseaseRecommendRequest request, AiCallContext ctx) {
    synchronized (this) {
      recommendRequests.add(request);
      if (recommendRequests.size() > RECORDED_LIMIT) recommendRequests.remove(0);
    }
    byte[] body =
        switch (scenario(ctx)) {
          case REC_TIMEOUT -> throw AiError.http(504, "E3002", "추천을 만들지 못했어요. 다시 시도해 주세요.");
          case AI_DOWN -> throw AiError.of(AiError.Kind.CONNECTION);
          // 보류는 AI가 GPU 생성 없이 바로 응답한다
          case UNCERTAIN -> uncertainBody;
          case NO_CONCERN -> noConcernBody;
          case REC_SLOW -> {
            sleep(REC_SLOW_DELAY);
            yield generatedBody;
          }
          default -> {
            sleep(REC_DELAY);
            yield generatedBody;
          }
        };
    return AiResponseParser.diseaseRecommend(json.readTree(body));
  }

  private JsonNode diseaseOf(byte[] body) {
    return json.readTree(body).get("disease");
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
