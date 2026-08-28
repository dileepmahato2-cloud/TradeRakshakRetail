-- broker_market_candles.created_at / updated_at were TEXT.
--
-- DateUtil.now() writes 'yyyy-MM-dd HH:mm:ss', so every value cost ~21 bytes on
-- disk where a native timestamp costs 8. Across ~1M candle rows that measured
-- 36 MB of pure formatting overhead, and this table has carried 27M+ rows.
--
-- Both columns are write-only audit stamps: they are set by the two candle
-- INSERTs and no query in the application reads, filters, or orders by them, so
-- narrowing the type is invisible to the rest of the schema.
--
-- timestamp, not timestamptz: the stored values are bare local wall-clock times
-- with no offset, so timestamptz would reinterpret them against the session
-- timezone. timestamp is the same 8 bytes and preserves the existing meaning.

ALTER TABLE broker_market_candles ALTER COLUMN created_at DROP DEFAULT;

ALTER TABLE broker_market_candles
    ALTER COLUMN created_at TYPE timestamp USING created_at::timestamp,
    ALTER COLUMN updated_at TYPE timestamp USING updated_at::timestamp;

ALTER TABLE broker_market_candles ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP;
