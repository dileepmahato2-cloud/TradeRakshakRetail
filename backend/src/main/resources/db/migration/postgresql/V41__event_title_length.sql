-- event_title was VARCHAR(240). Federal Reserve press-release headlines regularly exceed
-- that, and PostgreSQL rejects the row with "value too long for type character varying(240)".
-- Because the insert runs inside the scheduled event fetch, the rejection aborted the whole
-- transaction and every following statement failed with "current transaction is aborted",
-- so one long headline killed the entire calendar refresh - twice a day, every day.
--
-- TEXT rather than a larger VARCHAR: in PostgreSQL they are the same type internally, and a
-- fixed ceiling only defers the same failure to whenever a source publishes a longer title.
ALTER TABLE event_calendar ALTER COLUMN event_title TYPE TEXT;

-- Same exposure on the other free-text columns fed from external feeds.
ALTER TABLE event_calendar ALTER COLUMN source_url  TYPE TEXT;
ALTER TABLE event_calendar ALTER COLUMN description TYPE TEXT;
