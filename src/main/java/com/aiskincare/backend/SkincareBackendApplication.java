package com.aiskincare.backend;

import java.util.Arrays;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SkincareBackendApplication {

  public static void main(String[] args) {
    SpringApplication app = new SpringApplication(SkincareBackendApplication.class);
    boolean seed = Arrays.asList(args).contains("--seed");
    if (seed) {
      // 시드만 넣고 끝낸다: 웹 서버·워커를 띄우지 않는다
      app.setWebApplicationType(WebApplicationType.NONE);
      app.setDefaultProperties(Map.of("app.background-jobs.enabled", "false"));
    }
    var context = app.run(args);
    if (seed) System.exit(SpringApplication.exit(context));
  }
}
