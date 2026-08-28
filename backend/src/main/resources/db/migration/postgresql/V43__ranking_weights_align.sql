-- Align the stored ranking weights with the engine.
--
-- Context: the scoring code redistributed Delivery % and Sector Strength (20 points) across the
-- eleven surviving factors so they total 100, but tracker_ranking_config was never migrated. It
-- still carried both dead factors AND the pre-redistribution weights, and since loadTrackerWeights
-- reads that table, the stale values won over the code defaults: the eleven live factors summed to
-- 90, not 100. Scores were therefore computed against a weight base that matched neither the code
-- nor the documented model.

-- 1. The two factors the engine no longer scores. Disabled rather than deleted, so the historical
--    reason for their weights stays visible in the table.
UPDATE tracker_ranking_config SET is_enabled = 0
 WHERE factor_key IN ('delivery_pct', 'sector_strength');

-- 2. The eleven live factors, at the weights the engine's own defaults use. These total exactly 100.
UPDATE tracker_ranking_config SET weight = 17 WHERE factor_key = 'relative_strength';
UPDATE tracker_ranking_config SET weight = 11 WHERE factor_key = 'volume_growth';
UPDATE tracker_ranking_config SET weight = 11 WHERE factor_key = 'trend_strength';
UPDATE tracker_ranking_config SET weight =  9 WHERE factor_key = 'rsi_strength';
UPDATE tracker_ranking_config SET weight =  9 WHERE factor_key = 'ma_position';
UPDATE tracker_ranking_config SET weight =  9 WHERE factor_key = 'price_momentum';
UPDATE tracker_ranking_config SET weight =  8 WHERE factor_key = 'week52_position';
UPDATE tracker_ranking_config SET weight =  8 WHERE factor_key = 'breakout_strength';
UPDATE tracker_ranking_config SET weight =  8 WHERE factor_key = 'institutional_interest';
UPDATE tracker_ranking_config SET weight =  5 WHERE factor_key = 'liquidity';
UPDATE tracker_ranking_config SET weight =  5 WHERE factor_key = 'market_cap_quality';
