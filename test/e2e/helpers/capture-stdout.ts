/** pino는 process.stdout으로 쓴다 (logger.config.ts). 그동안의 출력을 모아 JSON 줄로 돌려준다. */
export async function captureStdout(
  fn: () => Promise<void>,
): Promise<{ raw: string; lines: Record<string, unknown>[] }> {
  const chunks: string[] = [];
  const original = process.stdout.write.bind(process.stdout);
  process.stdout.write = ((chunk: string | Uint8Array) => {
    chunks.push(typeof chunk === 'string' ? chunk : Buffer.from(chunk).toString('utf8'));
    return true;
  }) as typeof process.stdout.write;
  try {
    await fn();
    // pino-http의 'request completed'는 응답이 끝난 뒤에 찍히므로 한 틱 기다린다
    await new Promise((r) => setTimeout(r, 20));
  } finally {
    process.stdout.write = original;
  }
  const raw = chunks.join('');
  const lines = raw
    .split('\n')
    .filter((l) => l.startsWith('{'))
    .map((l) => JSON.parse(l) as Record<string, unknown>);
  return { raw, lines };
}
