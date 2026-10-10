import { ApiProperty } from '@nestjs/swagger';

export class SystemStatusDto {
  @ApiProperty({ example: true })
  aiAvailable!: boolean;

  @ApiProperty({ example: 3, description: 'queued 건수' })
  queueLength!: number;

  @ApiProperty({ example: 165, description: 'round(queueLength × 최근 평균 추천ms / 1000)' })
  estimatedWaitSec!: number;

  @ApiProperty({
    type: String,
    nullable: true,
    example: '2026-10-15T09:12:00Z',
    description: '마지막 헬스체크 시각. 첫 검사 전에는 null',
  })
  checkedAt!: string | null;
}
