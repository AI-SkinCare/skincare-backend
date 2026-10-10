import {
  Body,
  Controller,
  Headers,
  HttpCode,
  Inject,
  Post,
  Req,
  Res,
  UseGuards,
} from '@nestjs/common';
import {
  ApiBearerAuth,
  ApiBody,
  ApiCookieAuth,
  ApiHeader,
  ApiNoContentResponse,
  ApiOperation,
  ApiTags,
} from '@nestjs/swagger';
import type { Request, Response } from 'express';
import { ErrorCode } from '../common/errors/error-codes';
import type { AuthUser } from '../common/http/app-request';
import { ApiEnvelopeResponse, ApiErrorResponses } from '../common/swagger/api-envelope.decorator';
import { APP_CONFIG, AppConfig } from '../config/app-config';
import { REFRESH_COOKIE_NAME } from '../config/constants';
import { AuthService, Session } from './auth.service';
import { CLIENT_HEADER, NATIVE_CLIENTS, isNativeClient } from './client-type';
import { CurrentUser } from './decorators/current-user.decorator';
import { LoginDto, RefreshTokenBodyDto, SignupDto } from './dto/auth-request.dto';
import { SignupResponseDto, TokenResponseDto } from './dto/auth-response.dto';
import { JwtAuthGuard } from './guards/jwt-auth.guard';
import { LoginRateGuard } from './guards/login-rate.guard';
import { clearRefreshCookie, readRefreshToken, setRefreshCookie } from './refresh-cookie';

const ApiClientHeader = () =>
  ApiHeader({
    name: 'X-Client',
    required: false,
    enum: NATIVE_CLIENTS,
    description: '[앱 협의] 네이티브 앱이면 응답 data.refreshToken 포함 (TRD §9.1)',
  });

@ApiTags('auth')
@Controller('auth')
export class AuthController {
  constructor(
    private readonly auth: AuthService,
    @Inject(APP_CONFIG) private readonly config: AppConfig,
  ) {}

  @Post('signup')
  @HttpCode(201)
  @ApiOperation({ summary: '회원가입 — 동의 4종 필수 (FR-AUTH-01)' })
  @ApiEnvelopeResponse({ status: 201, type: SignupResponseDto })
  @ApiErrorResponses(ErrorCode.E4002, ErrorCode.E1004)
  signup(@Body() dto: SignupDto): Promise<SignupResponseDto> {
    return this.auth.signup(dto);
  }

  @Post('login')
  @HttpCode(200)
  @UseGuards(LoginRateGuard)
  @ApiOperation({ summary: '로그인 — 리프레시 토큰은 httpOnly 쿠키 (FR-AUTH-02)' })
  @ApiClientHeader()
  @ApiEnvelopeResponse({ status: 200, type: TokenResponseDto })
  @ApiErrorResponses(ErrorCode.E4004, ErrorCode.E4002, ErrorCode.E1003)
  async login(
    @Body() dto: LoginDto,
    @Res({ passthrough: true }) res: Response,
    @Headers(CLIENT_HEADER) client?: string,
  ): Promise<TokenResponseDto> {
    const session = await this.auth.login(dto);
    setRefreshCookie(res, session.refreshToken, this.config.cookieSecure);
    return this.present(session, client);
  }

  /** 쿠키 우선, 없으면 본문 refreshToken [앱 협의]. 실패 응답에는 항상 쿠키 삭제 헤더 */
  @Post('refresh')
  @HttpCode(200)
  @ApiOperation({ summary: '토큰 재발급 — 리프레시 토큰 교체 (FR-AUTH-02)' })
  @ApiCookieAuth(REFRESH_COOKIE_NAME)
  @ApiClientHeader()
  @ApiBody({ type: RefreshTokenBodyDto, required: false })
  @ApiEnvelopeResponse({ status: 200, type: TokenResponseDto })
  @ApiErrorResponses(ErrorCode.E1001, ErrorCode.E4002)
  async refresh(
    @Req() req: Request,
    @Body() body: RefreshTokenBodyDto,
    @Res({ passthrough: true }) res: Response,
    @Headers(CLIENT_HEADER) client?: string,
  ): Promise<TokenResponseDto> {
    try {
      const session = await this.auth.refresh(readRefreshToken(req, body.refreshToken));
      setRefreshCookie(res, session.refreshToken, this.config.cookieSecure);
      return this.present(session, client);
    } catch (error) {
      clearRefreshCookie(res, this.config.cookieSecure);
      throw error;
    }
  }

  @Post('logout')
  @HttpCode(204)
  @UseGuards(JwtAuthGuard)
  @ApiOperation({ summary: '로그아웃 — 리프레시 토큰 폐기 (FR-AUTH-03)' })
  @ApiBearerAuth()
  @ApiCookieAuth(REFRESH_COOKIE_NAME)
  @ApiBody({ type: RefreshTokenBodyDto, required: false })
  @ApiNoContentResponse()
  @ApiErrorResponses(ErrorCode.E1001)
  async logout(
    @CurrentUser() user: AuthUser,
    @Req() req: Request,
    @Body() body: RefreshTokenBodyDto,
    @Res({ passthrough: true }) res: Response,
  ): Promise<void> {
    await this.auth.logout(user.id, readRefreshToken(req, body.refreshToken));
    clearRefreshCookie(res, this.config.cookieSecure);
  }

  private present(session: Session, client: string | undefined): TokenResponseDto {
    return {
      accessToken: session.accessToken,
      expiresIn: session.expiresIn,
      ...(isNativeClient(client) ? { refreshToken: session.refreshToken } : {}),
      user: session.user,
    };
  }
}
