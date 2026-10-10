import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';
import { Transform, Type } from 'class-transformer';
import {
  Equals,
  IsDefined,
  IsEmail,
  IsNotEmpty,
  IsObject,
  IsOptional,
  IsString,
  Length,
  Matches,
  MaxLength,
  ValidateNested,
} from 'class-validator';

const trim = ({ value }: { value: unknown }) => (typeof value === 'string' ? value.trim() : value);
const normalizeEmail = ({ value }: { value: unknown }) =>
  typeof value === 'string' ? value.trim().toLowerCase() : value;

/**
 * 8–64자, 영문·숫자·특수문자 중 2종 이상.
 * [구현 결정] 출력 가능한 ASCII(\x21-\x7E)만 → bcrypt 72바이트 한계를 넘지 않는다.
 */
export const PASSWORD_PATTERN =
  /^(?:(?=.*[A-Za-z])(?=.*\d)|(?=.*[A-Za-z])(?=.*[^A-Za-z\d])|(?=.*\d)(?=.*[^A-Za-z\d]))[\x21-\x7E]{8,64}$/;

export class AgreementsDto {
  @ApiProperty({ type: 'boolean', example: true, description: '이용약관 동의 — 반드시 true' })
  @Equals(true)
  terms!: true;

  @ApiProperty({
    type: 'boolean',
    example: true,
    description: '개인정보 수집·이용 동의 — 반드시 true',
  })
  @Equals(true)
  privacy!: true;

  @ApiProperty({
    type: 'boolean',
    example: true,
    description: '얼굴 이미지 처리 동의 — 반드시 true',
  })
  @Equals(true)
  imageProcessing!: true;

  @ApiProperty({ type: 'boolean', example: true, description: '만 14세 이상 확인 — 반드시 true' })
  @Equals(true)
  over14!: true;
}

export class SignupDto {
  @ApiProperty({ example: 'user@example.com', maxLength: 255 })
  @Transform(normalizeEmail)
  @IsString()
  @IsNotEmpty()
  @MaxLength(255)
  @IsEmail()
  email!: string;

  @ApiProperty({ example: 'Skin!2026', minLength: 8, maxLength: 64 })
  @IsString()
  @IsNotEmpty()
  @Matches(PASSWORD_PATTERN)
  password!: string;

  @ApiProperty({ example: '우주', minLength: 2, maxLength: 20 })
  @Transform(trim)
  @IsString()
  @IsNotEmpty()
  @Length(2, 20)
  nickname!: string;

  @ApiProperty({ type: AgreementsDto, description: '4개 모두 true' })
  @IsDefined()
  @IsObject()
  @ValidateNested()
  @Type(() => AgreementsDto)
  agreements!: AgreementsDto;
}

export class LoginDto {
  @ApiProperty({ example: 'user@example.com' })
  @Transform(normalizeEmail)
  @IsString()
  @IsNotEmpty()
  @MaxLength(255)
  email!: string;

  @ApiProperty({ example: 'Skin!2026' })
  @IsString()
  @IsNotEmpty()
  @MaxLength(255)
  password!: string;
}

/** [앱 협의, TRD §9.1] 쿠키가 없을 때만 쓰는 본문 토큰 */
export class RefreshTokenBodyDto {
  @ApiPropertyOptional({ description: '쿠키 refresh_token이 없을 때만 사용 (네이티브 앱)' })
  @IsOptional()
  @IsString()
  @MaxLength(2048)
  refreshToken?: string;
}
