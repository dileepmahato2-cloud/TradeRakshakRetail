ALTER TABLE event_calendar ADD COLUMN IF NOT EXISTS symbol TEXT;
ALTER TABLE broker_instruments ADD COLUMN IF NOT EXISTS symbol TEXT;
ALTER TABLE broker_market_candles ADD COLUMN IF NOT EXISTS symbol TEXT;
ALTER TABLE broker_api_fetch_jobs ADD COLUMN IF NOT EXISTS symbol TEXT;
ALTER TABLE broker_option_chain_snapshots ADD COLUMN IF NOT EXISTS symbol TEXT;
ALTER TABLE option_chain ADD COLUMN IF NOT EXISTS symbol TEXT;

UPDATE broker_instruments
SET symbol=UPPER(TRIM(underlying_symbol))
WHERE broker_name='dhan'
  AND COALESCE(underlying_symbol,'')<>''
  AND (COALESCE(symbol,'')='' OR UPPER(TRIM(symbol))<>UPPER(TRIM(underlying_symbol)));

UPDATE broker_market_candles b
SET symbol=UPPER(TRIM(bi.underlying_symbol))
FROM broker_instruments bi
WHERE b.broker_name='dhan'
  AND bi.broker_name='dhan'
  AND b.broker_security_id=bi.broker_security_id
  AND COALESCE(b.exchange_segment,'')=COALESCE(bi.exchange_segment,'')
  AND COALESCE(bi.underlying_symbol,'')<>''
  AND (COALESCE(b.symbol,'')='' OR UPPER(TRIM(b.symbol))<>UPPER(TRIM(bi.underlying_symbol)));

UPDATE broker_market_candles b
SET symbol=UPPER(TRIM(di.underlying_symbol))
FROM dhan_instruments di
WHERE b.broker_name='dhan'
  AND b.broker_security_id=di.security_id
  AND COALESCE(di.underlying_symbol,'')<>''
  AND (COALESCE(b.exchange_segment,'')='' OR COALESCE(di.exchange_segment,'')='' OR UPPER(b.exchange_segment)=UPPER(di.exchange_segment))
  AND (COALESCE(b.symbol,'')='' OR UPPER(TRIM(b.symbol))<>UPPER(TRIM(di.underlying_symbol)));

UPDATE broker_api_fetch_jobs j
SET symbol=UPPER(TRIM(di.underlying_symbol))
FROM dhan_instruments di
WHERE j.broker_name='dhan'
  AND j.broker_security_id=di.security_id
  AND COALESCE(di.underlying_symbol,'')<>''
  AND (COALESCE(j.symbol,'')='' OR UPPER(TRIM(j.symbol))<>UPPER(TRIM(di.underlying_symbol)));

UPDATE event_calendar SET symbol=UPPER(TRIM(stock_symbol)) WHERE COALESCE(symbol,'')='' AND COALESCE(stock_symbol,'')<>'';
UPDATE broker_api_fetch_jobs SET symbol=UPPER(TRIM(instrument_symbol)) WHERE COALESCE(symbol,'')='' AND COALESCE(instrument_symbol,'')<>'';
UPDATE broker_option_chain_snapshots SET symbol=UPPER(TRIM(underlying)) WHERE COALESCE(symbol,'')='' AND COALESCE(underlying,'')<>'';
UPDATE option_chain SET symbol=UPPER(TRIM(underlying)) WHERE COALESCE(symbol,'')='' AND COALESCE(underlying,'')<>'';

CREATE INDEX IF NOT EXISTS idx_event_calendar_symbol_std ON event_calendar(symbol);
CREATE INDEX IF NOT EXISTS idx_bi_symbol_std ON broker_instruments(symbol);
CREATE INDEX IF NOT EXISTS idx_bmc_symbol_std ON broker_market_candles(symbol);
CREATE INDEX IF NOT EXISTS idx_bafj_symbol ON broker_api_fetch_jobs(symbol);
CREATE INDEX IF NOT EXISTS idx_bocs_symbol ON broker_option_chain_snapshots(symbol);
CREATE INDEX IF NOT EXISTS idx_option_chain_symbol ON option_chain(symbol);
