import {
  Check,
  Column,
  CreateDateColumn,
  Entity,
  Index,
  JoinColumn,
  ManyToOne,
  PrimaryColumn,
} from 'typeorm';
import type { AnalysisStatus, DeviceType } from '../../common/enums';
import { numberTransformer } from '../../database/transformers';
import { User } from '../../users/user.entity';

@Entity({ name: 'analyses' })
@Index('idx_analyses_user_created', ['userId', 'createdAt'])
@Index('idx_analyses_queue', ['queuedAt'], { where: `status = 'queued'` })
@Check('analyses_status_check', `status IN ('queued','recommending','done','failed')`)
@Check('analyses_device_type_check', `device_type IN ('smartphone','tablet','camera')`)
@Check('analyses_confidence_check', `confidence BETWEEN 0 AND 1`)
@Check('analyses_overall_score_check', `overall_score BETWEEN 0 AND 100`)
export class Analysis {
  /** ULID, URL에 노출 */
  @PrimaryColumn({
    name: 'id',
    type: 'char',
    length: 26,
    primaryKeyConstraintName: 'analyses_pkey',
  })
  id!: string;

  @Column({ name: 'user_id', type: 'bigint', transformer: numberTransformer })
  userId!: number;

  @ManyToOne(() => User, { onDelete: 'CASCADE' })
  @JoinColumn({ name: 'user_id', foreignKeyConstraintName: 'analyses_user_id_fkey' })
  user?: User;

  @Column({ name: 'status', type: 'varchar', length: 16, default: 'queued' })
  status!: AnalysisStatus;

  @Column({ name: 'device_type', type: 'varchar', length: 16 })
  deviceType!: DeviceType;

  /** AI is_stub 그대로 */
  @Column({ name: 'is_demo', type: 'boolean' })
  isDemo!: boolean;

  @Column({
    name: 'confidence',
    type: 'numeric',
    precision: 4,
    scale: 3,
    transformer: numberTransformer,
  })
  confidence!: number;

  @Column({ name: 'calibration_applied', type: 'boolean', default: false })
  calibrationApplied!: boolean;

  @Column({ name: 'overall_score', type: 'smallint' })
  overallScore!: number;

  @Column({ name: 'skin_model_version', type: 'varchar', length: 40 })
  skinModelVersion!: string;

  /** 측정 API 왕복 */
  @Column({ name: 'measure_ms', type: 'integer' })
  measureMs!: number;

  /** 재요청 시 갱신 */
  @Column({ name: 'queued_at', type: 'timestamptz', default: () => 'now()' })
  queuedAt!: Date;

  /** 워커 획득 시각 */
  @Column({ name: 'rec_started_at', type: 'timestamptz', nullable: true })
  recStartedAt!: Date | null;

  /** done·failed 확정 시각 */
  @Column({ name: 'completed_at', type: 'timestamptz', nullable: true })
  completedAt!: Date | null;

  @Column({ name: 'queue_wait_ms', type: 'integer', nullable: true })
  queueWaitMs!: number | null;

  /** 추천 API 왕복 */
  @Column({ name: 'recommend_ms', type: 'integer', nullable: true })
  recommendMs!: number | null;

  /** 추천 호출 횟수(재시도 포함) */
  @Column({ name: 'attempt_count', type: 'smallint', default: 0 })
  attemptCount!: number;

  @Column({ name: 'error_code', type: 'varchar', length: 10, nullable: true })
  errorCode!: string | null;

  @CreateDateColumn({ name: 'created_at', type: 'timestamptz' })
  createdAt!: Date;
}
