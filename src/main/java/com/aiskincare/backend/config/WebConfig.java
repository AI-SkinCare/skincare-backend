package com.aiskincare.backend.config;

import com.aiskincare.backend.analyses.AnalysisGuardInterceptor;
import com.aiskincare.backend.auth.AuthInterceptor;
import com.aiskincare.backend.common.web.CurrentUserArgumentResolver;
import com.aiskincare.backend.common.web.RequestIdFilter;
import com.aiskincare.backend.common.web.RequestLogFilter;
import com.aiskincare.backend.common.web.SecurityHeadersFilter;
import java.util.List;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
  private final AppProperties props;
  private final AuthInterceptor authInterceptor;
  private final AnalysisGuardInterceptor analysisGuardInterceptor;

  public WebConfig(
      AppProperties props,
      AuthInterceptor authInterceptor,
      AnalysisGuardInterceptor analysisGuardInterceptor) {
    this.props = props;
    this.authInterceptor = authInterceptor;
    this.analysisGuardInterceptor = analysisGuardInterceptor;
  }

  /** 전역 prefix /api/v1 — 우리 컨트롤러에만 (Swagger /api/docs는 prefix 밖) */
  @Override
  public void configurePathMatch(PathMatchConfigurer configurer) {
    configurer.addPathPrefix(
        Constants.API_PREFIX,
        HandlerTypePredicate.forBasePackage("com.aiskincare.backend")
            .and(HandlerTypePredicate.forAnnotation(RestController.class)));
  }

  /** 인증 → 요청 제한·대기열·AI 가용성 순서 (TRD §10.1). multipart는 lazy라 이 뒤에 파싱된다 */
  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(authInterceptor).order(0);
    registry.addInterceptor(analysisGuardInterceptor).order(1);
  }

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(new CurrentUserArgumentResolver());
  }

  /** iOS 앱은 Origin을 보내지 않아 CORS와 무관. 웹에서 부를 때만 의미가 있다 */
  @Override
  public void addCorsMappings(CorsRegistry registry) {
    if (props.corsOrigin() != null && !props.corsOrigin().isBlank()) {
      registry
          .addMapping("/api/**")
          .allowedOrigins(props.corsOrigin())
          .allowedMethods("GET", "POST", "DELETE")
          .allowCredentials(true);
    }
  }

  @Bean
  FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
    var bean = new FilterRegistrationBean<>(new RequestIdFilter());
    bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return bean;
  }

  @Bean
  FilterRegistrationBean<RequestLogFilter> requestLogFilter() {
    var bean = new FilterRegistrationBean<>(new RequestLogFilter());
    bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
    return bean;
  }

  @Bean
  FilterRegistrationBean<SecurityHeadersFilter> securityHeadersFilter() {
    var bean = new FilterRegistrationBean<>(new SecurityHeadersFilter());
    bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
    return bean;
  }
}
