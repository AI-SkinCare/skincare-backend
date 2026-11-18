-- Additive migration: retain existing analyses and recommendations.
ALTER TABLE analyses DROP CONSTRAINT analyses_recommendation_status_check;
ALTER TABLE analyses ADD CONSTRAINT analyses_recommendation_status_check
  CHECK (recommendation_status IN ('generated','uncertain','no_concern','quality_rejected'));
ALTER TABLE analyses ADD COLUMN generation JSONB NOT NULL DEFAULT '{}';
ALTER TABLE recommendations ALTER COLUMN model_version TYPE VARCHAR(160);
