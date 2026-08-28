-- Relocated from trep-institution-service (V8__trading_academy_tables.sql) on 2026-08-19.
-- Migration ownership rule: trep-core owns migrations for CRM, LMS, Finance,
-- Trading, AI and Platform tables; trep-institution-service owns ERP tables only.
-- Content is unchanged and fully idempotent (every CREATE/ALTER is guarded,
-- no INSERTs), so re-running it against an existing database is a no-op.

-- V8: Trading Academy supplementary tables
-- Reads existing shared tables (strategies, screeners, backtests, paper_trades,
-- real_trades, trading_journal, scanners, portfolios, ranking_config, broker_connections, users).
-- Creates institution-service-owned tables for risk management, journal sharing,
-- real trading gate, and ranking opt-outs.

-- ── Risk Management (BR-2.7.1) ───────────────────────────────────────────────
-- Hierarchical: Individual > Batch > Tenant > Platform default
CREATE TABLE IF NOT EXISTS ta_risk_configs (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    scope_level      VARCHAR(20) NOT NULL CHECK (scope_level IN ('platform','tenant','batch','individual')),
    batch_id         BIGINT,
    user_id          BIGINT,
    limit_type       VARCHAR(30) NOT NULL CHECK (limit_type IN ('max_daily_loss','position_size','order_frequency','leverage')),
    threshold_value  NUMERIC(18,4) NOT NULL,
    warning_pct      NUMERIC(5,2) NOT NULL DEFAULT 80.00,
    applies_paper    BOOLEAN NOT NULL DEFAULT FALSE,
    status           VARCHAR(20) NOT NULL DEFAULT 'active',
    created_by       BIGINT NOT NULL,
    created_at       VARCHAR(30) NOT NULL,
    updated_at       VARCHAR(30) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_trc_inst ON ta_risk_configs(institution_id);
CREATE INDEX IF NOT EXISTS idx_trc_scope ON ta_risk_configs(institution_id, scope_level, limit_type);
CREATE INDEX IF NOT EXISTS idx_trc_user ON ta_risk_configs(user_id);
CREATE INDEX IF NOT EXISTS idx_trc_batch ON ta_risk_configs(batch_id);

-- Risk events — warnings and hard blocks
CREATE TABLE IF NOT EXISTS ta_risk_events (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    user_id          BIGINT NOT NULL,
    risk_config_id   BIGINT REFERENCES ta_risk_configs(id),
    limit_type       VARCHAR(30) NOT NULL,
    event_type       VARCHAR(20) NOT NULL CHECK (event_type IN ('warning','block','escalation')),
    current_value    NUMERIC(18,4) NOT NULL,
    threshold_value  NUMERIC(18,4) NOT NULL,
    pct_of_limit     NUMERIC(5,2),
    trade_ref        VARCHAR(120),
    message          TEXT,
    acknowledged     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at       VARCHAR(30) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_tre_inst ON ta_risk_events(institution_id);
CREATE INDEX IF NOT EXISTS idx_tre_user ON ta_risk_events(user_id, created_at);

-- ── Journal Tags (BR-2.10) ───────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ta_journal_tags (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    tag_name         VARCHAR(60) NOT NULL,
    tag_type         VARCHAR(20) NOT NULL DEFAULT 'custom' CHECK (tag_type IN ('strategy','emotion','mistake','custom')),
    created_by       BIGINT NOT NULL,
    created_at       VARCHAR(30) NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_tjt_uniq ON ta_journal_tags(institution_id, tag_name);

-- Link journal entries to tags (many-to-many against shared trading_journal)
CREATE TABLE IF NOT EXISTS ta_journal_entry_tags (
    id               BIGSERIAL PRIMARY KEY,
    journal_id       BIGINT NOT NULL,
    tag_id           BIGINT NOT NULL REFERENCES ta_journal_tags(id),
    created_at       VARCHAR(30) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_tjet_journal ON ta_journal_entry_tags(journal_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_tjet_uniq ON ta_journal_entry_tags(journal_id, tag_id);

-- Journal sharing with faculty (BR-2.10: student controls visibility, default private)
CREATE TABLE IF NOT EXISTS ta_journal_shares (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    student_user_id  BIGINT NOT NULL,
    faculty_user_id  BIGINT NOT NULL,
    share_scope      VARCHAR(20) NOT NULL DEFAULT 'full' CHECK (share_scope IN ('full','trade_data_only')),
    active           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at       VARCHAR(30) NOT NULL,
    revoked_at       VARCHAR(30)
);
CREATE INDEX IF NOT EXISTS idx_tjs_student ON ta_journal_shares(student_user_id, active);
CREATE INDEX IF NOT EXISTS idx_tjs_faculty ON ta_journal_shares(faculty_user_id, active);

-- Journal reflection notes (extends shared trading_journal with user reflections)
CREATE TABLE IF NOT EXISTS ta_journal_reflections (
    id               BIGSERIAL PRIMARY KEY,
    journal_id       BIGINT NOT NULL,
    user_id          BIGINT NOT NULL,
    institution_id   BIGINT NOT NULL,
    reflection_notes TEXT,
    updated_at       VARCHAR(30) NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_tjr_journal ON ta_journal_reflections(journal_id);

-- ── Real Trading Gate (BR-2.5.1, BR-2.5.3) ──────────────────────────────────
-- Records each gating checkpoint a user must pass before Real Trading
CREATE TABLE IF NOT EXISTS ta_real_trading_gate (
    id                   BIGSERIAL PRIMARY KEY,
    institution_id       BIGINT NOT NULL,
    user_id              BIGINT NOT NULL,
    eligibility_passed   BOOLEAN NOT NULL DEFAULT FALSE,
    kyc_verified         BOOLEAN NOT NULL DEFAULT FALSE,
    broker_linked        BOOLEAN NOT NULL DEFAULT FALSE,
    risk_acknowledged    BOOLEAN NOT NULL DEFAULT FALSE,
    risk_ack_timestamp   VARCHAR(30),
    gate_status          VARCHAR(20) NOT NULL DEFAULT 'blocked' CHECK (gate_status IN ('blocked','cleared')),
    last_checked_at      VARCHAR(30) NOT NULL,
    cleared_at           VARCHAR(30),
    blocked_reason       TEXT
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_trtg_user ON ta_real_trading_gate(institution_id, user_id);

-- ── Ranking Opt-out (BR-2.9.1) ───────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ta_ranking_opt_out (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    user_id          BIGINT NOT NULL,
    opted_out        BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at       VARCHAR(30) NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_troo_user ON ta_ranking_opt_out(institution_id, user_id);

-- Ranking configuration per institution (scope, metric, period, real-trading opt-in)
CREATE TABLE IF NOT EXISTS ta_ranking_config (
    id                   BIGSERIAL PRIMARY KEY,
    institution_id       BIGINT NOT NULL,
    ranking_scope        VARCHAR(20) NOT NULL DEFAULT 'batch' CHECK (ranking_scope IN ('batch','branch','tenant')),
    ranking_metric       VARCHAR(30) NOT NULL DEFAULT 'absolute_return' CHECK (ranking_metric IN ('absolute_return','risk_adjusted_return','consistency')),
    ranking_period       VARCHAR(20) NOT NULL DEFAULT 'monthly' CHECK (ranking_period IN ('weekly','monthly','course_length')),
    include_real_trading BOOLEAN NOT NULL DEFAULT FALSE,
    status               VARCHAR(20) NOT NULL DEFAULT 'active',
    created_by           BIGINT NOT NULL,
    created_at           VARCHAR(30) NOT NULL,
    updated_at           VARCHAR(30) NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_trkc_inst ON ta_ranking_config(institution_id);

-- Ranking snapshots (nightly calculation)
CREATE TABLE IF NOT EXISTS ta_ranking_snapshots (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    config_id        BIGINT REFERENCES ta_ranking_config(id),
    scope_value      VARCHAR(60),
    user_id          BIGINT NOT NULL,
    rank_position    INTEGER NOT NULL,
    metric_value     NUMERIC(18,4),
    total_trades     INTEGER DEFAULT 0,
    win_rate         NUMERIC(5,2),
    snapshot_date    VARCHAR(30) NOT NULL,
    trading_mode     VARCHAR(10) NOT NULL DEFAULT 'paper' CHECK (trading_mode IN ('paper','real'))
);
CREATE INDEX IF NOT EXISTS idx_trs_inst ON ta_ranking_snapshots(institution_id, snapshot_date);
CREATE INDEX IF NOT EXISTS idx_trs_user ON ta_ranking_snapshots(user_id, snapshot_date);

-- Sync sequences
SELECT setval(pg_get_serial_sequence('ta_risk_configs', 'id'),
              COALESCE((SELECT MAX(id) FROM ta_risk_configs), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('ta_risk_events', 'id'),
              COALESCE((SELECT MAX(id) FROM ta_risk_events), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('ta_journal_tags', 'id'),
              COALESCE((SELECT MAX(id) FROM ta_journal_tags), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('ta_journal_entry_tags', 'id'),
              COALESCE((SELECT MAX(id) FROM ta_journal_entry_tags), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('ta_journal_shares', 'id'),
              COALESCE((SELECT MAX(id) FROM ta_journal_shares), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('ta_journal_reflections', 'id'),
              COALESCE((SELECT MAX(id) FROM ta_journal_reflections), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('ta_real_trading_gate', 'id'),
              COALESCE((SELECT MAX(id) FROM ta_real_trading_gate), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('ta_ranking_opt_out', 'id'),
              COALESCE((SELECT MAX(id) FROM ta_ranking_opt_out), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('ta_ranking_config', 'id'),
              COALESCE((SELECT MAX(id) FROM ta_ranking_config), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('ta_ranking_snapshots', 'id'),
              COALESCE((SELECT MAX(id) FROM ta_ranking_snapshots), 0) + 1, false);
