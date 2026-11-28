-- The last balance the broker reported, kept so an unreachable broker can still be answered.
--
-- The requirement is explicit: when the broker API is unavailable, show the last known balance
-- WITH its timestamp and say plainly that it could not be refreshed. That is only possible if
-- each successful read is kept, so this stores one row per account and overwrites it on every
-- successful refresh.
--
-- One row per credential rather than a history: this exists to answer "what did the broker last
-- tell us, and when", not to chart a balance over time. A history would also hold a user's cash
-- position indefinitely, which is more of their money's detail than this feature needs.

CREATE TABLE IF NOT EXISTS broker_funds_snapshot (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT NOT NULL,
    institution_id      BIGINT NOT NULL DEFAULT 1,
    broker_name         VARCHAR(32) NOT NULL DEFAULT 'dhan',
    -- Which broker login the figures belong to, so two accounts cannot be confused for one.
    client_id           VARCHAR(64),

    -- Dhan reports these separately and they mean different things: cash that can be withdrawn
    -- is not the same as buying power, which may include collateral and intraday limits.
    available_balance   DOUBLE PRECISION,
    withdrawable_balance DOUBLE PRECISION,
    utilized_margin     DOUBLE PRECISION,
    collateral_amount   DOUBLE PRECISION,
    buying_power        DOUBLE PRECISION,

    -- When the broker last answered. The age of this is what the screen must show when a
    -- refresh fails, so it is stored rather than derived.
    fetched_at          TIMESTAMP NOT NULL DEFAULT now(),
    raw_response        TEXT,
    CONSTRAINT uk_broker_funds UNIQUE (user_id, institution_id, broker_name, client_id)
);

CREATE INDEX IF NOT EXISTS idx_broker_funds_account
    ON broker_funds_snapshot (user_id, institution_id);
