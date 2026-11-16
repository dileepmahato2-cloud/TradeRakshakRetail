-- Which stocks belong to which index.
--
-- stock_master already carried nifty50_flag and nifty500_flag, but a column per index does not
-- survive contact with reality: a stock is routinely in NIFTY 500, NIFTY MIDCAP 150 and NIFTY
-- PHARMA at the same time, and NSE publishes around twenty lists that matter here. A membership
-- row per (stock, index) holds all of that without a schema change every time an index is added.
--
-- The two existing flags are kept in sync by the loader rather than dropped, so anything already
-- reading them keeps working.
CREATE TABLE IF NOT EXISTS stock_index_membership (
    id           BIGSERIAL PRIMARY KEY,
    symbol       VARCHAR(64)  NOT NULL,   -- normalized: upper, trimmed
    index_key    VARCHAR(64)  NOT NULL,   -- NIFTY_500, NIFTY_BANK, ...
    index_name   VARCHAR(128),            -- as NSE labels it
    company_name VARCHAR(256),
    industry     VARCHAR(128),            -- NSE's own industry label from the constituent file
    isin         VARCHAR(24),
    series       VARCHAR(16),
    source       VARCHAR(32)  DEFAULT 'NSE',
    as_on_date   VARCHAR(10),             -- when this list was last refreshed
    is_active    INTEGER      DEFAULT 1,  -- 0 once a stock drops out at a rebalance
    created_at   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_stock_index UNIQUE (symbol, index_key)
);

-- "which indices is this stock in" - the per-stock lookup the screens make.
CREATE INDEX IF NOT EXISTS idx_sim_symbol ON stock_index_membership (symbol) WHERE is_active = 1;
-- "which stocks are in this index" - the filter direction.
CREATE INDEX IF NOT EXISTS idx_sim_index  ON stock_index_membership (index_key) WHERE is_active = 1;
