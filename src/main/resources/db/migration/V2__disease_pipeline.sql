-- TRD §8.1·8.3 — 개정 수행계획서(2026-10-15): 회귀 지표 측정 → DenseNet121 6종 이미지 분류 + Qwen2.5 추천.
-- 기존 분석은 회귀 stub(데모) 값이라 새 응답 형식으로 보여줄 수 없어 지운다 (2026-10-16 이현승 결정).
-- 사용자·리프레시 토큰은 그대로 둔다.

DELETE FROM analyses;  -- CASCADE: skin_metrics · surveys · recommendations

DROP TABLE skin_metrics;

DROP INDEX idx_analyses_queue;
ALTER TABLE analyses DROP CONSTRAINT analyses_status_check;

ALTER TABLE analyses
  DROP COLUMN device_type,
  DROP COLUMN is_demo,
  DROP COLUMN confidence,
  DROP COLUMN calibration_applied,
  DROP COLUMN overall_score,
  DROP COLUMN skin_model_version;

ALTER TABLE analyses RENAME COLUMN measure_ms TO classify_ms;

-- 작업 상태는 대문자 5종 (개정 B.1)
ALTER TABLE analyses ALTER COLUMN status SET DEFAULT 'QUEUED';
ALTER TABLE analyses ADD CONSTRAINT analyses_status_check
  CHECK (status IN ('QUEUED','GENERATING','COMPLETED','WITHHELD','FAILED'));

-- POST /infer/disease 분류 결과. 추천 호출·재시도 때 그대로 다시 보내므로 반올림하지 않는다
ALTER TABLE analyses
  ADD COLUMN disease_label         VARCHAR(16) NOT NULL
             CHECK (disease_label IN ('psoriasis','atopic','acne','normal','rosacea','seborrheic')),
  ADD COLUMN disease_confidence    DOUBLE PRECISION NOT NULL
             CHECK (disease_confidence BETWEEN 0 AND 1),
  ADD COLUMN disease_uncertain     BOOLEAN NOT NULL,
  ADD COLUMN disease_probabilities JSONB NOT NULL,
  ADD COLUMN disease_model_version VARCHAR(80) NOT NULL,
  ADD COLUMN disease_inference_ms  INTEGER NOT NULL,
  -- AI recommendation_status. COMPLETED·WITHHELD일 때만
  ADD COLUMN recommendation_status VARCHAR(16)
             CHECK (recommendation_status IN ('generated','uncertain','no_concern')),
  ADD COLUMN guidance              JSONB NOT NULL DEFAULT '[]';

CREATE INDEX idx_analyses_queue ON analyses(queued_at) WHERE status = 'QUEUED';
