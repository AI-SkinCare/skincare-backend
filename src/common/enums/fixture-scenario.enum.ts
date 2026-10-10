// TRD §12.7 — AI_MODE=fixture 시나리오
export const FIXTURE_SCENARIOS = ['ok', 'no-face', 'rec-timeout', 'rec-slow', 'ai-down'] as const;
export type FixtureScenario = (typeof FIXTURE_SCENARIOS)[number];

export function isFixtureScenario(value: unknown): value is FixtureScenario {
  return typeof value === 'string' && (FIXTURE_SCENARIOS as readonly string[]).includes(value);
}
