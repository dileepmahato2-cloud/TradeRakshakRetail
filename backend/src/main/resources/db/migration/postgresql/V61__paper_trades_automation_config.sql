-- The automation settings a simulated system trade actually ran under.
--
-- Section 5B lists "Automation Configuration" among the things a System Trade must record. V60
-- added that column to trading_journal, which covers the real and manual paths - but a simulated
-- system trade is written to paper_trades, and the journal reads it from there. Without a column
-- here, the one kind of trade automation produces most often was the one kind that could not say
-- what settings produced it.
--
-- Kept with the trade rather than looked up afterwards: the configuration changes, and a trade has
-- to stay explicable on the terms it was placed on rather than on today's. "Which buy time was in
-- force when this fired?" is unanswerable a week later otherwise.
--
-- Additive and nullable. Existing rows stay NULL, because the settings in force when they were
-- placed were not recorded and reconstructing them from today's values would be a guess presented
-- as history. A manual simulated trade also stays NULL - automation did not place it, so it has no
-- automation configuration, and that is a real answer rather than missing data.

ALTER TABLE paper_trades ADD COLUMN IF NOT EXISTS automation_config TEXT;

COMMENT ON COLUMN paper_trades.automation_config IS
    'JSON snapshot of the automation settings in force when the scheduled engine placed this trade. NULL for a manually placed simulation, and for rows written before this was recorded.';
