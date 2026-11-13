-- A grayscale model-attribution map only. No face image or face overlay is stored.
ALTER TABLE analyses ADD COLUMN disease_explanation jsonb;
