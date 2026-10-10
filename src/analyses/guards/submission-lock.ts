import { Injectable } from '@nestjs/common';

/**
 * 처리 중 제출 잠금 [구현 결정] — 측정(약 3초) 동안은 아직 분석 행이 없어서
 * DB의 진행 중 검사로는 같은 사용자의 연속 제출을 막지 못한다. 인스턴스 메모리로 막는다.
 */
@Injectable()
export class SubmissionLock {
  private readonly users = new Set<number>();

  tryAcquire(userId: number): boolean {
    if (this.users.has(userId)) return false;
    this.users.add(userId);
    return true;
  }

  release(userId: number): void {
    this.users.delete(userId);
  }
}
