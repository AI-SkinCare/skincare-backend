package com.aiskincare.backend.aiclient;

import com.aiskincare.backend.common.enums.FixtureScenario;
import com.aiskincare.backend.config.AppProperties;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 워커는 요청 헤더를 볼 수 없으므로 POST /analyses·retry에서 받은 X-Fixture-Scenario를 분석 id별로 기억해 둔다 (TRD §12.7).
 * 인스턴스 메모리라 재시작하면 사라져도 된다. production에서는 헤더를 무시한다.
 */
@Component
public class FixtureScenarioStore {
  public static final String HEADER = "X-Fixture-Scenario";

  private final Map<String, FixtureScenario> byAnalysis = new ConcurrentHashMap<>();
  private final boolean production;

  public FixtureScenarioStore(AppProperties props) {
    this.production = props.isProduction();
  }

  /** 헤더 값 → 시나리오. production이거나 모르는 값이면 null (env 기본값 사용) */
  public FixtureScenario fromHeader(String value) {
    return production ? null : FixtureScenario.fromCode(value);
  }

  /** 요청마다 갱신: 헤더가 없으면 지워서 env 값을 쓰게 한다 */
  public void remember(String analysisId, FixtureScenario scenario) {
    if (scenario != null) byAnalysis.put(analysisId, scenario);
    else byAnalysis.remove(analysisId);
  }

  public FixtureScenario get(String analysisId) {
    return byAnalysis.get(analysisId);
  }

  public void forget(String analysisId) {
    byAnalysis.remove(analysisId);
  }
}
