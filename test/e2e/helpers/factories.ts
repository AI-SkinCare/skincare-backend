import { randomInt } from 'node:crypto';
import * as bcrypt from 'bcrypt';
import type { Response } from 'supertest';
import { DataSource } from 'typeorm';
import { User } from '../../../src/users/user.entity';
import { solidPng } from '../../fixtures/images';
import type { TestApp } from './test-app';

export const PASSWORD = 'Skin!2026';
let fastHash: Promise<string> | undefined;

let seq = 0;
export const uniqueEmail = (prefix = 'user') => `${prefix}${Date.now()}${++seq}@example.com`;
/** 로그인 제한(IP당 분당 10회)에 걸리지 않게 호출마다 다른 IP */
export const randomIp = () => `198.51.${randomInt(0, 255)}.${randomInt(1, 255)}`;

/** API를 거치지 않고 사용자를 만든다 (가입 API 테스트가 아닌 곳에서 bcrypt cost 12를 피하려고 cost 4) */
export async function createUser(dataSource: DataSource, email = uniqueEmail()): Promise<User> {
  fastHash ??= bcrypt.hash(PASSWORD, 4);
  const now = new Date();
  return dataSource.getRepository(User).save({
    email,
    passwordHash: await fastHash,
    nickname: '테스터',
    termsAgreedAt: now,
    privacyAgreedAt: now,
    imageConsentAt: now,
    over14ConfirmedAt: now,
  });
}

export interface LoggedIn {
  user: User;
  accessToken: string;
  /** Set-Cookie의 refresh_token 값 */
  refreshCookie: string;
  auth: { Authorization: string };
}

export function refreshCookieOf(res: Response): string {
  const raw = res.headers['set-cookie'] as unknown as string[] | undefined;
  const cookie = raw?.find((c) => c.startsWith('refresh_token='));
  if (!cookie) throw new Error('refresh_token 쿠키가 없다');
  return cookie.split(';')[0].slice('refresh_token='.length);
}

export async function login(t: TestApp, user: User): Promise<LoggedIn> {
  const res = await t
    .http()
    .post('/api/v1/auth/login')
    .set('CF-Connecting-IP', randomIp())
    .send({ email: user.email, password: PASSWORD })
    .expect(200);
  const accessToken = (res.body as { data: { accessToken: string } }).data.accessToken;
  return {
    user,
    accessToken,
    refreshCookie: refreshCookieOf(res),
    auth: { Authorization: `Bearer ${accessToken}` },
  };
}

export async function userWithToken(t: TestApp): Promise<LoggedIn> {
  return login(t, await createUser(t.dataSource));
}

export const DEFAULT_SURVEY = {
  gender: 'female',
  ageBand: '20s',
  skinType: 'combination',
  sleepHours: 6,
  waterIntakeLevel: 'low',
  sunExposureLevel: 'high',
  stressLevel: 'medium',
  concerns: ['pore', 'pigmentation'],
  currentProducts: ['토너', '수분크림'],
  note: '여름에 번들거림이 심해요',
};

const FACE = solidPng(1080, 1440);

export interface SubmitOptions {
  image?: Buffer;
  filename?: string;
  survey?: unknown;
  deviceType?: string | null;
  scenario?: string;
}

/** POST /analyses (multipart) */
export function submit(t: TestApp, who: LoggedIn, options: SubmitOptions = {}) {
  let req = t.http().post('/api/v1/analyses').set(who.auth);
  if (options.scenario) req = req.set('X-Fixture-Scenario', options.scenario);
  req = req.attach('image', options.image ?? FACE, options.filename ?? 'face.png');
  const survey = options.survey === undefined ? DEFAULT_SURVEY : options.survey;
  if (survey !== null)
    req = req.field('survey', typeof survey === 'string' ? survey : JSON.stringify(survey));
  if (options.deviceType !== null)
    req = req.field('deviceType', options.deviceType ?? 'smartphone');
  return req;
}

export async function submitOk(
  t: TestApp,
  who: LoggedIn,
  options: SubmitOptions = {},
): Promise<string> {
  const res = await submit(t, who, options).expect(202);
  return (res.body as { data: { analysisId: string } }).data.analysisId;
}

export async function statusOf(t: TestApp, id: string): Promise<string> {
  const rows: { status: string }[] = await t.dataSource.query(
    'SELECT status FROM analyses WHERE id = $1',
    [id],
  );
  return rows[0]?.status;
}
