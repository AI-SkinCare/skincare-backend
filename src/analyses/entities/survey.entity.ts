import { Column, Entity, JoinColumn, OneToOne, PrimaryColumn } from 'typeorm';
import type { AgeBand, Gender, SkinTypeCode } from '../../common/enums';
import type { NormalizedSurvey } from '../survey';
import { Analysis } from './analysis.entity';

@Entity({ name: 'surveys' })
export class Survey {
  @PrimaryColumn({
    name: 'analysis_id',
    type: 'char',
    length: 26,
    primaryKeyConstraintName: 'surveys_pkey',
  })
  analysisId!: string;

  @OneToOne(() => Analysis, { onDelete: 'CASCADE' })
  @JoinColumn({ name: 'analysis_id', foreignKeyConstraintName: 'surveys_analysis_id_fkey' })
  analysis?: Analysis;

  @Column({ name: 'gender', type: 'varchar', length: 8 })
  gender!: Gender;

  @Column({ name: 'age_band', type: 'varchar', length: 4 })
  ageBand!: AgeBand;

  /** 사용자 자가응답 코드 */
  @Column({ name: 'skin_type', type: 'varchar', length: 16, nullable: true })
  skinType!: SkinTypeCode | null;

  /** 제출한 설문 전체(클라이언트 코드, 정규화) */
  @Column({ name: 'payload', type: 'jsonb' })
  payload!: NormalizedSurvey;
}
