package com.aiskincare.backend.common.error;

import com.aiskincare.backend.common.validation.FieldIssue;
import com.aiskincare.backend.common.web.ErrorEnvelope;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import tools.jackson.databind.DatabindException;

/**
 * 모든 예외를 실패 봉투로 바꾸는 유일한 핸들러 (TRD §7.2). Spring·라이브러리 예외가 원래 모양 그대로 나가지 않게 코드를 정한다. 검증 실패는 400이 아니라
 * 422 E4002.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<ErrorEnvelope> handleApi(ApiException e) {
    return respond(e);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorEnvelope> handleBodyValidation(MethodArgumentNotValidException e) {
    List<FieldIssue> fields = new ArrayList<>();
    for (FieldError error : e.getBindingResult().getFieldErrors()) {
      if (fields.stream().anyMatch(f -> f.field().equals(error.getField()))) continue;
      fields.add(
          new FieldIssue(
              error.getField(), FieldIssue.reasonFor(error.getCode(), error.getRejectedValue())));
    }
    return respond(FieldIssue.exception(fields));
  }

  @ExceptionHandler(HandlerMethodValidationException.class)
  public ResponseEntity<ErrorEnvelope> handleParamValidation(HandlerMethodValidationException e) {
    List<FieldIssue> fields = new ArrayList<>();
    e.getParameterValidationResults()
        .forEach(
            result -> {
              String name = result.getMethodParameter().getParameterName();
              String code =
                  result.getResolvableErrors().isEmpty()
                      ? "invalid"
                      : String.valueOf(result.getResolvableErrors().get(0).getCodes()[0])
                          .replaceAll("^.*\\.", "");
              fields.add(new FieldIssue(name, FieldIssue.reasonFor(code, result.getArgument())));
            });
    return respond(FieldIssue.exception(fields));
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ErrorEnvelope> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
    return respond(FieldIssue.exception(List.of(new FieldIssue(e.getName(), "invalid_type"))));
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<ErrorEnvelope> handleMissingParam(
      MissingServletRequestParameterException e) {
    return respond(FieldIssue.exception(List.of(new FieldIssue(e.getParameterName(), "required"))));
  }

  /** 깨진 JSON·타입이 틀린 필드 → 422 E4002. 원문은 detail에 넣지 않는다 */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ErrorEnvelope> handleUnreadable(HttpMessageNotReadableException e) {
    if (e.getCause() instanceof DatabindException databind && !databind.getPath().isEmpty()) {
      String field =
          databind.getPath().stream()
              .map(ref -> ref.getPropertyName() != null ? ref.getPropertyName() : "")
              .filter(name -> !name.isEmpty())
              .reduce((a, b) -> a + "." + b)
              .orElse("body");
      return respond(FieldIssue.exception(List.of(new FieldIssue(field, "invalid_type"))));
    }
    return respond(new ApiException(ErrorCode.E4002));
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<ErrorEnvelope> handleTooLarge(MaxUploadSizeExceededException e) {
    return respond(new ApiException(ErrorCode.E2003));
  }

  @ExceptionHandler(MultipartException.class)
  public ResponseEntity<ErrorEnvelope> handleMultipart(MultipartException e) {
    return respond(new ApiException(ErrorCode.E4002));
  }

  @ExceptionHandler({NoHandlerFoundException.class, HttpRequestMethodNotSupportedException.class})
  public ResponseEntity<ErrorEnvelope> handleNotFound(Exception e) {
    return respond(new ApiException(ErrorCode.E4003));
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<ErrorEnvelope> handleMediaType(HttpMediaTypeNotSupportedException e) {
    return respond(new ApiException(ErrorCode.E4002));
  }

  /** 그 밖의 Spring MVC 예외(ErrorResponse)는 HTTP 상태로 코드를 정하고, 나머지는 500 E4001 + ERROR 로그 */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorEnvelope> handleUnknown(Exception e) {
    if (e instanceof ErrorResponse errorResponse) {
      ErrorCode code = codeForStatus(errorResponse.getStatusCode().value());
      if (code == ErrorCode.E4001) logUnhandled(e);
      return respond(new ApiException(code));
    }
    logUnhandled(e);
    return respond(new ApiException(ErrorCode.E4001));
  }

  private void logUnhandled(Exception e) {
    // 스택·requestId(MDC)만. 요청 본문은 남기지 않는다
    log.error("unhandled_exception", e);
  }

  static ErrorCode codeForStatus(int status) {
    return switch (status) {
      case 401 -> ErrorCode.E1001;
      case 403 -> ErrorCode.E1002;
      case 404, 405 -> ErrorCode.E4003;
      case 413 -> ErrorCode.E2003;
      case 415 -> ErrorCode.E2004;
      case 429 -> ErrorCode.E4004;
      default -> status >= 400 && status < 500 ? ErrorCode.E4002 : ErrorCode.E4001;
    };
  }

  static ResponseEntity<ErrorEnvelope> respond(ApiException e) {
    var builder = ResponseEntity.status(e.code().status());
    if (e.retryAfterSec() != null) {
      builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSec()));
    }
    // E4001 detail은 항상 null — 내부 정보를 흘리지 않는다
    Object detail = e.code() == ErrorCode.E4001 ? null : e.detail();
    return builder.body(ErrorEnvelope.of(e.code().name(), e.userMessage(), detail));
  }
}
