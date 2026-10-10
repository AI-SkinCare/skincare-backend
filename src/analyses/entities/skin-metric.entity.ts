import { Check, Column, Entity, JoinColumn, ManyToOne, PrimaryColumn } from 'typeorm';
import type { Roi } from '../../common/enums';
import { numberTransformer } from '../../database/transformers';
import { Analysis } from './analysis.entity';

const ratioColumn = (name: string) =>
  Column({ name, type: 'numeric', precision: 5, scale: 4, transformer: numberTransformer });

/** 0–1 원본을 4자리로 저장한다. 0–100 변환은 응답 시점에 (TRD §8.1). */
@Entity({ name: 'skin_metrics' })
@Check('skin_metrics_moisture_check', `moisture BETWEEN 0 AND 1`)
@Check('skin_metrics_elasticity_check', `elasticity BETWEEN 0 AND 1`)
@Check('skin_metrics_pigmentation_check', `pigmentation BETWEEN 0 AND 1`)
@Check('skin_metrics_pore_check', `pore BETWEEN 0 AND 1`)
export class SkinMetric {
  @PrimaryColumn({
    name: 'analysis_id',
    type: 'char',
    length: 26,
    primaryKeyConstraintName: 'skin_metrics_pkey',
  })
  analysisId!: string;

  @PrimaryColumn({
    name: 'roi',
    type: 'varchar',
    length: 16,
    primaryKeyConstraintName: 'skin_metrics_pkey',
  })
  roi!: Roi;

  @ManyToOne(() => Analysis, { onDelete: 'CASCADE' })
  @JoinColumn({ name: 'analysis_id', foreignKeyConstraintName: 'skin_metrics_analysis_id_fkey' })
  analysis?: Analysis;

  @ratioColumn('moisture')
  moisture!: number;

  @ratioColumn('elasticity')
  elasticity!: number;

  /** 높을수록 나쁨 */
  @ratioColumn('pigmentation')
  pigmentation!: number;

  /** 높을수록 나쁨 */
  @ratioColumn('pore')
  pore!: number;
}
