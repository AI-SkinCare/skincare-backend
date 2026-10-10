import { AddressInfo } from 'node:net';
import { Server, createServer } from 'node:http';
import { AiError } from './ai-error';
import { HttpAiClient } from './http-ai-client';

type Handler = (
  req: { url?: string; headers: Record<string, unknown> },
  body: string,
) => {
  status: number;
  body: string;
  contentType?: string;
  delayMs?: number;
};

/** 오류 분류(TRD §12.2)를 실제 HTTP로 확인하는 로컬 가짜 AI 서버 */
async function fakeAi(
  handler: Handler,
): Promise<{ url: string; server: Server; seen: Record<string, unknown>[] }> {
  const seen: Record<string, unknown>[] = [];
  const server = createServer((req, res) => {
    const chunks: Buffer[] = [];
    req.on('data', (c: Buffer) => chunks.push(c));
    req.on('end', () => {
      seen.push(req.headers);
      const out = handler(req, Buffer.concat(chunks).toString('utf8'));
      setTimeout(() => {
        res.writeHead(out.status, { 'Content-Type': out.contentType ?? 'application/json' });
        res.end(out.body);
      }, out.delayMs ?? 0);
    });
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address() as AddressInfo;
  return { url: `http://127.0.0.1:${port}`, server, seen };
}

const aiError = (code: string) =>
  JSON.stringify({ success: false, error: { code, message: 'x', detail: null } });
const client = (baseUrl: string, timeoutMs = 2000) =>
  new HttpAiClient({
    baseUrl,
    internalToken: 'internal-test-token',
    skinTimeoutMs: timeoutMs,
    recTimeoutMs: timeoutMs,
  });
const request = { metrics: null, survey: { gender: 'female' as const, ageBand: '20s' as const } };
const ctx = { requestId: 'req-123' };

async function caught(promise: Promise<unknown>): Promise<AiError> {
  try {
    await promise;
  } catch (error) {
    if (error instanceof AiError) return error;
    throw error;
  }
  throw new Error('예외가 나지 않았다');
}

describe('HttpAiClient 오류 분류 (TRD §12.2)', () => {
  let servers: Server[] = [];
  afterEach(async () => {
    await Promise.all(servers.map((s) => new Promise((r) => s.close(r))));
    servers = [];
  });
  const start = async (handler: Handler) => {
    const fake = await fakeAi(handler);
    servers.push(fake.server);
    return fake;
  };

  it('본문에 error.code가 있는 4xx/5xx → http (status, code)', async () => {
    const { url } = await start(() => ({ status: 401, body: aiError('E1001') }));
    const error = await caught(client(url).recommend(request, ctx));
    expect(error).toMatchObject({ kind: 'http', status: 401, code: 'E1001' });
  });

  it('error.code 없는 5xx(Cloudflare HTML 503) → connection — E3003으로 읽지 않는다', async () => {
    const { url } = await start(() => ({
      status: 503,
      body: '<html>cloudflare</html>',
      contentType: 'text/html',
    }));
    expect(await caught(client(url).recommend(request, ctx))).toMatchObject({
      kind: 'connection',
      status: 503,
    });
  });

  it('응답 전 연결 실패 → connection', async () => {
    const { url, server } = await start(() => ({ status: 200, body: '{}' }));
    await new Promise((r) => server.close(r));
    servers = [];
    expect(await caught(client(url).health())).toMatchObject({ kind: 'connection' });
  });

  it('우리 타임아웃 초과 → timeout', async () => {
    const { url } = await start(() => ({ status: 200, body: '{}', delayMs: 300 }));
    expect(await caught(client(url, 50).recommend(request, ctx))).toMatchObject({
      kind: 'timeout',
    });
  });

  it('2xx인데 JSON이 아님 → invalid_response', async () => {
    const { url } = await start(() => ({
      status: 200,
      body: 'not json',
      contentType: 'text/plain',
    }));
    expect(await caught(client(url).recommend(request, ctx))).toMatchObject({
      kind: 'invalid_response',
    });
  });

  it('2xx인데 스키마 위반 → invalid_response', async () => {
    const { url } = await start(() => ({ status: 200, body: JSON.stringify({ skin_type: 1 }) }));
    expect(await caught(client(url).recommend(request, ctx))).toMatchObject({
      kind: 'invalid_response',
    });
  });

  it('X-Internal-Token·X-Request-Id를 보낸다. /health에는 내부 토큰을 보내지 않는다', async () => {
    const { url, seen } = await start((req) =>
      req.url === '/health'
        ? {
            status: 200,
            body: JSON.stringify({
              status: 'ok',
              rec_loaded: true,
              skin_loaded: true,
              rec_backend: 'sllm',
              skin_backend: 'stub',
            }),
          }
        : { status: 401, body: aiError('E1001') },
    );
    await client(url).health();
    await caught(client(url).recommend(request, ctx));
    expect(seen[0]['x-internal-token']).toBeUndefined();
    expect(seen[1]['x-internal-token']).toBe('internal-test-token');
    expect(seen[1]['x-request-id']).toBe('req-123');
  });

  it('측정 요청은 multipart image(시그니처 Content-Type)·device_type', async () => {
    let body = '';
    const { url } = await start((_req, raw) => {
      body = raw;
      return { status: 422, body: aiError('E2001') };
    });
    const error = await caught(
      client(url).measureSkin(
        {
          image: Buffer.from([0x89, 0x50, 0x4e, 0x47]),
          mimeType: 'image/png',
          deviceType: 'smartphone',
        },
        ctx,
      ),
    );
    expect(error).toMatchObject({ kind: 'http', status: 422, code: 'E2001' });
    expect(body).toContain('name="image"; filename="image.png"');
    expect(body).toContain('Content-Type: image/png');
    expect(body).toContain('name="device_type"');
  });
});
