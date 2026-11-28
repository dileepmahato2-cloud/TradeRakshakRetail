-- The exchange holiday calendar, as its own table fed from NSE.
--
-- 'market.holidays' (V31) shipped as an empty string and was never filled, so isMarketHoliday()
-- returned false for every date in history and nothing in the platform could tell a closed
-- exchange from a trading day. Three consequences, all observed:
--
--   1. The data-health panel reported every holiday as a MISSING_DAILY_CANDLES gap - its scan
--      skips weekends and holidays, and the holiday half never matched. That is a false alarm on
--      the one screen built to distinguish a real gap from a quiet day: 24 Nov 2026 was reported
--      for days as a session needing a backfill when the exchange was simply shut.
--   2. The live-feed and auto BUY/SELL guards treat a holiday as an ordinary session and start up
--      to wait for ticks that cannot arrive.
--   3. Buy/sell date validation accepted a holiday.
--
-- The bar charts were never affected: tradingDaysWithData() takes its sessions from the candle
-- table, so a holiday has never entered a simulation and the next-trading-day sell leg was always
-- correct.
--
-- A table rather than a longer comma-separated string in application_settings, because the
-- calendar is exchange reference data with a source, a fetch time and a reason per date, and it
-- has to be refreshed as NSE amends it - an election holiday was added to 2026 mid-year. The
-- config key stays as a manual override for a date NSE has not published yet.
--
-- Segment is kept because NSE publishes a different calendar per segment: for 2026 the capital
-- market (CM) closes 20 days while the corporate bond market (CBM) closes 24. Equity screening
-- reads CM; storing the rest costs a few hundred rows and makes this the whole published
-- calendar rather than one slice of it.

CREATE TABLE IF NOT EXISTS market_holidays (
    id            BIGSERIAL PRIMARY KEY,
    exchange      VARCHAR(16)  NOT NULL DEFAULT 'NSE',
    segment       VARCHAR(16)  NOT NULL DEFAULT 'CM',
    holiday_date  DATE         NOT NULL,
    week_day      VARCHAR(16),
    description   VARCHAR(255),
    source        VARCHAR(32)  NOT NULL DEFAULT 'NSE_API',
    fetched_at    TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uk_market_holidays UNIQUE (exchange, segment, holiday_date)
);

-- The equity lookup is "is this one date a holiday", answered per request, so it gets its own
-- narrow index rather than relying on the wider unique constraint.
CREATE INDEX IF NOT EXISTS idx_market_holidays_cm_date
    ON market_holidays (holiday_date)
    WHERE segment = 'CM';
