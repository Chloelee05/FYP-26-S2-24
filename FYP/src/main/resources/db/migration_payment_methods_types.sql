-- Support multiple payment method types: credit card, PayPal, bank transfer. Safe to re-run.

-- New columns: the method type and a generic account reference
--   (PayPal email, or bank name for bank transfers).
ALTER TABLE payment_methods
    ADD COLUMN IF NOT EXISTS method_type VARCHAR(20) NOT NULL DEFAULT 'CARD';

ALTER TABLE payment_methods
    ADD COLUMN IF NOT EXISTS account_ref VARCHAR(255);

-- Card-specific columns do not apply to PayPal / bank transfer, so relax them.
ALTER TABLE payment_methods ALTER COLUMN card_holder     DROP NOT NULL;
ALTER TABLE payment_methods ALTER COLUMN card_last4      DROP NOT NULL;
ALTER TABLE payment_methods ALTER COLUMN exp_month       DROP NOT NULL;
ALTER TABLE payment_methods ALTER COLUMN exp_year        DROP NOT NULL;
ALTER TABLE payment_methods ALTER COLUMN card_number_enc DROP NOT NULL;

-- Constrain method_type to the supported values (guarded so the script is re-runnable).
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'payment_methods_type_check') THEN
        ALTER TABLE payment_methods
            ADD CONSTRAINT payment_methods_type_check
            CHECK (method_type IN ('CARD','PAYPAL','BANK_TRANSFER'));
    END IF;
END$$;
