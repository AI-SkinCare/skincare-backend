import { Module } from '@nestjs/common';
import { JwtModule } from '@nestjs/jwt';
import { PassportModule } from '@nestjs/passport';
import { TypeOrmModule } from '@nestjs/typeorm';
import { User } from '../users/user.entity';
import { AuthController } from './auth.controller';
import { AuthService } from './auth.service';
import { LoginRateGuard } from './guards/login-rate.guard';
import { JwtStrategy } from './jwt.strategy';
import { LoginRateLimiter } from './login-rate-limiter';
import { RefreshToken } from './refresh-token.entity';
import { RefreshTokenService } from './refresh-token.service';

@Module({
  imports: [
    PassportModule,
    // 비밀키는 호출마다 넘긴다 (액세스·리프레시가 서로 다른 키)
    JwtModule.register({}),
    TypeOrmModule.forFeature([User, RefreshToken]),
  ],
  controllers: [AuthController],
  providers: [AuthService, RefreshTokenService, JwtStrategy, LoginRateLimiter, LoginRateGuard],
  exports: [JwtModule],
})
export class AuthModule {}
