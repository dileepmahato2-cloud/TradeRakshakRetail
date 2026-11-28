-- Numbered condition groups for the Strategy Builder.
--
-- strategy_conditions already had condition_group and group_operator, but condition_group holds
-- "entry" or "exit" - which half of the strategy a row belongs to - not which numbered group
-- inside that half. The builder lets conditions be put into groups ("all of these" / "any one of
-- these") and joined, and without somewhere to record that, a saved strategy came back as a flat
-- list that the screener folded left to right. The same conditions in a different row order then
-- meant a different strategy, silently.
--
-- All three are nullable with no default on purpose. A null group_index reads as "one implicit
-- group", which is exactly how every strategy saved before this evaluated, so nothing that
-- already exists changes behaviour.

ALTER TABLE strategy_conditions ADD COLUMN IF NOT EXISTS group_index TEXT;
ALTER TABLE strategy_conditions ADD COLUMN IF NOT EXISTS group_mode  TEXT;
ALTER TABLE strategy_conditions ADD COLUMN IF NOT EXISTS group_join  TEXT;

COMMENT ON COLUMN strategy_conditions.group_index IS
  'Which numbered group inside the entry/exit half this condition belongs to. Null means the single implicit group, which is pre-group behaviour.';
COMMENT ON COLUMN strategy_conditions.group_mode IS
  'ALL (every condition in the group must hold) or ANY (at least one must). Null falls back to folding the per-row operators.';
COMMENT ON COLUMN strategy_conditions.group_join IS
  'AND or OR, joining this group to the next one. Read from the last condition of the group.';
