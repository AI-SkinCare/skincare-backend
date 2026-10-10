import {
  Column,
  CreateDateColumn,
  Entity,
  Index,
  JoinColumn,
  ManyToOne,
  PrimaryColumn,
  Unique,
} from 'typeorm';
import { numberTransformer } from '../database/transformers';
import { User } from '../users/user.entity';

@Entity({ name: 'refresh_tokens' })
@Index('idx_refresh_tokens_user', ['userId'])
@Unique('refresh_tokens_token_hash_key', ['tokenHash'])
export class RefreshToken {
  @PrimaryColumn({
    name: 'id',
    type: 'bigint',
    generated: 'increment',
    primaryKeyConstraintName: 'refresh_tokens_pkey',
    transformer: numberTransformer,
  })
  id!: number;

  @Column({ name: 'user_id', type: 'bigint', transformer: numberTransformer })
  userId!: number;

  @ManyToOne(() => User, { onDelete: 'CASCADE' })
  @JoinColumn({ name: 'user_id', foreignKeyConstraintName: 'refresh_tokens_user_id_fkey' })
  user?: User;

  /** SHA-256(토큰) hex. 원문 저장 금지 */
  @Column({ name: 'token_hash', type: 'char', length: 64 })
  tokenHash!: string;

  @Column({ name: 'expires_at', type: 'timestamptz' })
  expiresAt!: Date;

  @Column({ name: 'revoked_at', type: 'timestamptz', nullable: true })
  revokedAt!: Date | null;

  /** 교체한 새 토큰 id (재사용 감지용) */
  @Column({ name: 'replaced_by', type: 'bigint', nullable: true, transformer: numberTransformer })
  replacedBy!: number | null;

  @CreateDateColumn({ name: 'created_at', type: 'timestamptz' })
  createdAt!: Date;
}
