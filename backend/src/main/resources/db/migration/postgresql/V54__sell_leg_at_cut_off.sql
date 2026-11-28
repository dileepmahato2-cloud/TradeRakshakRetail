-- Both trade legs move to 15:15.
--
-- The buy leg was already 15:15 - the cut-off the RSI 45 shortlist is frozen at - while the sell
-- leg sat at 10:15, an hour into the following session. Two reasons to move it:
--
--   1. It is the strategy as specified: bought at the 15:15 cut-off and held to the same point
--      the next trading day.
--   2. Intraday candles are now 2 hours wide, so from the 09:15 open bars start 09:15, 11:15,
--      13:15 and 15:15. 10:15 is no longer a bar start, and a price lookup seeks the first bar
--      at or AFTER the time it is given - so a 10:15 sell would have silently resolved to 11:15
--      and priced a trade an hour later than the setting said.
--
-- Only rows still holding the old default are touched. A value someone has deliberately changed
-- is theirs and is left alone.

UPDATE application_settings
   SET setting_value = '15:15',
       default_value = '15:15',
       updated_at    = to_char(now(), 'YYYY-MM-DD"T"HH24:MI:SS')
 WHERE setting_key IN ('prevtop10.sell_time', 'top10.trade.sell_time')
   AND COALESCE(setting_value, '') = '10:15';

-- The buy legs are already 15:15 in every shipped configuration; this only repairs one that was
-- left blank, and never overwrites a chosen value.
UPDATE application_settings
   SET setting_value = '15:15',
       default_value = '15:15',
       updated_at    = to_char(now(), 'YYYY-MM-DD"T"HH24:MI:SS')
 WHERE setting_key IN ('prevtop10.buy_time', 'top10.trade.buy_time')
   AND COALESCE(setting_value, '') = '';
