-- The Demo (paper) account: a virtual balance with its own ledger.
--
-- Paper trading had no money behind it. The nearest thing was the config key
-- volbreakout.available.funds, whose own description says "0 falls back to the investment amount
-- - it does not mean 'no money'": a cap on deployment, not a balance. Nothing was debited when a
-- paper position opened, nothing credited when it closed, and nothing survived a restart. So a
-- paper strategy could "spend" the same rupee every session and never run out, which makes its
-- results incomparable with a real account's.
--
-- Two tables rather than one. The balance is what every screen asks for and must be cheap to
-- read; the ledger is why the balance is what it is, and has to be append-only or the balance
-- cannot be audited. Keeping them apart means a correction is a new row rather than an edit.

CREATE TABLE IF NOT EXISTS demo_account (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    institution_id  BIGINT NOT NULL DEFAULT 1,
    -- The running virtual balance, in rupees. Seeded once at the opening balance below and
    -- thereafter moved only by ledger entries.
    balance         DOUBLE PRECISION NOT NULL,
    opening_balance DOUBLE PRECISION NOT NULL,
    currency        VARCHAR(8) NOT NULL DEFAULT 'INR',
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uk_demo_account UNIQUE (user_id, institution_id)
);

-- Every movement, append-only. The balance above is derivable from these rows, and a
-- disagreement between the two is a bug worth being able to see.
CREATE TABLE IF NOT EXISTS demo_account_ledger (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    institution_id  BIGINT NOT NULL DEFAULT 1,
    entry_at        TIMESTAMP NOT NULL DEFAULT now(),
    -- BUY, SELL, CHARGES, ADJUSTMENT, RESET
    entry_type      VARCHAR(16) NOT NULL,
    symbol          VARCHAR(64),
    quantity        INTEGER,
    price           DOUBLE PRECISION,
    -- Negative debits the account, positive credits it. Signed rather than a separate
    -- direction column, so the balance is the sum of this column and nothing else.
    amount          DOUBLE PRECISION NOT NULL,
    balance_after   DOUBLE PRECISION NOT NULL,
    reference       VARCHAR(128),
    note            VARCHAR(255),
    CONSTRAINT fk_demo_ledger_account FOREIGN KEY (user_id, institution_id)
        REFERENCES demo_account (user_id, institution_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_demo_ledger_account_time
    ON demo_account_ledger (user_id, institution_id, entry_at DESC);

-- The opening balance, as a setting rather than a literal in code, so it can be changed for a
-- new account without a deployment. Existing accounts keep whatever balance they have reached:
-- this is the amount a demo account STARTS with, not one it is reset to.
INSERT INTO application_settings
    (category, setting_key, setting_name, setting_value, data_type,
     default_value, min_value, max_value, description,
     is_editable, is_active, display_order, config_version, created_at)
SELECT 'TRADING', 'demo.account.opening_balance', 'Demo Account Opening Balance (Rs)',
       '1000000', 'DOUBLE', '1000000', '0', '1000000000',
       'Virtual balance a new demo account opens with. Changing it does not alter accounts that already exist.',
       1, 1, 90, 1, to_char(now(), 'YYYY-MM-DD"T"HH24:MI:SS')
WHERE NOT EXISTS (
    SELECT 1 FROM application_settings WHERE setting_key = 'demo.account.opening_balance');
