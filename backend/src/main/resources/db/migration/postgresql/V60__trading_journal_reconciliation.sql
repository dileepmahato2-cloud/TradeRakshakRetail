-- Reconciliation bookkeeping on the journal of record.
--
-- Section 6 requires a reconciliation timestamp and a source reference on every journal row that
-- a synchronisation touched, so a repeated sync can tell its own earlier import from a genuinely
-- new trade, and so a reader can see when a row was last checked against the broker and against
-- WHAT at the broker.
--
-- This is `trading_journal`, the system of record - deliberately NOT `trade_journal`, which is the
-- older read-only table, holds zero rows and is never written. An earlier attempt at this
-- migration targeted that table and was discarded.
--
-- Additive and nullable throughout. Existing rows are left NULL rather than stamped with a
-- reconciliation that never happened: "this row has never been reconciled" and "this row was
-- reconciled at the moment the migration ran" are different facts, and only the first is true.

ALTER TABLE trading_journal ADD COLUMN IF NOT EXISTS reconciled_at         TIMESTAMP;
ALTER TABLE trading_journal ADD COLUMN IF NOT EXISTS reconciliation_source VARCHAR(48);

-- Which broker record a row was matched against - an order id, a trade id, or the exchange's own
-- trade reference. Separate from broker_order_id because the two are not always the same thing:
-- one order can produce several exchange trades, and the trade book is the primary source for a
-- fill while the order book is the primary source for a status.
ALTER TABLE trading_journal ADD COLUMN IF NOT EXISTS source_reference      VARCHAR(128);

-- The automation settings a system trade actually ran under, kept with the trade rather than
-- looked up afterwards. Configuration changes, and a trade has to remain explicable on the terms
-- it was placed on rather than on today's.
ALTER TABLE trading_journal ADD COLUMN IF NOT EXISTS automation_config     TEXT;

COMMENT ON COLUMN trading_journal.reconciliation_source IS
    'Where the last reconciliation read from: DHAN_ORDER_BOOK, DHAN_TRADE_BOOK, or EXTERNAL_IMPORT. NULL = never reconciled.';
COMMENT ON COLUMN trading_journal.source_reference IS
    'The broker or exchange record this row was matched against, e.g. an exchange trade id.';
COMMENT ON COLUMN trading_journal.automation_config IS
    'JSON snapshot of the automation settings in force when a system trade was placed.';

-- Reconciliation sweeps the rows that carry a broker order id and asks which were checked least
-- recently, so both columns are in the index.
CREATE INDEX IF NOT EXISTS idx_tj_reconciliation
    ON trading_journal (user_id, reconciled_at)
    WHERE broker_order_id IS NOT NULL;

-- One broker execution may appear in only one journal row per account. This is what makes a
-- repeated import safe at the database rather than only in application logic: the second import
-- of the same execution violates the constraint instead of quietly creating a second copy of a
-- trade the user made once.
--
-- Partial, because a simulated fill legitimately has no source reference and several such rows
-- must be allowed to coexist.
CREATE UNIQUE INDEX IF NOT EXISTS ux_tj_source_reference
    ON trading_journal (user_id, institution_id, source_reference)
    WHERE source_reference IS NOT NULL;
