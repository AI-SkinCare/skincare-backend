import { Column, CreateDateColumn, Entity, JoinColumn, OneToOne, PrimaryColumn } from 'typeorm';
import type { ConcernCode, RoutineTime, SkinTypeCode } from '../../common/enums';
import { Analysis } from './analysis.entity';

export interface IngredientItem {
  name: string;
  purpose: string | null;
  priority: number;
}

export interface RoutineItem {
  step: number;
  time: RoutineTime;
  action: string;
}

@Entity({ name: 'recommendations' })
export class Recommendation {
  @PrimaryColumn({
    name: 'analysis_id',
    type: 'char',
    length: 26,
    primaryKeyConstraintName: 'recommendations_pkey',
  })
  analysisId!: string;

  @OneToOne(() => Analysis, { onDelete: 'CASCADE' })
  @JoinColumn({ name: 'analysis_id', foreignKeyConstraintName: 'recommendations_analysis_id_fkey' })
  analysis?: Analysis;

  /** 도출된 피부 타입 코드 */
  @Column({ name: 'skin_type', type: 'varchar', length: 16 })
  skinType!: SkinTypeCode;

  /** 고민 코드 배열 */
  @Column({ name: 'concerns', type: 'jsonb' })
  concerns!: ConcernCode[];

  @Column({ name: 'ingredients', type: 'jsonb' })
  ingredients!: IngredientItem[];

  @Column({ name: 'routine', type: 'jsonb' })
  routine!: RoutineItem[];

  @Column({ name: 'reasoning', type: 'jsonb' })
  reasoning!: string[];

  @Column({ name: 'answer', type: 'text' })
  answer!: string;

  @Column({ name: 'disclaimer', type: 'text' })
  disclaimer!: string;

  @Column({ name: 'input_notes', type: 'jsonb', default: () => `'[]'` })
  inputNotes!: string[];

  @Column({ name: 'filtered', type: 'boolean' })
  filtered!: boolean;

  @Column({ name: 'used_fallback', type: 'boolean' })
  usedFallback!: boolean;

  @Column({ name: 'model_version', type: 'varchar', length: 40 })
  modelVersion!: string;

  /** AI 서버 내부 생성 시간 */
  @Column({ name: 'inference_ms', type: 'integer' })
  inferenceMs!: number;

  @CreateDateColumn({ name: 'created_at', type: 'timestamptz' })
  createdAt!: Date;
}
