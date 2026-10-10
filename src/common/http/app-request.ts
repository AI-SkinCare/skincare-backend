import type { Request } from 'express';

/** JwtStrategy.validate가 req.user에 넣는 값 */
export interface AuthUser {
  id: number;
}

/** requestIdMiddleware·JwtAuthGuard를 거친 요청 */
export type AppRequest = Request & { id: string; user?: AuthUser };
