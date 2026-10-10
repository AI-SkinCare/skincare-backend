import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';

export class SignupResponseDto {
  @ApiProperty({ example: 42 })
  userId!: number;
}

export class SessionUserDto {
  @ApiProperty({ example: 42 })
  id!: number;

  @ApiProperty({ example: 'user@example.com' })
  email!: string;

  @ApiProperty({ example: '우주' })
  nickname!: string;
}

export class TokenResponseDto {
  @ApiProperty({ example: 'eyJhbGciOi…' })
  accessToken!: string;

  @ApiProperty({ example: 3600 })
  expiresIn!: number;

  @ApiPropertyOptional({
    example: 'eyJhbGciOi…',
    description: '[앱 협의] `X-Client: ios` 요청일 때만. 쿠키 refresh_token과 같은 값',
  })
  refreshToken?: string;

  @ApiProperty({ type: SessionUserDto })
  user!: SessionUserDto;
}
