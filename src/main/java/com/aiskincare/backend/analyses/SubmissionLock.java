package com.aiskincare.backend.analyses;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 처리 중 제출 잠금 [구현 결정] — 측정(약 3초) 동안은 아직 분석 행이 없어서 DB의 진행 중 검사로는 같은 사용자의 연속 제출을 막지 못한다. 인스턴스 메모리로
 * 막는다.
 */
@Component
public class SubmissionLock {
  private final Set<Long> users = ConcurrentHashMap.newKeySet();

  public boolean tryAcquire(long userId) {
    return users.add(userId);
  }

  public void release(long userId) {
    users.remove(userId);
  }
}
