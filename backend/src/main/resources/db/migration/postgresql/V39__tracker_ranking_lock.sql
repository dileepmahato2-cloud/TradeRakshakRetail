-- "Previous Top 10" used to mean "whichever ranking run happened to be last on the
-- previous day". With the engine re-ranking every few minutes that is effectively
-- arbitrary: the list a user studied at 15:20 was replaced by a 15:25 run, and it was
-- the 15:25 one that resurfaced the next morning as Previous Top 10.
--
-- A run can now be locked. Locking marks one run per day as the official Top 10 for
-- that day, and that is the run Previous Top 10 reads the next day. Unlocked runs
-- still happen and are still stored - they simply no longer decide the record.
--
-- SMALLINT rather than BOOLEAN to match how the rest of this schema stores flags
-- (is_current_top10, is_active, is_deleted are all 0/1 integers).

ALTER TABLE tracker_rankings
    ADD COLUMN IF NOT EXISTS is_locked   SMALLINT DEFAULT 0;

ALTER TABLE tracker_rankings
    ADD COLUMN IF NOT EXISTS locked_at   TEXT;

-- Previous Top 10 asks for "the newest locked run before today" on every ranking run
-- and on every dashboard load, so the lookup gets its own partial index. Partial
-- because only locked rows are ever the target, and they are a small minority.
CREATE INDEX IF NOT EXISTS idx_tracker_rankings_locked
    ON tracker_rankings (institution_id, run_date DESC, id DESC)
    WHERE is_locked = 1;
