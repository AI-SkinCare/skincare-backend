import { MigrationInterface, QueryRunner } from 'typeorm';

/** TRD §8.1 DDL 그대로 (SC-04). 스키마 변경은 새 마이그레이션 + SC-04 수정으로만. */
export class CreateInitialSchema1791590400000 implements MigrationInterface {
  name = 'CreateInitialSchema1791590400000';

  public async up(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`
      CREATE TABLE users (
        id                  BIGSERIAL PRIMARY KEY,
        email               VARCHAR(255) NOT NULL UNIQUE,
        password_hash       VARCHAR(100) NOT NULL,
        nickname            VARCHAR(20)  NOT NULL,
        terms_agreed_at     TIMESTAMPTZ  NOT NULL,
        privacy_agreed_at   TIMESTAMPTZ  NOT NULL,
        image_consent_at    TIMESTAMPTZ  NOT NULL,
        over14_confirmed_at TIMESTAMPTZ  NOT NULL,
        created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
        updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
      )
    `);

    await queryRunner.query(`
      CREATE TABLE refresh_tokens (
        id          BIGSERIAL PRIMARY KEY,
        user_id     BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        token_hash  CHAR(64)    NOT NULL UNIQUE,
        expires_at  TIMESTAMPTZ NOT NULL,
        revoked_at  TIMESTAMPTZ,
        replaced_by BIGINT,
        created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
      )
    `);
    await queryRunner.query(`CREATE INDEX idx_refresh_tokens_user ON refresh_tokens(user_id)`);

    await queryRunner.query(`
      CREATE TABLE analyses (
        id                  CHAR(26)     PRIMARY KEY,
        user_id             BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        status              VARCHAR(16)  NOT NULL DEFAULT 'queued'
                            CHECK (status IN ('queued','recommending','done','failed')),
        device_type         VARCHAR(16)  NOT NULL
                            CHECK (device_type IN ('smartphone','tablet','camera')),
        is_demo             BOOLEAN      NOT NULL,
        confidence          NUMERIC(4,3) NOT NULL CHECK (confidence BETWEEN 0 AND 1),
        calibration_applied BOOLEAN      NOT NULL DEFAULT false,
        overall_score       SMALLINT     NOT NULL CHECK (overall_score BETWEEN 0 AND 100),
        skin_model_version  VARCHAR(40)  NOT NULL,
        measure_ms          INTEGER      NOT NULL,
        queued_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
        rec_started_at      TIMESTAMPTZ,
        completed_at        TIMESTAMPTZ,
        queue_wait_ms       INTEGER,
        recommend_ms        INTEGER,
        attempt_count       SMALLINT     NOT NULL DEFAULT 0,
        error_code          VARCHAR(10),
        created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
      )
    `);
    await queryRunner.query(
      `CREATE INDEX idx_analyses_user_created ON analyses(user_id, created_at DESC)`,
    );
    await queryRunner.query(
      `CREATE INDEX idx_analyses_queue ON analyses(queued_at) WHERE status = 'queued'`,
    );

    await queryRunner.query(`
      CREATE TABLE skin_metrics (
        analysis_id  CHAR(26)     NOT NULL REFERENCES analyses(id) ON DELETE CASCADE,
        roi          VARCHAR(16)  NOT NULL,
        moisture     NUMERIC(5,4) NOT NULL CHECK (moisture BETWEEN 0 AND 1),
        elasticity   NUMERIC(5,4) NOT NULL CHECK (elasticity BETWEEN 0 AND 1),
        pigmentation NUMERIC(5,4) NOT NULL CHECK (pigmentation BETWEEN 0 AND 1),
        pore         NUMERIC(5,4) NOT NULL CHECK (pore BETWEEN 0 AND 1),
        PRIMARY KEY (analysis_id, roi)
      )
    `);

    await queryRunner.query(`
      CREATE TABLE surveys (
        analysis_id CHAR(26)    PRIMARY KEY REFERENCES analyses(id) ON DELETE CASCADE,
        gender      VARCHAR(8)  NOT NULL,
        age_band    VARCHAR(4)  NOT NULL,
        skin_type   VARCHAR(16),
        payload     JSONB       NOT NULL
      )
    `);

    await queryRunner.query(`
      CREATE TABLE recommendations (
        analysis_id   CHAR(26)    PRIMARY KEY REFERENCES analyses(id) ON DELETE CASCADE,
        skin_type     VARCHAR(16) NOT NULL,
        concerns      JSONB       NOT NULL,
        ingredients   JSONB       NOT NULL,
        routine       JSONB       NOT NULL,
        reasoning     JSONB       NOT NULL,
        answer        TEXT        NOT NULL,
        disclaimer    TEXT        NOT NULL,
        input_notes   JSONB       NOT NULL DEFAULT '[]',
        filtered      BOOLEAN     NOT NULL,
        used_fallback BOOLEAN     NOT NULL,
        model_version VARCHAR(40) NOT NULL,
        inference_ms  INTEGER     NOT NULL,
        created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
      )
    `);
  }

  public async down(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`DROP TABLE recommendations`);
    await queryRunner.query(`DROP TABLE surveys`);
    await queryRunner.query(`DROP TABLE skin_metrics`);
    await queryRunner.query(`DROP TABLE analyses`);
    await queryRunner.query(`DROP TABLE refresh_tokens`);
    await queryRunner.query(`DROP TABLE users`);
  }
}
