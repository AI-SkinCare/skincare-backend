import { createHash } from 'node:crypto';

/** refresh_tokens.token_hash = SHA-256(토큰 문자열) hex. 원문은 저장하지 않는다 */
export function hashToken(token: string): string {
  return createHash('sha256').update(token, 'utf8').digest('hex');
}
