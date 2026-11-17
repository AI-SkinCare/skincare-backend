package com.aiskincare.backend.aiclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.aiskincare.backend.aiclient.AiModels.AiCallContext;
import com.aiskincare.backend.aiclient.AiModels.ClassifyInput;
import com.aiskincare.backend.aiclient.AiModels.DiseaseResult;
import com.aiskincare.backend.analyses.NormalizedSurvey;
import com.aiskincare.backend.common.enums.AgeBand;
import com.aiskincare.backend.common.enums.Concern;
import com.aiskincare.backend.common.enums.Gender;
import com.aiskincare.backend.common.enums.RecommendationStatus;
import com.aiskincare.backend.common.enums.SkinType;
import com.aiskincare.backend.common.error.ErrorCode;
import com.aiskincare.backend.support.TestImages;
import java.io.IOException;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 AI 서버(skincare-ai)와의 계약 테스트 — AI_CONTRACT_URL이 있을 때만 돈다 (CI에선 건너뜀).
 *
 * <pre>
 * AI_CONTRACT_URL=http://127.0.0.1:8000 AI_CONTRACT_TOKEN=&lt;AI_INTERNAL_TOKEN&gt; ./gradlew aiContractTest
 * </pre>
 *
 * 분류 시험은 AI가 DISEASE_BACKEND=densenet이어야 한다. 추천은 REC_BACKEND=mock이면 수 초, sllm이면 생성 1건에 약 45초 × 10건이
 * 걸린다(보류는 GPU 생성 없음). 백엔드가 만든 요청(저장한 분류 JSON 왕복, 코드 → 라벨 변환)을 AI가 받는지, AI 응답이 우리 검증을 통과하는지 본다. 분류
 * 입력은 합성 이미지·시험용 JSON이라 결과는 실제 판정이 아니다.
 */
@Tag("ai-contract")
@EnabledIfEnvironmentVariable(named = "AI_CONTRACT_URL", matches = ".+")
class AiContractIT {
  private static final String URL = System.getenv("AI_CONTRACT_URL");
  private static final String TOKEN = System.getenv().getOrDefault("AI_CONTRACT_TOKEN", "");
  private static final AiCallContext CTX = new AiCallContext("ai-contract-test", null);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static HttpAiClient client(String token) {
    return new HttpAiClient(new HttpAiClient.Options(URL, token, 20_000, 130_000), JSON);
  }

  /** 시험용 분류 JSON (skincare-ai 기록의 disease 객체) */
  private static DiseaseResult fixtureDisease(String name) {
    try (var in =
        new ClassPathResource("fixtures/ai/disease-pipeline/" + name + ".response.json")
            .getInputStream()) {
      return AiResponseParser.disease(JSON.readTree(in).get("disease"));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static NormalizedSurvey survey(SkinType skinType, List<Concern> concerns) {
    return new NormalizedSurvey(
        Gender.FEMALE,
        AgeBand.TWENTIES,
        skinType,
        null,
        null,
        null,
        null,
        concerns,
        List.of(),
        null);
  }

  @Test
  @DisplayName("GET /health — 인증 없이 status ok, 분류기 로드")
  void health() {
    var health = client("").health();
    assertThat(health.status()).isEqualTo("ok");
    assertThat(health.diseaseLoaded()).isTrue();
  }

  @Test
  @DisplayName("POST /infer/disease → 받은 분류를 그대로 /infer/disease-recommend에 다시 보내도 422가 아니다 (값 왕복)")
  void classifyRoundTrip() {
    var ai = client(TOKEN);
    var disease = ai.classify(new ClassifyInput(TestImages.png(1080, 1440), "image/png"), CTX);
    assertThat(disease.probabilities()).hasSize(6);
    var result =
        ai.recommend(
            AiTransform.buildRecommendRequest(
                disease, survey(SkinType.DRY, List.of(Concern.DRYNESS))),
            CTX);
    assertThat(result.status()).isNotNull();
  }

  static Stream<Arguments> labels() {
    Concern[] concerns = Concern.values();
    SkinType[] types = SkinType.values();
    return IntStream.range(0, concerns.length)
        .mapToObj(i -> Arguments.of(concerns[i], types[i % types.length]));
  }

  @ParameterizedTest(name = "정상 분류 + 고민 {0} · 피부 타입 {1}을 AI가 인식한다")
  @MethodSource("labels")
  void labelsRecognized(Concern concern, SkinType skinType) {
    var result =
        client(TOKEN)
            .recommend(
                AiTransform.buildRecommendRequest(
                    fixtureDisease("no_concern"), survey(skinType, List.of(concern))),
                CTX);
    assertThat(result.status()).isEqualTo(RecommendationStatus.GENERATED);
    assertThat(result.recommendation().skinType()).isEqualTo(skinType);
    assertThat(result.recommendation().concerns()).contains(concern);
    assertThat(result.recommendation().disclaimer()).isNotBlank();
  }

  @Test
  @DisplayName("분류 라벨이 추천 입력이 된다 — 아토피 0.9, 고민 없음 → generated")
  void diseaseDrivesRecommendation() {
    var result =
        client(TOKEN)
            .recommend(
                AiTransform.buildRecommendRequest(
                    fixtureDisease("generated"), survey(null, List.of())),
                CTX);
    assertThat(result.status()).isEqualTo(RecommendationStatus.GENERATED);
    assertThat(result.guidance()).isNotEmpty();
  }

  @Test
  @DisplayName("보류: 점수 0.55 → uncertain, 정상·고민 없음 → no_concern (추천 null)")
  void withheld() {
    var ai = client(TOKEN);
    var uncertain =
        ai.recommend(
            AiTransform.buildRecommendRequest(fixtureDisease("uncertain"), survey(null, List.of())),
            CTX);
    assertThat(uncertain.status()).isEqualTo(RecommendationStatus.UNCERTAIN);
    assertThat(uncertain.recommendation()).isNull();
    var noConcern =
        ai.recommend(
            AiTransform.buildRecommendRequest(
                fixtureDisease("no_concern"), survey(null, List.of())),
            CTX);
    assertThat(noConcern.status()).isEqualTo(RecommendationStatus.NO_CONCERN);
  }

  @Test
  @DisplayName("내부 토큰이 틀리면 AI 401 E1001 → 분류 503 E3001 / 워커 FAILED E3001")
  void wrongToken() {
    var wrong = client("wrong-token");
    AiError classify =
        catchThrowableOfType(
            AiError.class,
            () -> wrong.classify(new ClassifyInput(TestImages.png(800, 800), "image/png"), CTX));
    assertThat(classify.kind()).isEqualTo(AiError.Kind.HTTP);
    assertThat(classify.status()).isEqualTo(401);
    assertThat(classify.code()).isEqualTo("E1001");
    assertThat(AiErrorMapper.classify(classify).code()).isEqualTo(ErrorCode.E3001);
    AiError rec =
        catchThrowableOfType(
            AiError.class,
            () ->
                wrong.recommend(
                    AiTransform.buildRecommendRequest(
                        fixtureDisease("generated"), survey(null, List.of())),
                    CTX));
    assertThat(AiErrorMapper.recommend(rec).code()).isEqualTo(ErrorCode.E3001);
  }
}
