import { ApiProperty } from '@nestjs/swagger';

export class UserMeDto {
  @ApiProperty({ example: 42 })
  id!: number;

  @ApiProperty({ example: 'user@example.com' })
  email!: string;

  @ApiProperty({ example: '우주' })
  nickname!: string;

  @ApiProperty({ example: '2026-10-03T02:11:00Z' })
  createdAt!: string;

  @ApiProperty({ example: 3, description: '분석 수 (상태 무관)' })
  analysisCount!: number;
}
