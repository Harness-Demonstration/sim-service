CREATE TABLE sim (
    iccid VARCHAR(32) PRIMARY KEY,
    msisdn VARCHAR(32) NOT NULL,
    activated_at TIMESTAMPTZ
);
