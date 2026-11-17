package com.aiskincare.backend.analyses;

import com.aiskincare.backend.common.error.ApiException;
import com.aiskincare.backend.common.error.ErrorCode;
import com.aiskincare.backend.common.validation.FieldIssue;
import com.aiskincare.backend.config.Constants;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

/** 검증을 마친 분석 제출. 이미지 바이트는 AI 분류 직후 {@link #releaseImage()}로 참조를 끊는다 (TRD §10.1 처리 순서 8). */
public final class Submission {
  private byte[] image;
  private final String mimeType;
  private final NormalizedSurvey survey;

  private Submission(byte[] image, String mimeType, NormalizedSurvey survey) {
    this.image = image;
    this.mimeType = mimeType;
    this.survey = survey;
  }

  public byte[] image() {
    if (image == null) throw new IllegalStateException("이미지가 이미 해제됐습니다");
    return image;
  }

  public void releaseImage() {
    image = null;
  }

  public String mimeType() {
    return mimeType;
  }

  public NormalizedSurvey survey() {
    return survey;
  }

  /**
   * TRD §10.1 처리 순서 6 — 형식 검증. image 없음 → E4002 / 시그니처가 JPEG·PNG 아님 → 415 E2004 / 짧은 변 &lt; 720px →
   * 422 E2002 / survey → E4002. 이미지 내용·설문 원문은 오류 detail에 넣지 않는다. deviceType은 받지 않는다(개정 B.1).
   */
  public static Submission validate(MultipartFile file, String surveyRaw) {
    if (file == null || file.isEmpty()) {
      List<FieldIssue> fields = new ArrayList<>();
      fields.add(new FieldIssue("image", "required"));
      fields.addAll(SurveyParser.parse(surveyRaw).errors());
      throw FieldIssue.exception(fields);
    }
    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException e) {
      throw new ApiException(ErrorCode.E4002);
    }
    String mimeType = ImageInspector.detectMimeType(bytes);
    if (mimeType == null) throw new ApiException(ErrorCode.E2004);
    var dimensions = ImageInspector.dimensions(bytes, mimeType);
    if (dimensions == null) throw new ApiException(ErrorCode.E2004);
    if (dimensions.shortSide() < Constants.MIN_IMAGE_SHORT_SIDE_PX) {
      throw ApiException.withMessage(ErrorCode.E2002, ErrorCode.LOW_RESOLUTION_MESSAGE);
    }
    var survey = SurveyParser.parse(surveyRaw);
    if (!survey.ok()) throw FieldIssue.exception(survey.errors());
    return new Submission(bytes, mimeType, survey.survey());
  }
}
