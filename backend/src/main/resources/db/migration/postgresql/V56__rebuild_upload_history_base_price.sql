-- Rebuild stock_upload_history.base_price from the rule the uploaded file actually states:
-- a stock's base price is its CLOSING PRICE on the date given for it in that file.
--
-- The table did not obey that rule. Measured before this migration ran: of 3,769 rows, 3,600 had
-- a daily candle on their own entry_date, and only 456 of those carried that day's close. 3,144
-- carried something else. ACE is the worked example - its 2026-04-10 row held 1,132.50, which is
-- ACE's close on 2025-07-31, eight months before the date on the row.
--
-- Why it mattered: this table is what the entry test reads to decide "Previous Close < Base
-- Price". On the live server its values disagreed with the Stock Upload screen for 499 of 502
-- stocks, from -42% to +35%, so every crossing verdict was computed against a number the user
-- never set and could not see. It produced false positives (ACE "crossed" on 26 Nov 2026 on a
-- base of 1,211.70 when its own file says 1,167.70) and false negatives (CONFIPET and GEOJITFSL
-- were crossing on 27 Nov and absent from the list).
--
-- The old values are copied to stock_upload_history_base_price_backup first, so this is
-- reversible: see the commented UPDATE at the foot of this file.

CREATE TABLE IF NOT EXISTS stock_upload_history_base_price_backup AS
SELECT id, base_price, base_price_date, base_price_source, now() AS backed_up_at
FROM stock_upload_history;

-- Resolve each row to the close on its entry_date.
--
-- When that date did not trade - a weekend, an exchange holiday, or simply a session this
-- database does not hold - the last session ON OR BEFORE it is used instead, and the row records
-- which date was actually used. 169 of the 3,769 rows are in that position. Stepping back is the
-- only defensible reading: a file dated on a Sunday still means "the price as the market last
-- left it", and stepping FORWARD would take a price that did not exist when the file was written.
WITH resolved AS (
    SELECT h.id,
           c.trade_date::date AS used_date,
           c.close            AS used_close
    FROM stock_upload_history h
    JOIN LATERAL (
        SELECT bmc.trade_date, bmc.close
        FROM broker_market_candles bmc
        WHERE UPPER(COALESCE(bmc.symbol, '')) = UPPER(h.symbol)
          AND UPPER(COALESCE(bmc.candle_mode, '')) = 'DAILY'
          AND bmc.trade_date <= h.entry_date::text
          AND bmc.close IS NOT NULL
          AND bmc.close > 0
        ORDER BY bmc.trade_date DESC
        LIMIT 1
    ) c ON TRUE
)
UPDATE stock_upload_history h
   SET base_price        = r.used_close,
       base_price_date   = r.used_date,
       base_price_source = CASE WHEN r.used_date = h.entry_date
                                THEN 'CLOSE_ON_ENTRY_DATE'
                                ELSE 'CLOSE_PRIOR_SESSION' END
  FROM resolved r
 WHERE r.id = h.id
   AND h.base_price IS DISTINCT FROM r.used_close;

-- A row whose symbol has no daily candle at or before its entry_date keeps whatever it had:
-- there is nothing to derive a close from, and blanking it would drop the stock out of every
-- screen. Those rows are findable by base_price_source still being its old value.

-- ---------------------------------------------------------------------------------------------
-- To undo:
--
--   UPDATE stock_upload_history h
--      SET base_price        = b.base_price,
--          base_price_date   = b.base_price_date,
--          base_price_source = b.base_price_source
--     FROM stock_upload_history_base_price_backup b
--    WHERE b.id = h.id;
-- ---------------------------------------------------------------------------------------------
