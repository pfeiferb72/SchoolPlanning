ALTER TABLE school ADD COLUMN retention_days integer CHECK(retention_days BETWEEN 1 AND 36500);
ALTER TABLE membership ADD COLUMN purpose_until date;
ALTER TABLE mail_queue ADD COLUMN created timestamptz NOT NULL DEFAULT now();
