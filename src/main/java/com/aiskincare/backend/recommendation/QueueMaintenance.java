package com.aiskincare.backend.recommendation;

import com.aiskincare.backend.aiclient.FixtureScenarioStore;
import com.aiskincare.backend.config.AppProperties;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** TRD §13.5 장기 대기 만료, §13.6 재시작 복구 */
@Service
public class QueueMaintenance {
  private static final Logger log = LoggerFactory.getLogger(QueueMaintenance.class);

  private final RecommendationQueueRepository queue;
  private final FixtureScenarioStore scenarios;
  private final AppProperties props;

  public QueueMaintenance(
      RecommendationQueueRepository queue, FixtureScenarioStore scenarios, AppProperties props) {
    this.queue = queue;
    this.scenarios = scenarios;
    this.props = props;
  }

  /** 기동 시 recommending → queued (queued_at 유지로 순서 보존) */
  public int recoverInterrupted() {
    int count = queue.recoverInterrupted();
    if (count > 0) log.atWarn().addKeyValue("count", count).log("queue_recovered");
    return count;
  }

  /** queued가 REC_QUEUE_MAX_WAIT_MIN분을 넘으면 failed E3001 */
  public int expireStale() {
    try {
      List<String> ids = queue.expireStale(props.queue().maxWaitMin());
      ids.forEach(scenarios::forget);
      if (!ids.isEmpty()) {
        log.atWarn()
            .addKeyValue("count", ids.size())
            .addKeyValue("analysisIds", ids)
            .log("queue_expired");
      }
      return ids.size();
    } catch (RuntimeException e) {
      log.error("queue_expiry_failed", e);
      return 0;
    }
  }
}
