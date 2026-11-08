-- Same Day (EXPRESS) delivery: the tier and its surcharge are part of the immutable quote.
ALTER TABLE pricing_quotes ADD COLUMN service_tier VARCHAR(16) NOT NULL DEFAULT 'STANDARD';
ALTER TABLE pricing_quotes ADD COLUMN express_surcharge_minor_units BIGINT NOT NULL DEFAULT 0;
ALTER TABLE pricing_quotes ADD CONSTRAINT chk_pricing_quotes_service_tier CHECK (service_tier IN ('STANDARD', 'EXPRESS'));
ALTER TABLE pricing_quotes ADD CONSTRAINT chk_pricing_quotes_express_surcharge CHECK (express_surcharge_minor_units >= 0);
