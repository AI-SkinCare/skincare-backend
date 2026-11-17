package com.aiskincare.backend.aiclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiskincare.backend.common.error.ErrorCode;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.event.Level;

/** TRD §12.5 표 */
class AiErrorMapperTest {
  private static AiError http(int status, String code) {
    return AiError.http(status, code, null);
  }

  static Stream<Arguments> classify() {
    return Stream.of(
        Arguments.of(
            "TC-ANA-10: AI 401 → 503 E3001 + ERROR",
            http(401, "E1001"),
            ErrorCode.E3001,
            Level.ERROR),
        Arguments.of("413 그대로", http(413, "E2003"), ErrorCode.E2003, Level.INFO),
        Arguments.of("415 그대로", http(415, "E2004"), ErrorCode.E2004, Level.INFO),
        Arguments.of("422 E2001 그대로", http(422, "E2001"), ErrorCode.E2001, Level.INFO),
        Arguments.of("422 E2002 그대로", http(422, "E2002"), ErrorCode.E2002, Level.INFO),
        Arguments.of("422 E4002 → E4001 (변환 버그)", http(422, "E4002"), ErrorCode.E4001, Level.ERROR),
        Arguments.of("(개정) 503 E3001 분류기 비활성 그대로", http(503, "E3001"), ErrorCode.E3001, Level.WARN),
        Arguments.of("503 E3003 그대로", http(503, "E3003"), ErrorCode.E3003, Level.WARN),
        Arguments.of("504 E3002 그대로", http(504, "E3002"), ErrorCode.E3002, Level.WARN),
        Arguments.of(
            "connection → E3001", AiError.of(AiError.Kind.CONNECTION), ErrorCode.E3001, Level.WARN),
        Arguments.of(
            "timeout → E3001", AiError.of(AiError.Kind.TIMEOUT), ErrorCode.E3001, Level.WARN),
        Arguments.of(
            "invalid → E4001", AiError.invalid(java.util.List.of()), ErrorCode.E4001, Level.ERROR),
        Arguments.of("그 밖의 4xx → E4001", http(400, "E9999"), ErrorCode.E4001, Level.ERROR),
        Arguments.of("그 밖의 5xx → E4001", http(500, "E4001"), ErrorCode.E4001, Level.ERROR));
  }

  @ParameterizedTest(name = "분류 {0}")
  @MethodSource
  void classify(String name, AiError error, ErrorCode code, Level level) {
    var mapped = AiErrorMapper.classify(error);
    assertThat(mapped.code()).isEqualTo(code);
    assertThat(mapped.logLevel()).isEqualTo(level);
  }

  @Test
  void classifyUsesAiMessageForE2001E2002() {
    assertThat(
            AiErrorMapper.classify(AiError.http(422, "E2002", "이미지를 해석할 수 없습니다. 다시 촬영해 주세요."))
                .message())
        .isEqualTo("이미지를 해석할 수 없습니다. 다시 촬영해 주세요.");
    assertThat(AiErrorMapper.classify(http(422, "E2001")).message()).isNull();
  }

  @Test
  void classifyRetriesOnlyConnection() {
    assertThat(AiErrorMapper.classifyRetryable(AiError.of(AiError.Kind.CONNECTION))).isTrue();
    assertThat(AiErrorMapper.classifyRetryable(AiError.of(AiError.Kind.TIMEOUT))).isFalse();
    assertThat(AiErrorMapper.classifyRetryable(http(503, "E3003"))).isFalse();
  }

  static Stream<Arguments> recommend() {
    return Stream.of(
        Arguments.of("401 → E3001 + ERROR", http(401, "E1001"), ErrorCode.E3001, Level.ERROR),
        Arguments.of("422 E4002 → E4001", http(422, "E4002"), ErrorCode.E4001, Level.ERROR),
        Arguments.of("503 E3001 → E3001", http(503, "E3001"), ErrorCode.E3001, Level.WARN),
        Arguments.of("503 E3003 → E3003", http(503, "E3003"), ErrorCode.E3003, Level.WARN),
        Arguments.of("504 E3002 → E3002", http(504, "E3002"), ErrorCode.E3002, Level.WARN),
        Arguments.of(
            "connection → E3001", AiError.of(AiError.Kind.CONNECTION), ErrorCode.E3001, Level.WARN),
        Arguments.of(
            "timeout(우리 쪽·524) → E3002",
            AiError.of(AiError.Kind.TIMEOUT),
            ErrorCode.E3002,
            Level.WARN),
        Arguments.of(
            "invalid → E4001", AiError.invalid(java.util.List.of()), ErrorCode.E4001, Level.ERROR),
        Arguments.of("그 밖의 5xx → E4001", http(500, "E4001"), ErrorCode.E4001, Level.ERROR));
  }

  @ParameterizedTest(name = "워커 {0}")
  @MethodSource
  void recommend(String name, AiError error, ErrorCode code, Level level) {
    assertThat(AiErrorMapper.recommend(error))
        .isEqualTo(new AiErrorMapper.RecommendFailure(code, level));
  }

  @Test
  void recommendRetriesOnlyConnectionAnd503() {
    assertThat(AiErrorMapper.recommendRetryable(AiError.of(AiError.Kind.CONNECTION))).isTrue();
    assertThat(AiErrorMapper.recommendRetryable(http(503, "E3003"))).isTrue();
    assertThat(AiErrorMapper.recommendRetryable(http(504, "E3002"))).isFalse();
    assertThat(AiErrorMapper.recommendRetryable(http(422, "E4002"))).isFalse();
    assertThat(AiErrorMapper.recommendRetryable(AiError.of(AiError.Kind.TIMEOUT))).isFalse();
    assertThat(AiErrorMapper.recommendRetryable(AiError.invalid(java.util.List.of()))).isFalse();
  }
}
