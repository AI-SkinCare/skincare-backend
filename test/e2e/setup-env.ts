import { E2E_ENV, resolveE2eDatabaseUrl } from './e2e-env';

process.env.DATABASE_URL = resolveE2eDatabaseUrl();
for (const [key, value] of Object.entries(E2E_ENV)) {
  // CI가 준 값(JWT 등)은 그대로 둔다. NODE_ENV·AI_MODE는 항상 고정
  if (key === 'NODE_ENV' || key === 'AI_MODE' || process.env[key] === undefined) {
    process.env[key] = value;
  }
}
