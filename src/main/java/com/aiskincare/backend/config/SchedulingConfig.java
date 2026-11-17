package com.aiskincare.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 워커 폴링·헬스체크·장기 대기 점검·토큰 정리 배치. 워커 한 번이 최대 130초를 붙잡을 수 있어 스레드를 여럿 둔다(워커 자체는 동시성 1). 종료 때 진행 중 작업을
 * 기다리지 않는다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
  @Bean
  ThreadPoolTaskScheduler taskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(4);
    scheduler.setThreadNamePrefix("jobs-");
    scheduler.setWaitForTasksToCompleteOnShutdown(false);
    scheduler.setRemoveOnCancelPolicy(true);
    return scheduler;
  }
}
