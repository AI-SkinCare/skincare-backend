package com.aiskincare.backend.common.error;

import com.aiskincare.backend.common.web.ErrorEnvelope;
import com.aiskincare.backend.common.web.PublicApi;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Spring 기본 /error(BasicErrorController)를 대신한다. 필터·Tomcat 단계 오류도 봉투로만 나가게 한다 (TRD §7.2). 직접 /error를
 * 부르면 없는 경로와 같이 404 E4003. @RestController가 아니라서 /api/v1 접두가 붙지 않는다.
 */
@Hidden
@PublicApi
@Controller
public class EnvelopeErrorController implements ErrorController {

  @RequestMapping("${server.error.path:/error}")
  @ResponseBody
  public ResponseEntity<ErrorEnvelope> error(HttpServletRequest request) {
    Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
    ErrorCode code =
        status instanceof Integer s ? GlobalExceptionHandler.codeForStatus(s) : ErrorCode.E4003;
    return GlobalExceptionHandler.respond(new ApiException(code));
  }
}
