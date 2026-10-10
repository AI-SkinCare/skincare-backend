import { Controller, Delete, Get, HttpCode, Inject, Res, UseGuards } from '@nestjs/common';
import { ApiBearerAuth, ApiNoContentResponse, ApiOperation, ApiTags } from '@nestjs/swagger';
import type { Response } from 'express';
import { CurrentUser } from '../auth/decorators/current-user.decorator';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { clearRefreshCookie } from '../auth/refresh-cookie';
import { ErrorCode } from '../common/errors/error-codes';
import type { AuthUser } from '../common/http/app-request';
import { ApiEnvelopeResponse, ApiErrorResponses } from '../common/swagger/api-envelope.decorator';
import { APP_CONFIG, AppConfig } from '../config/app-config';
import { UserMeDto } from './dto/user-me.dto';
import { UsersService } from './users.service';

@ApiTags('users')
@ApiBearerAuth()
@Controller('users')
@UseGuards(JwtAuthGuard)
@ApiErrorResponses(ErrorCode.E1001)
export class UsersController {
  constructor(
    private readonly users: UsersService,
    @Inject(APP_CONFIG) private readonly config: AppConfig,
  ) {}

  @Get('me')
  @ApiOperation({ summary: '내 정보' })
  @ApiEnvelopeResponse({ status: 200, type: UserMeDto })
  me(@CurrentUser() user: AuthUser): Promise<UserMeDto> {
    return this.users.me(user.id);
  }

  @Delete('me')
  @HttpCode(204)
  @ApiOperation({ summary: '회원 탈퇴 — 모든 데이터 즉시 삭제 (FR-AUTH-04)' })
  @ApiNoContentResponse()
  async remove(
    @CurrentUser() user: AuthUser,
    @Res({ passthrough: true }) res: Response,
  ): Promise<void> {
    await this.users.remove(user.id);
    clearRefreshCookie(res, this.config.cookieSecure);
  }
}
