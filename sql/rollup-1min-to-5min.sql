-- =============================================================================
--  Roll 1-minute intraday candles up into 5-minute candles
-- =============================================================================
--
--  WHY
--  Every intraday row stored is a 1-minute bar: 78,267,854 of them, about 19 GB of
--  data before indexes. Nothing in the application reads minute-level detail - the
--  tracker ranks on daily closes and volume, and the back-test needs two moments a
--  day (15:20 and 10:00). Five-minute bars carry the same information for those
--  purposes at a fifth of the size.
--
--  There was no 5-minute data to "keep": it all had to be derived from the 1-minute
--  bars first, which is what this does. Deleting the 1-minute rows without this step
--  would have left no intraday data at all.
--
--  HOW THE ROLLUP WORKS
--  Standard OHLCV aggregation into 5-minute buckets:
--      open   = first open in the bucket, by time
--      high   = highest high
--      low    = lowest low
--      close  = last close in the bucket, by time
--      volume = sum of volume
--  Verified by hand against RELIANCE 2026-01-01 09:15, which rolls four 1-minute
--  bars into open 1577.5 / high 1579 / low 1575.2 / close 1575.3 / volume 150039.
--
--  SAFETY
--  This script only INSERTs. The new rows carry interval_key = 5, while the existing
--  rows carry interval_key = 1, so the two never collide on the unique constraint and
--  nothing is overwritten. The 1-minute rows are left in place - deleting them is a
--  separate, deliberate step once you have checked the result.
--
--  To undo:  DELETE FROM broker_market_candles WHERE candle_mode='INTRADAY' AND interval_key=5;
-- =============================================================================

\timing on

INSERT INTO broker_market_candles (
    source_job_type, broker_name, broker_credential_id, data_type,
    exchange, exchange_segment, segment, instrument_type,
    broker_security_id, symbol, trading_symbol,
    candle_mode, interval_minutes, interval_key,
    candle_time, trade_date,
    open, high, low, close, volume, total_volume, open_interest,
    source, created_at, updated_at
)
SELECT
    'rollup-5min',
    broker_name,
    max(broker_credential_id),
    max(data_type),
    max(exchange),
    exchange_segment,
    max(segment),
    max(instrument_type),
    broker_security_id,
    symbol,
    max(trading_symbol),
    'INTRADAY',
    5,
    5,
    to_char(to_timestamp(floor(extract(epoch FROM candle_time::timestamp) / 300) * 300)
              AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS'),
    trade_date,
    (array_agg(open  ORDER BY candle_time ASC ))[1],
    max(high),
    min(low),
    (array_agg(close ORDER BY candle_time DESC))[1],
    sum(COALESCE(total_volume, volume)),
    sum(COALESCE(total_volume, volume)),
    0,
    'DHAN-ROLLUP',
    to_char(now(), 'YYYY-MM-DD HH24:MI:SS'),
    to_char(now(), 'YYYY-MM-DD HH24:MI:SS')
FROM broker_market_candles
WHERE candle_mode = 'INTRADAY'
  AND interval_key = 1
  AND close IS NOT NULL
  AND trade_date = :'day'
GROUP BY broker_name, exchange_segment, broker_security_id, symbol, trade_date,
         floor(extract(epoch FROM candle_time::timestamp) / 300)
ON CONFLICT (broker_name, broker_security_id, exchange_segment, candle_mode, interval_key, candle_time)
DO NOTHING;
