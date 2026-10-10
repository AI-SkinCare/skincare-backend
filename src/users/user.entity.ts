import { Column, CreateDateColumn, Entity, PrimaryColumn, Unique, UpdateDateColumn } from 'typeorm';
import { numberTransformer } from '../database/transformers';

@Entity({ name: 'users' })
@Unique('users_email_key', ['email'])
export class User {
  @PrimaryColumn({
    name: 'id',
    type: 'bigint',
    generated: 'increment',
    primaryKeyConstraintName: 'users_pkey',
    transformer: numberTransformer,
  })
  id!: number;

  @Column({ name: 'email', type: 'varchar', length: 255 })
  email!: string;

  @Column({ name: 'password_hash', type: 'varchar', length: 100 })
  passwordHash!: string;

  @Column({ name: 'nickname', type: 'varchar', length: 20 })
  nickname!: string;

  @Column({ name: 'terms_agreed_at', type: 'timestamptz' })
  termsAgreedAt!: Date;

  @Column({ name: 'privacy_agreed_at', type: 'timestamptz' })
  privacyAgreedAt!: Date;

  @Column({ name: 'image_consent_at', type: 'timestamptz' })
  imageConsentAt!: Date;

  @Column({ name: 'over14_confirmed_at', type: 'timestamptz' })
  over14ConfirmedAt!: Date;

  @CreateDateColumn({ name: 'created_at', type: 'timestamptz' })
  createdAt!: Date;

  @UpdateDateColumn({ name: 'updated_at', type: 'timestamptz' })
  updatedAt!: Date;
}
