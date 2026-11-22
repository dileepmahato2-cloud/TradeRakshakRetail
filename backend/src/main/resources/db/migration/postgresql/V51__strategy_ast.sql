-- =============================================================================
-- Strategy Builder: the rule tree becomes the source of truth.
--
-- strategy_conditions stores a rule as flat rows with is_group_start/is_group_end
-- markers. That shape expresses one level of grouping - (A AND B) OR (C AND D) -
-- and cannot express what the builder now needs: arbitrary nesting, an ordered
-- IF / ELSE IF / ELSE chain, or an operand that is itself an expression, which
-- "Volume > 2 * AvgVolume20" requires.
--
-- Encoding a tree in condition_order integers is a tree with extra steps and no
-- validation, so the tree is stored as a tree. JSONB rather than text because
-- Postgres validates it on write, it can be queried from the outside ("which
-- strategies reference RSI?" is one @> away), and the rule engine deserialises
-- it without a join.
--
-- strategy_conditions is deliberately left in place and untouched. It still
-- feeds the existing builder screens; this column is additive, and nothing reads
-- rule_ast until a strategy has one.
-- =============================================================================

ALTER TABLE strategies ADD COLUMN IF NOT EXISTS rule_ast JSONB;

-- Version the shape itself, so a later change to the node schema can be migrated
-- rather than guessed at from the contents.
ALTER TABLE strategies ADD COLUMN IF NOT EXISTS ast_version INTEGER NOT NULL DEFAULT 1;

-- jsonb_path_ops rather than the default jsonb_ops: it indexes only the paths
-- that exist, which is a third of the size and faster for the containment
-- queries this column actually gets ("find strategies using this indicator").
-- It does not support key-existence (?) operators, which nothing here needs.
CREATE INDEX IF NOT EXISTS idx_strategies_rule_ast
    ON strategies USING GIN (rule_ast jsonb_path_ops);
