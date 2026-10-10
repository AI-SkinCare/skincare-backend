import { ExecutionContext, createParamDecorator } from '@nestjs/common';
import type { AppRequest, AuthUser } from '../../common/http/app-request';

/** JwtAuthGuard 뒤에서만 쓴다 */
export const CurrentUser = createParamDecorator(
  (_data: unknown, ctx: ExecutionContext): AuthUser => {
    const user = ctx.switchToHttp().getRequest<AppRequest>().user;
    if (!user) throw new Error('CurrentUser는 JwtAuthGuard 뒤에서만 쓴다');
    return user;
  },
);
