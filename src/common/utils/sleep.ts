/** 지연 주입 — 워커 재시도 대기·fixture 지연. 테스트는 이 토큰을 바꿔 실제로 기다리지 않는다. */
export const SLEEP = Symbol('SLEEP');

export type Sleep = (ms: number) => Promise<void>;

export const realSleep: Sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
