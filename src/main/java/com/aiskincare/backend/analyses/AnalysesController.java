package com.aiskincare.backend.analyses;

import com.aiskincare.backend.aiclient.AiModels.AiCallContext;
import com.aiskincare.backend.aiclient.FixtureScenarioStore;
import com.aiskincare.backend.analyses.dto.AnalysisResponses.AnalysisList;
import com.aiskincare.backend.analyses.dto.AnalysisResponses.AnalysisView;
import com.aiskincare.backend.analyses.dto.AnalysisSubmissionForm;
import com.aiskincare.backend.common.error.ApiException;
import com.aiskincare.backend.common.error.ErrorCode;
import com.aiskincare.backend.common.util.Ulid;
import com.aiskincare.backend.common.web.ApiEnvelope;
import com.aiskincare.backend.common.web.ApiErrorCodes;
import com.aiskincare.backend.common.web.AuthUser;
import com.aiskincare.backend.common.web.CurrentUser;
import com.aiskincare.backend.common.web.RequestIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "analyses")
@RestController
@RequestMapping("/analyses")
public class AnalysesController {
  private final AnalysesService analyses;
  private final FixtureScenarioStore scenarios;

  public AnalysesController(AnalysesService analyses, FixtureScenarioStore scenarios) {
    this.analyses = analyses;
    this.scenarios = scenarios;
  }

  /**
   * TRD §10.1 처리 순서: 인증 → 요청 제한(E4004) → 대기열 상한(E3003) → AI 가용성(E3001) → 파일 수신(메모리, 413 E2003) → 형식
   * 검증 → AI 이미지 분류 → 저장(QUEUED) → 202. 인터셉터가 먼저 돌고 multipart는 lazy라 제한에 걸리면 파일을 읽지 않는다.
   */
  @Operation(summary = "분석 요청 — 이미지 분류 + 추천 대기열 등록 (FR-ANA-01·02·05)")
  @AnalysisGuards
  @ApiErrorCodes({
    ErrorCode.E4004,
    ErrorCode.E3003,
    ErrorCode.E3001,
    ErrorCode.E2003,
    ErrorCode.E2004,
    ErrorCode.E2001,
    ErrorCode.E2002,
    ErrorCode.E4002,
    ErrorCode.E3002,
    ErrorCode.E4001
  })
  @ResponseStatus(HttpStatus.ACCEPTED)
  // consumes를 두지 않는다: Content-Type 불일치가 인증(401)·요청 제한보다 먼저 판정되지 않게. multipart가 아니면 image 누락 → E4002
  @io.swagger.v3.oas.annotations.parameters.RequestBody(
      required = true,
      content =
          @Content(
              mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
              schema = @Schema(implementation = AnalysisSubmissionForm.class)))
  @PostMapping
  public ApiEnvelope<AnalysisView> create(
      @CurrentUser AuthUser user,
      @Parameter(hidden = true) @RequestPart(value = "image", required = false) MultipartFile image,
      @Parameter(hidden = true) @RequestParam(value = "survey", required = false) String survey,
      @Parameter(in = ParameterIn.HEADER, description = "개발·테스트용 (production에선 무시)")
          @RequestHeader(value = FixtureScenarioStore.HEADER, required = false)
          String scenario,
      HttpServletRequest request) {
    Submission submission = Submission.validate(image, survey);
    var ctx = new AiCallContext(RequestIdFilter.of(request), scenarios.fromHeader(scenario));
    return ApiEnvelope.ok(analyses.create(user.id(), submission, ctx));
  }

  @Operation(summary = "이력 목록 — 최신순 (FR-HIS-01)")
  @ApiErrorCodes(ErrorCode.E4002)
  @GetMapping
  public ApiEnvelope<AnalysisList> list(
      @CurrentUser AuthUser user,
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
    return ApiEnvelope.ok(analyses.list(user.id(), page, size));
  }

  @Operation(summary = "분석 결과·진행 상태 — 분류 + 추천 또는 보류 (FR-ANA-03, FR-REP-01·02)")
  @ApiErrorCodes(ErrorCode.E4003)
  @GetMapping("/{id}")
  public ApiEnvelope<AnalysisView> findOne(@CurrentUser AuthUser user, @PathVariable String id) {
    return ApiEnvelope.ok(analyses.findOne(user.id(), ulid(id)));
  }

  @Operation(summary = "실패한 추천 재요청 — 저장한 분류로 추천만 다시 (FR-ANA-04)")
  @AnalysisGuards(checkAiAvailability = false)
  @ApiErrorCodes({ErrorCode.E4004, ErrorCode.E3003, ErrorCode.E4003, ErrorCode.E4005})
  @ResponseStatus(HttpStatus.ACCEPTED)
  @PostMapping("/{id}/recommendation/retry")
  public ApiEnvelope<AnalysisView> retry(
      @CurrentUser AuthUser user,
      @PathVariable String id,
      @Parameter(in = ParameterIn.HEADER, description = "개발·테스트용 (production에선 무시)")
          @RequestHeader(value = FixtureScenarioStore.HEADER, required = false)
          String scenario) {
    return ApiEnvelope.ok(analyses.retry(user.id(), ulid(id), scenarios.fromHeader(scenario)));
  }

  @Operation(summary = "이력 삭제 (FR-HIS-03)")
  @ApiErrorCodes(ErrorCode.E4003)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @DeleteMapping("/{id}")
  public void delete(@CurrentUser AuthUser user, @PathVariable String id) {
    analyses.delete(user.id(), ulid(id));
  }

  /** 경로의 분석 id가 ULID 형식이 아니면 404 E4003 — 422가 아니다 (존재 여부를 흘리지 않음, TRD §7.4) */
  private static String ulid(String id) {
    if (!Ulid.isValid(id)) throw new ApiException(ErrorCode.E4003);
    return id;
  }
}
