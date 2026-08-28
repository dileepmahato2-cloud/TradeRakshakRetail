-- =============================================================================
-- TradeRakshakâ„¢ v11.6 â€” V16: Broker Authentication & Real Trade Support
--
-- Tables:
--   1. broker_credentials  â€” encrypted API key/secret per user per broker
--   2. broker_sessions     â€” active login sessions and access tokens
--   3. real_trades         â€” real money trade log (audit trail, read-only reference)
--
-- Supported Brokers (Indian):
--   Zerodha (Kite), Upstox, Angel One (SmartAPI), Fyers,
--   Dhan, Shoonya (Finvasia), Kotak Securities, ICICI Direct (Breeze)
--
-- Security Policy:
--   â€¢ api_secret and access_token stored encrypted (AES-256 via Fernet key)
--   â€¢ No plaintext secrets stored ever
--   â€¢ read_only flag â€” when true, only data fetch allowed, NO order placement
--   â€¢ Every real order attempt is audit-logged before execution
--   â€¢ Super Admin can disable real trading per institution via feature_config
-- =============================================================================

-- â”€â”€ 1. Broker Credentials â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS broker_credentials (
    id              BIGSERIAL PRIMARY KEY,
    user_id         INTEGER NOT NULL,
    institution_id  INTEGER DEFAULT 1,
    broker_name     TEXT NOT NULL,         -- zerodha / upstox / angel / fyers / dhan / shoonya / kotak / icici
    broker_label    TEXT NOT NULL,         -- display: "Zerodha (Kite Connect)"
    client_id       TEXT,                  -- broker client/user ID
    api_key         TEXT,                  -- broker API key (plaintext OK â€” not secret)
    api_secret_enc  TEXT,                  -- AES-encrypted API secret
    access_token_enc TEXT,                 -- AES-encrypted access token (session)
    totp_secret_enc  TEXT,                 -- AES-encrypted TOTP secret (optional)
    redirect_uri    TEXT,                  -- OAuth redirect URI if applicable
    is_active       INTEGER DEFAULT 1,
    is_verified     INTEGER DEFAULT 0,     -- 1 = connection tested and verified
    read_only       INTEGER DEFAULT 1,     -- 1 = data only; 0 = order placement allowed
    last_verified   TEXT,
    verification_msg TEXT,
    token_expiry    TEXT,                  -- when access token expires
    permissions_json TEXT,                 -- JSON: ["orders","holdings","positions","profile"]
    created_at      TEXT DEFAULT (CURRENT_TIMESTAMP::text),
    updated_at      TEXT,
    UNIQUE(user_id, broker_name)           -- one credential set per broker per user
);
CREATE INDEX IF NOT EXISTS idx_bc_user   ON broker_credentials(user_id, is_active);
CREATE INDEX IF NOT EXISTS idx_bc_broker ON broker_credentials(broker_name, is_active);

-- â”€â”€ 2. Broker Sessions â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS broker_sessions (
    id              BIGSERIAL PRIMARY KEY,
    user_id         INTEGER NOT NULL,
    broker_name     TEXT NOT NULL,
    session_token   TEXT,                  -- current session token (encrypted)
    login_at        TEXT,
    expires_at      TEXT,
    is_active       INTEGER DEFAULT 1,
    device_info     TEXT,
    created_at      TEXT DEFAULT (CURRENT_TIMESTAMP::text)
);

-- â”€â”€ 3. Real Trade Log â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
-- Audit trail for real money trades. READ ONLY â€” never modified after insert.
CREATE TABLE IF NOT EXISTS real_trades (
    id              BIGSERIAL PRIMARY KEY,
    trade_ref       TEXT UNIQUE NOT NULL,  -- internal reference e.g. RT-U0001-RELIANCE-20260612-001
    user_id         INTEGER NOT NULL,
    institution_id  INTEGER DEFAULT 1,
    broker_name     TEXT NOT NULL,
    broker_order_id TEXT,                  -- order ID returned by broker API
    symbol          TEXT NOT NULL,
    exchange        TEXT DEFAULT 'NSE',
    trade_type      TEXT NOT NULL,         -- BUY / SELL
    order_type      TEXT DEFAULT 'MARKET', -- MARKET / LIMIT / SL / SL-M
    product_type    TEXT DEFAULT 'CNC',    -- CNC / MIS / NRML
    qty             INTEGER NOT NULL,
    price           REAL,                  -- limit price (0 for MARKET)
    trigger_price   REAL,                  -- for SL orders
    filled_qty      INTEGER DEFAULT 0,
    avg_fill_price  REAL DEFAULT 0,
    order_status    TEXT DEFAULT 'PENDING', -- PENDING/OPEN/COMPLETE/REJECTED/CANCELLED
    rejection_reason TEXT,
    -- Risk confirmation fields
    risk_confirmed   INTEGER DEFAULT 0,    -- 1 = user explicitly confirmed real money risk
    sebi_consent     INTEGER DEFAULT 0,    -- 1 = user acknowledged SEBI disclaimer
    broker_verified  INTEGER DEFAULT 0,    -- 1 = broker connection verified before order
    -- Timestamps
    ordered_at      TEXT,
    filled_at       TEXT,
    created_at      TEXT DEFAULT (CURRENT_TIMESTAMP::text)
);
CREATE INDEX IF NOT EXISTS idx_rt_user   ON real_trades(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_rt_symbol ON real_trades(symbol, created_at DESC);

-- â”€â”€ 4. Add trade_mode column to paper_trades table â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
-- trade_mode: 'virtual' (default) or 'real'
-- broker_credential_id: links to broker_credentials for real trades
ALTER TABLE paper_trades ADD COLUMN trade_mode TEXT DEFAULT 'virtual';
ALTER TABLE paper_trades ADD COLUMN broker_credential_id INTEGER;
ALTER TABLE paper_trades ADD COLUMN real_trade_ref TEXT;  -- links to real_trades

-- â”€â”€ 5. Feature flags â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
INSERT INTO feature_configs
    (feature_key, feature_name, feature_group, is_enabled, min_plan, allowed_roles, description, created_at)
VALUES
    ('broker_connect',
     'Broker Connection',
     'trading', 1, 'Free Trial', 'ALL',
     'Connect Zerodha, Upstox, Angel, Fyers, Dhan, Shoonya, Kotak, ICICI for data feed and optional real trading',
     CURRENT_TIMESTAMP::text),
    ('real_trading',
     'Real Money Trading',
     'trading', 1, 'Pro', 'ALL',
     'Place real orders through connected broker. Requires verified broker connection and explicit risk confirmation.',
     CURRENT_TIMESTAMP::text) ON CONFLICT DO NOTHING;

-- =============================================================================
-- END V16
-- =============================================================================

