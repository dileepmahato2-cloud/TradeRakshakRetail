-- Let a strategy say WHEN its base price is taken from, instead of only inheriting the one
-- value that was uploaded with the stock.
--
-- The ATR Base Price Crossover is defined against a base price, but until now that price only
-- ever existed as tracked_stocks.base_price: a single number per stock, fixed at upload. That
-- makes the rule impossible to test at any other reference point - you cannot ask "what if the
-- base had been the 21 August close?" without re-uploading every stock.
--
-- Both columns are nullable and both default to null, which means "keep using the uploaded base
-- price". Nothing changes for an existing strategy until a date is actually chosen.
--
--   base_price_date  yyyy-MM-dd. The session the base price is read from. A non-trading day or
--                    a day with no bar falls back to the last session on or before it.
--   base_price_time  HH:mm, optional. With it, the base is the last 5-minute candle at or before
--                    that time on that session. Without it, the session's closing price.

SET LOCAL lock_timeout = '60s';

ALTER TABLE strategies ADD COLUMN IF NOT EXISTS base_price_date text;
ALTER TABLE strategies ADD COLUMN IF NOT EXISTS base_price_time text;

COMMENT ON COLUMN strategies.base_price_date IS
    'yyyy-MM-dd the base price is read from; null = use the uploaded tracked_stocks.base_price';
COMMENT ON COLUMN strategies.base_price_time IS
    'HH:mm within base_price_date; null = that session''s close';
