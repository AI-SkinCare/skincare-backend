import { Transform, plainToInstance } from 'class-transformer';
import {
  IsBoolean,
  IsIn,
  IsInt,
  IsNotEmpty,
  IsOptional,
  IsString,
  IsUrl,
  Max,
  Min,
  validateSync,
} from 'class-validator';
import { FIXTURE_SCENARIOS, FixtureScenario } from '../common/enums';

export const APP_CONFIG = Symbol('APP_CONFIG');

export type NodeEnv = 'development' | 'test' | 'production';
export type AiMode = 'fixture' | 'real';
const LOG_LEVELS = ['fatal', 'error', 'warn', 'info', 'debug', 'trace', 'silent'] as const;
export type LogLevel = (typeof LOG_LEVELS)[number];

/** 타입 있는 설정. 서비스는 process.env를 직접 읽지 않고 이것을 주입받는다. */
export interface AppConfig {
  nodeEnv: NodeEnv;
  isProduction: boolean;
  port: number;
  databaseUrl: string;
  jwt: { accessSecret: string; refreshSecret: string };
  corsOrigin?: string;
  ai: {
    mode: AiMode;
    baseUrl?: string;
    internalToken?: string;
    skinTimeoutMs: number;
    recTimeoutMs: number;
    healthIntervalMs: number;
    fixtureScenario: FixtureScenario;
  };
  queue: { limit: number; maxWaitMin: number };
  analysisDailyLimit: number;
  loginRatePerMin: number;
  trustProxy: boolean;
  cookieSecure: boolean;
  logLevel: LogLevel;
  seedPassword?: string;
}

const toInt = ({ value }: { value: unknown }) =>
  typeof value === 'string' && /^-?\d+$/.test(value.trim()) ? Number(value) : value;
const toBool = ({ value }: { value: unknown }) =>
  value === 'true' ? true : value === 'false' ? false : value;

class EnvironmentVariables {
  @IsIn(['development', 'test', 'production'])
  NODE_ENV: NodeEnv = 'development';

  @Transform(toInt)
  @IsInt()
  @Min(1)
  @Max(65535)
  PORT = 3000;

  @IsString()
  @IsNotEmpty()
  DATABASE_URL!: string;

  @IsString()
  @IsNotEmpty()
  JWT_ACCESS_SECRET!: string;

  @IsString()
  @IsNotEmpty()
  JWT_REFRESH_SECRET!: string;

  @IsOptional()
  @IsString()
  CORS_ORIGIN?: string;

  @IsIn(['fixture', 'real'])
  AI_MODE: AiMode = 'fixture';

  @IsOptional()
  @IsUrl({ require_tld: false, require_protocol: true, protocols: ['http', 'https'] })
  AI_BASE_URL?: string;

  @IsOptional()
  @IsString()
  AI_INTERNAL_TOKEN?: string;

  @Transform(toInt)
  @IsInt()
  @Min(1)
  AI_SKIN_TIMEOUT_MS = 20000;

  @Transform(toInt)
  @IsInt()
  @Min(1)
  AI_REC_TIMEOUT_MS = 130000;

  @Transform(toInt)
  @IsInt()
  @Min(1000)
  AI_HEALTH_INTERVAL_MS = 30000;

  @Transform(toInt)
  @IsInt()
  REC_WORKER_CONCURRENCY = 1;

  @Transform(toInt)
  @IsInt()
  @Min(1)
  REC_QUEUE_LIMIT = 10;

  @Transform(toInt)
  @IsInt()
  @Min(1)
  REC_QUEUE_MAX_WAIT_MIN = 60;

  @Transform(toInt)
  @IsInt()
  @Min(1)
  ANALYSIS_DAILY_LIMIT = 20;

  @Transform(toInt)
  @IsInt()
  @Min(1)
  LOGIN_RATE_PER_MIN = 10;

  @Transform(toBool)
  @IsBoolean()
  TRUST_PROXY = true;

  @IsIn(FIXTURE_SCENARIOS)
  AI_FIXTURE_SCENARIO: FixtureScenario = 'ok';

  @Transform(toBool)
  @IsBoolean()
  COOKIE_SECURE = true;

  @IsIn(LOG_LEVELS)
  LOG_LEVEL: LogLevel = 'info';

  @IsOptional()
  @IsString()
  SEED_PASSWORD?: string;
}

/**
 * TRD §6 기동 시 검증. 문제가 있으면 예외를 던져 즉시 종료시킨다.
 * 오류 메시지에는 변수 이름만 넣고 값은 넣지 않는다 (비밀값 유출 방지).
 */
export function loadAppConfig(raw: Record<string, unknown>): AppConfig {
  const cleaned = Object.fromEntries(
    Object.entries(raw).filter(([, value]) => value !== undefined && value !== ''),
  );
  const env = plainToInstance(EnvironmentVariables, cleaned);
  const problems = validateSync(env, { skipMissingProperties: false }).map(
    (error) => `${error.property}: ${Object.keys(error.constraints ?? {}).join(', ')}`,
  );

  if (env.AI_MODE === 'real' && (!env.AI_BASE_URL || !env.AI_INTERNAL_TOKEN)) {
    problems.push('AI_MODE=real이면 AI_BASE_URL과 AI_INTERNAL_TOKEN이 필요합니다');
  }
  if (env.JWT_ACCESS_SECRET && env.JWT_ACCESS_SECRET === env.JWT_REFRESH_SECRET) {
    problems.push('JWT_ACCESS_SECRET과 JWT_REFRESH_SECRET은 서로 달라야 합니다');
  }
  if (env.REC_WORKER_CONCURRENCY !== 1) {
    problems.push('REC_WORKER_CONCURRENCY는 1이어야 합니다 (GPU 생성이 직렬)');
  }
  if (problems.length > 0) {
    throw new Error(`환경변수 검증 실패:\n- ${problems.join('\n- ')}`);
  }

  const isProduction = env.NODE_ENV === 'production';
  return {
    nodeEnv: env.NODE_ENV,
    isProduction,
    port: env.PORT,
    databaseUrl: env.DATABASE_URL,
    jwt: { accessSecret: env.JWT_ACCESS_SECRET, refreshSecret: env.JWT_REFRESH_SECRET },
    corsOrigin: env.CORS_ORIGIN,
    ai: {
      mode: env.AI_MODE,
      baseUrl: env.AI_BASE_URL?.replace(/\/+$/, ''),
      internalToken: env.AI_INTERNAL_TOKEN,
      skinTimeoutMs: env.AI_SKIN_TIMEOUT_MS,
      recTimeoutMs: env.AI_REC_TIMEOUT_MS,
      healthIntervalMs: env.AI_HEALTH_INTERVAL_MS,
      fixtureScenario: env.AI_FIXTURE_SCENARIO,
    },
    queue: { limit: env.REC_QUEUE_LIMIT, maxWaitMin: env.REC_QUEUE_MAX_WAIT_MIN },
    analysisDailyLimit: env.ANALYSIS_DAILY_LIMIT,
    loginRatePerMin: env.LOGIN_RATE_PER_MIN,
    trustProxy: env.TRUST_PROXY,
    // production은 항상 Secure 쿠키
    cookieSecure: isProduction ? true : env.COOKIE_SECURE,
    logLevel: env.LOG_LEVEL,
    seedPassword: env.SEED_PASSWORD,
  };
}
