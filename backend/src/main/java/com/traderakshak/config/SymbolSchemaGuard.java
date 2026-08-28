package com.traderakshak.config;

import com.traderakshak.service.AppConfigService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps stock-linked tables aligned on the common application key: symbol.
 * This runs at startup because local/dev deployments may run with Flyway disabled.
 */
@Component
@Slf4j
public class SymbolSchemaGuard implements ApplicationRunner {

    @PersistenceContext
    private EntityManager em;

    private final TransactionTemplate tx;
    private final AppConfigService appConfigService;

    public SymbolSchemaGuard(PlatformTransactionManager transactionManager, AppConfigService appConfigService) {
        this.tx = new TransactionTemplate(transactionManager);
        this.appConfigService = appConfigService;
    }

    @Override
    public void run(ApplicationArguments args) {
        addTrackerRuntimeColumns();
        addStockMasterPreviewColumns();
        addApplicationSettingsTables();
        addDataFetchPerformanceIndexes();
        addSymbol("event_calendar");
        addSymbol("broker_instruments");
        addSymbol("broker_market_candles");
        addSymbol("broker_api_fetch_jobs");
        addSymbol("broker_option_chain_snapshots");
        addSymbol("option_chain");
        addTradeBatchTables();
        backfillSymbols();
        appConfigService.deferredInit();
    }

    private void addStockMasterPreviewColumns() {
        addColumn("stock_master", "action_type TEXT DEFAULT 'fetched'");
        addColumn("stock_master", "remarks TEXT");
        addColumn("stock_master", "created_at TEXT");
        addColumn("stock_master", "updated_at TEXT");
        addColumn("stock_master", "uploaded_at TEXT");
        addColumn("stock_master", "fetched_at TEXT");
        addIndex("stock_master", "idx_stock_master_action_type", "action_type");
        addIndex("stock_master", "idx_stock_master_source", "source");
        addIndex("stock_master", "idx_stock_master_status", "status");
        addIndex("stock_master", "idx_stock_master_created_at", "created_at");
        addIndex("stock_master", "idx_stock_master_exchange", "exchange");
        execute("UPDATE stock_master SET created_at = COALESCE(as_on_date, '2024-01-01') WHERE created_at IS NULL");
        execute("UPDATE stock_master SET action_type = 'fetched' WHERE action_type IS NULL");
        execute("CREATE TABLE IF NOT EXISTS upload_logs (" +
            "id BIGSERIAL PRIMARY KEY, user_id BIGINT, file_name TEXT, data_type VARCHAR(100), " +
            "source VARCHAR(50) DEFAULT 'manual', status VARCHAR(30) DEFAULT 'completed', " +
            "total_records INTEGER DEFAULT 0, imported_records INTEGER DEFAULT 0, " +
            "failed_records INTEGER DEFAULT 0, duplicate_records INTEGER DEFAULT 0, " +
            "skipped_records INTEGER DEFAULT 0, error_message TEXT, uploaded_at TEXT, created_at TEXT)");
        addIndex("upload_logs", "idx_upload_logs_user", "user_id");
        addIndex("upload_logs", "idx_upload_logs_data_type", "data_type");
        addIndex("upload_logs", "idx_upload_logs_created_at", "created_at");
        execute("CREATE TABLE IF NOT EXISTS dhan_backfill_batches (" +
            "id BIGSERIAL PRIMARY KEY, job_id BIGINT NOT NULL, batch_index INTEGER DEFAULT 0, " +
            "month_label TEXT, from_date TEXT, to_date TEXT, status VARCHAR(30) DEFAULT 'pending', " +
            "total_instruments INTEGER DEFAULT 0, processed_instruments INTEGER DEFAULT 0, " +
            "imported_candles INTEGER DEFAULT 0, failed_instruments INTEGER DEFAULT 0, " +
            "skipped_instruments INTEGER DEFAULT 0, successful_instruments INTEGER DEFAULT 0, " +
            "error_message TEXT, started_at TEXT, completed_at TEXT, created_at TEXT)");
        addIndex("dhan_backfill_batches", "idx_dbb_job", "job_id, batch_index");
        addIndex("dhan_backfill_batches", "idx_dbb_status", "job_id, status");
        addColumn("institutions", "logo_url TEXT");
        addColumn("institutions", "address TEXT");
        addColumn("institutions", "city TEXT");
        addColumn("institutions", "state TEXT");
        addColumn("institutions", "pincode TEXT");
        addColumn("institutions", "contact_person TEXT");
        addColumn("institutions", "contact_email TEXT");
        addColumn("institutions", "contact_mobile TEXT");
        addColumn("institutions", "subscription_plan TEXT");
        addColumn("institutions", "subscription_expiry TEXT");
        execute("CREATE TABLE IF NOT EXISTS institution_classes (" +
            "id BIGSERIAL PRIMARY KEY, institution_id BIGINT, class_name TEXT NOT NULL, " +
            "batch_name TEXT, section TEXT, academic_year TEXT, schedule TEXT, " +
            "status VARCHAR(30) DEFAULT 'active', created_by BIGINT, created_at TEXT, updated_at TEXT)");
        addIndex("institution_classes", "idx_ic_inst", "institution_id");
        execute("CREATE TABLE IF NOT EXISTS institution_courses (" +
            "id BIGSERIAL PRIMARY KEY, institution_id BIGINT, course_name TEXT NOT NULL, " +
            "subject_name TEXT, description TEXT, duration TEXT, " +
            "status VARCHAR(30) DEFAULT 'active', created_by BIGINT, created_at TEXT, updated_at TEXT)");
        addIndex("institution_courses", "idx_icrs_inst", "institution_id");
        execute("CREATE TABLE IF NOT EXISTS institution_assignments (" +
            "id BIGSERIAL PRIMARY KEY, institution_id BIGINT, user_id BIGINT, " +
            "assignment_type VARCHAR(50), class_id BIGINT, course_id BIGINT, " +
            "role VARCHAR(50), status VARCHAR(30) DEFAULT 'active', " +
            "assigned_by BIGINT, created_at TEXT, updated_at TEXT)");
        addIndex("institution_assignments", "idx_ia_inst", "institution_id, assignment_type");
        addIndex("institution_assignments", "idx_ia_user", "user_id");
        execute("CREATE TABLE IF NOT EXISTS institution_role_permissions (" +
            "id BIGSERIAL PRIMARY KEY, institution_id BIGINT, role VARCHAR(50) NOT NULL, " +
            "menu_key TEXT, action_key TEXT, allowed INTEGER DEFAULT 1, " +
            "created_by BIGINT, created_at TEXT, updated_at TEXT, " +
            "UNIQUE(institution_id, role, menu_key, action_key))");
        addIndex("institution_role_permissions", "idx_irp_inst_role", "institution_id, role");

        execute("CREATE TABLE IF NOT EXISTS subscription_plans (" +
            "id BIGSERIAL PRIMARY KEY, plan_name VARCHAR(100) NOT NULL, plan_category VARCHAR(50) NOT NULL, " +
            "monthly_price DOUBLE PRECISION DEFAULT 0, quarterly_price DOUBLE PRECISION DEFAULT 0, " +
            "half_yearly_price DOUBLE PRECISION DEFAULT 0, yearly_price DOUBLE PRECISION DEFAULT 0, " +
            "trial_days INTEGER DEFAULT 0, description TEXT, " +
            "status VARCHAR(30) DEFAULT 'active', created_by BIGINT, created_at TEXT, updated_at TEXT)");
        addIndex("subscription_plans", "idx_sp_category", "plan_category");
        addIndex("subscription_plans", "idx_sp_status", "status");

        execute("CREATE TABLE IF NOT EXISTS plan_features (" +
            "id BIGSERIAL PRIMARY KEY, plan_id BIGINT NOT NULL, feature_key VARCHAR(100) NOT NULL, " +
            "feature_label TEXT, allowed INTEGER DEFAULT 1, " +
            "created_at TEXT, updated_at TEXT, " +
            "UNIQUE(plan_id, feature_key))");
        addIndex("plan_features", "idx_pf_plan", "plan_id");

        execute("CREATE TABLE IF NOT EXISTS subscriptions (" +
            "id BIGSERIAL PRIMARY KEY, user_id BIGINT NOT NULL, plan_id BIGINT NOT NULL, " +
            "billing_cycle VARCHAR(30) NOT NULL, price DOUBLE PRECISION DEFAULT 0, " +
            "start_date TEXT, end_date TEXT, renewal_date TEXT, " +
            "trial_active INTEGER DEFAULT 0, auto_renew INTEGER DEFAULT 1, " +
            "subscription_status VARCHAR(30) DEFAULT 'active', payment_status VARCHAR(30) DEFAULT 'pending', " +
            "cancelled_at TEXT, upgraded_from BIGINT, downgraded_from BIGINT, " +
            "created_at TEXT, updated_at TEXT)");
        addColumn("subscriptions", "subscription_status VARCHAR(30) DEFAULT 'active'");
        addColumn("subscriptions", "payment_status VARCHAR(30) DEFAULT 'pending'");
        addColumn("subscriptions", "trial_active INTEGER DEFAULT 0");
        addColumn("subscriptions", "auto_renew INTEGER DEFAULT 1");
        addColumn("subscriptions", "billing_cycle VARCHAR(30)");
        addColumn("subscriptions", "price DOUBLE PRECISION DEFAULT 0");
        addColumn("subscriptions", "start_date TEXT");
        addColumn("subscriptions", "end_date TEXT");
        addColumn("subscriptions", "renewal_date TEXT");
        addIndex("subscriptions", "idx_sub_user", "user_id");
        addIndex("subscriptions", "idx_sub_plan", "plan_id");
        addIndex("subscriptions", "idx_sub_status", "subscription_status");

        execute("CREATE TABLE IF NOT EXISTS payments (" +
            "id BIGSERIAL PRIMARY KEY, user_id BIGINT NOT NULL, subscription_id BIGINT, plan_id BIGINT, " +
            "billing_cycle VARCHAR(30), amount DOUBLE PRECISION DEFAULT 0, " +
            "gst DOUBLE PRECISION DEFAULT 0, discount DOUBLE PRECISION DEFAULT 0, " +
            "net_amount DOUBLE PRECISION DEFAULT 0, " +
            "gateway_name VARCHAR(50), gateway_mode VARCHAR(20) DEFAULT 'test', " +
            "gateway_order_id TEXT, transaction_id TEXT, " +
            "payment_status VARCHAR(30) DEFAULT 'pending', payment_date TEXT, " +
            "invoice_number TEXT, receipt_url TEXT, " +
            "refund_amount DOUBLE PRECISION DEFAULT 0, refund_date TEXT, refund_reason TEXT, " +
            "created_at TEXT, updated_at TEXT)");
        addIndex("payments", "idx_pay_user", "user_id");
        addIndex("payments", "idx_pay_sub", "subscription_id");
        addIndex("payments", "idx_pay_status", "payment_status");
        addIndex("payments", "idx_pay_gateway", "gateway_name, gateway_mode");

        execute("CREATE TABLE IF NOT EXISTS payment_logs (" +
            "id BIGSERIAL PRIMARY KEY, payment_id BIGINT NOT NULL, " +
            "gateway_name VARCHAR(50), gateway_mode VARCHAR(20), " +
            "request_payload TEXT, response_payload TEXT, " +
            "error_message TEXT, webhook_status VARCHAR(30), " +
            "retry_count INTEGER DEFAULT 0, " +
            "created_at TEXT, updated_at TEXT)");
        addIndex("payment_logs", "idx_plog_payment", "payment_id");

        execute("CREATE TABLE IF NOT EXISTS invoices (" +
            "id BIGSERIAL PRIMARY KEY, payment_id BIGINT NOT NULL, user_id BIGINT NOT NULL, " +
            "invoice_number TEXT NOT NULL, invoice_date TEXT, " +
            "plan_name TEXT, billing_cycle VARCHAR(30), " +
            "amount DOUBLE PRECISION DEFAULT 0, gst DOUBLE PRECISION DEFAULT 0, " +
            "discount DOUBLE PRECISION DEFAULT 0, net_amount DOUBLE PRECISION DEFAULT 0, " +
            "status VARCHAR(30) DEFAULT 'generated', " +
            "created_at TEXT)");
        addIndex("invoices", "idx_inv_user", "user_id");
        addIndex("invoices", "idx_inv_payment", "payment_id");

        execute("CREATE TABLE IF NOT EXISTS user_plan_permissions (" +
            "id BIGSERIAL PRIMARY KEY, plan_category VARCHAR(50) NOT NULL, " +
            "billing_cycle VARCHAR(30), menu_key TEXT NOT NULL, action_key VARCHAR(30), " +
            "allowed INTEGER DEFAULT 1, " +
            "created_by BIGINT, created_at TEXT, updated_at TEXT)");
        addIndex("user_plan_permissions", "idx_upp_cat", "plan_category");

        execute("CREATE TABLE IF NOT EXISTS technical_indicator_cache (" +
            "id BIGSERIAL PRIMARY KEY, symbol TEXT NOT NULL, exchange TEXT, " +
            "indicator_name VARCHAR(50) NOT NULL, indicator_params TEXT, " +
            "timeframe VARCHAR(20) DEFAULT 'DAILY', " +
            "value DOUBLE PRECISION, value_json TEXT, " +
            "computed_at TEXT NOT NULL, expires_at TEXT, " +
            "data_points INTEGER DEFAULT 0, " +
            "UNIQUE(symbol, indicator_name, indicator_params, timeframe))");
        addIndex("technical_indicator_cache", "idx_tic_symbol", "symbol, indicator_name");
        addIndex("technical_indicator_cache", "idx_tic_expires", "expires_at");

        execute("CREATE TABLE IF NOT EXISTS indicator_calculation_logs (" +
            "id BIGSERIAL PRIMARY KEY, symbol TEXT, exchange TEXT, " +
            "indicator_name VARCHAR(50), timeframe VARCHAR(20), " +
            "status VARCHAR(30) DEFAULT 'success', " +
            "duration_ms INTEGER DEFAULT 0, data_points INTEGER DEFAULT 0, " +
            "error_message TEXT, created_at TEXT)");
        addIndex("indicator_calculation_logs", "idx_icl_symbol", "symbol, indicator_name");
        addIndex("indicator_calculation_logs", "idx_icl_created", "created_at");

        execute("CREATE TABLE IF NOT EXISTS stock_signals (" +
            "id BIGSERIAL PRIMARY KEY, symbol TEXT NOT NULL, exchange TEXT, " +
            "signal_type VARCHAR(50) NOT NULL, signal_value VARCHAR(30), " +
            "indicator_name VARCHAR(50), indicator_value DOUBLE PRECISION, " +
            "timeframe VARCHAR(20) DEFAULT 'DAILY', " +
            "confidence DOUBLE PRECISION DEFAULT 0, " +
            "triggered_at TEXT NOT NULL, expires_at TEXT, " +
            "acknowledged INTEGER DEFAULT 0, " +
            "UNIQUE(symbol, signal_type, timeframe, triggered_at))");
        addIndex("stock_signals", "idx_ss_symbol", "symbol, signal_type");
        addIndex("stock_signals", "idx_ss_triggered", "triggered_at");

        execute("CREATE TABLE IF NOT EXISTS strategy_indicator_mapping (" +
            "id BIGSERIAL PRIMARY KEY, strategy_id BIGINT NOT NULL, " +
            "indicator_name VARCHAR(50) NOT NULL, " +
            "indicator_params TEXT, position VARCHAR(20) DEFAULT 'LEFT', " +
            "display_order INTEGER DEFAULT 0, " +
            "created_at TEXT, updated_at TEXT, " +
            "UNIQUE(strategy_id, indicator_name, position))");
        addIndex("strategy_indicator_mapping", "idx_sim_strategy", "strategy_id");

        execute("CREATE TABLE IF NOT EXISTS indicator_settings (" +
            "id BIGSERIAL PRIMARY KEY, setting_key VARCHAR(100) NOT NULL UNIQUE, " +
            "setting_value TEXT, description TEXT, " +
            "updated_by BIGINT, updated_at TEXT)");

        addColumn("fundamentals", "company_name TEXT");
        addColumn("fundamentals", "sector TEXT");
        addColumn("fundamentals", "industry TEXT");
        addColumn("fundamentals", "market_cap_name TEXT");
        addColumn("fundamentals", "pe_ratio REAL");
        addColumn("fundamentals", "pb_ratio REAL");
        addColumn("fundamentals", "debt_to_equity REAL");
        addColumn("fundamentals", "face_value REAL");
        addColumn("fundamentals", "week_52_high REAL");
        addColumn("fundamentals", "week_52_low REAL");
        addColumn("fundamentals", "updated_at TEXT");
        addIndex("fundamentals", "idx_fundamentals_symbol", "symbol");

        log.info("Stock Master Preview, Institution, Subscription & Indicator schema verified.");
    }

    private void addApplicationSettingsTables() {
        execute("CREATE TABLE IF NOT EXISTS application_settings (" +
            "id BIGSERIAL PRIMARY KEY, " +
            "category VARCHAR(100) NOT NULL, " +
            "setting_key VARCHAR(200) NOT NULL UNIQUE, " +
            "setting_name VARCHAR(200) NOT NULL, " +
            "setting_value TEXT, " +
            "data_type VARCHAR(30) DEFAULT 'STRING', " +
            "default_value TEXT, " +
            "min_value TEXT, " +
            "max_value TEXT, " +
            "description TEXT, " +
            "is_editable INTEGER DEFAULT 1, " +
            "is_active INTEGER DEFAULT 1, " +
            "display_order INTEGER DEFAULT 0, " +
            "config_version INTEGER DEFAULT 1, " +
            "created_by BIGINT, " +
            "updated_by BIGINT, " +
            "created_at TEXT, " +
            "updated_at TEXT)");
        addIndex("application_settings", "idx_appsetting_category", "category");
        addIndex("application_settings", "idx_appsetting_active", "is_active");

        execute("CREATE TABLE IF NOT EXISTS config_audit_log (" +
            "id BIGSERIAL PRIMARY KEY, " +
            "setting_key VARCHAR(200) NOT NULL, " +
            "category VARCHAR(100), " +
            "old_value TEXT, " +
            "new_value TEXT, " +
            "changed_by BIGINT, " +
            "changed_by_name TEXT, " +
            "ip_address TEXT, " +
            "browser TEXT, " +
            "reason TEXT, " +
            "config_version INTEGER DEFAULT 1, " +
            "created_at TEXT)");
        addIndex("config_audit_log", "idx_cal_key", "setting_key");
        addIndex("config_audit_log", "idx_cal_created", "created_at");
        addIndex("config_audit_log", "idx_cal_changed_by", "changed_by");

        execute("CREATE TABLE IF NOT EXISTS user_feature_access (" +
            "id BIGSERIAL PRIMARY KEY, " +
            "user_id BIGINT NOT NULL, " +
            "feature_key VARCHAR(200) NOT NULL, " +
            "allowed INTEGER DEFAULT 1, " +
            "reason TEXT, " +
            "granted_by BIGINT, " +
            "created_at TEXT, " +
            "updated_at TEXT, " +
            "UNIQUE(user_id, feature_key))");
        addIndex("user_feature_access", "idx_ufa_user", "user_id");

        execute("CREATE TABLE IF NOT EXISTS signal_history (" +
            "id BIGSERIAL PRIMARY KEY, " +
            "symbol VARCHAR(100) NOT NULL, " +
            "signal_type VARCHAR(20) NOT NULL, " +
            "signal_date TEXT NOT NULL, " +
            "signal_price DOUBLE PRECISION, " +
            "previous_signal VARCHAR(20), " +
            "rsi_value DOUBLE PRECISION, " +
            "weekly_rsi DOUBLE PRECISION, " +
            "trend VARCHAR(30), " +
            "score DOUBLE PRECISION, " +
            "source VARCHAR(50) DEFAULT 'RANKING_ENGINE', " +
            "institution_id BIGINT, " +
            "user_id BIGINT, " +
            "created_at TEXT DEFAULT CURRENT_TIMESTAMP)");
        addIndex("signal_history", "idx_sighist_symbol", "symbol");
        addIndex("signal_history", "idx_sighist_date", "signal_date");
        addIndex("signal_history", "idx_sighist_inst", "institution_id");
        addIndex("signal_history", "idx_sighist_type", "signal_type");

        log.info("Application Settings, Config Audit & Signal History tables verified.");
    }

    private void addDataFetchPerformanceIndexes() {
        execute("CREATE TABLE IF NOT EXISTS data_fetch_logs (" +
            "id BIGSERIAL PRIMARY KEY, user_id BIGINT, data_type TEXT, source TEXT, " +
            "from_date TEXT, to_date TEXT, status VARCHAR(30), error_code TEXT, " +
            "rows_imported INTEGER DEFAULT 0, files_fetched INTEGER DEFAULT 0, " +
            "skipped_count INTEGER DEFAULT 0, duration_ms BIGINT DEFAULT 0, " +
            "message TEXT, details_json JSONB, created_at TEXT)");
        addIndex("data_fetch_logs", "idx_dfl_user_created", "user_id, created_at DESC");
        addIndex("data_fetch_logs", "idx_dfl_status", "status");
        addIndex("data_fetch_logs", "idx_dfl_data_type", "data_type");
        addIndex("data_fetch_logs", "idx_dfl_created_date", "created_at");

        addIndex("market_data", "idx_market_data_trade_date", "trade_date");
        addIndex("market_data", "idx_market_data_symbol_date", "symbol, trade_date DESC");
        addIndex("market_data", "idx_market_data_exchange_date", "exchange, trade_date");

        addIndex("broker_market_candles", "idx_bmc_trade_date", "trade_date");
        addIndex("broker_market_candles", "idx_bmc_symbol_date", "symbol, trade_date DESC");

        addIndex("broker_delivery_volumes", "idx_bdv_trade_date", "trade_date");
        addIndex("broker_delivery_volumes", "idx_bdv_symbol_date", "symbol, trade_date DESC");

        log.info("Data Fetch Engine performance indexes verified.");
    }

    private void addTrackerRuntimeColumns() {
        addColumn("stock_uploads", "user_id INTEGER");
        addColumn("tracked_stocks", "user_id INTEGER");
        addColumn("tracker_rankings", "user_id INTEGER");
        addColumn("tracker_ranking_items", "user_id INTEGER");
        addColumn("tracker_rankings", "trade_avg_return DOUBLE PRECISION");
        addColumn("tracker_rankings", "trade_winners INTEGER");
        addColumn("tracker_rankings", "trade_losers INTEGER");
        addColumn("tracker_rankings", "trade_symbol_count INTEGER");
        addColumn("tracker_rankings", "trade_source TEXT");
        addIndex("tracked_stocks", "idx_tracked_stocks_user_symbol", "user_id, symbol");
        addIndex("tracked_stocks", "idx_tracked_stocks_user_date", "user_id, upload_date DESC");
        addIndex("tracker_rankings", "idx_trankings_user_date", "user_id, run_date DESC");
        addIndex("tracker_ranking_items", "idx_tri_user_date", "user_id, run_date DESC");
        addIndex("stock_uploads", "idx_stock_uploads_user_date", "user_id, created_at DESC");

        addColumn("tracked_stocks", "is_deleted INTEGER DEFAULT 0");
        addColumn("tracked_stocks", "deleted_at TEXT");
        addColumn("tracked_stocks", "deleted_by BIGINT");
        addColumn("tracked_stocks", "delete_type VARCHAR(30)");
        addColumn("tracked_stocks", "batch_id TEXT");
        addIndex("tracked_stocks", "idx_tracked_stocks_batch", "batch_id");
        addIndex("tracked_stocks", "idx_tracked_stocks_deleted", "is_deleted");

        addColumn("stock_uploads", "institution_id BIGINT");
        addColumn("stock_uploads", "batch_id TEXT");
        addColumn("stock_uploads", "source VARCHAR(50) DEFAULT 'CSV Upload'");
        addColumn("stock_uploads", "uploaded_by TEXT");
        addColumn("stock_uploads", "imported_records INTEGER DEFAULT 0");
        addColumn("stock_uploads", "is_deleted INTEGER DEFAULT 0");
        addColumn("stock_uploads", "deleted_at TEXT");
        addColumn("stock_uploads", "deleted_by BIGINT");
        addIndex("stock_uploads", "idx_stock_uploads_batch", "batch_id");
        addIndex("stock_uploads", "idx_stock_uploads_inst", "institution_id");

        execute("CREATE TABLE IF NOT EXISTS tracker_deletion_logs (" +
            "id BIGSERIAL PRIMARY KEY, institution_id BIGINT, user_id BIGINT, " +
            "action VARCHAR(50) NOT NULL, delete_type VARCHAR(30) NOT NULL, " +
            "batch_id TEXT, total_records INTEGER DEFAULT 0, " +
            "filter_criteria TEXT, reason TEXT, ip_address TEXT, " +
            "created_at TEXT)");
        addIndex("tracker_deletion_logs", "idx_tdl_inst", "institution_id");
        addIndex("tracker_deletion_logs", "idx_tdl_user", "user_id, created_at DESC");
    }

    private void addTradeBatchTables() {
        try {
            tx.executeWithoutResult(status -> {
                em.createNativeQuery("""
                    CREATE TABLE IF NOT EXISTS top10_trade_batches (
                        id SERIAL PRIMARY KEY,
                        batch_id TEXT NOT NULL UNIQUE,
                        user_id INTEGER NOT NULL,
                        institution_id INTEGER NOT NULL,
                        trade_date TEXT NOT NULL,
                        buy_time TEXT NOT NULL,
                        sell_time TEXT,
                        status TEXT DEFAULT 'OPEN',
                        strategy TEXT DEFAULT 'TOP10',
                        trade_type TEXT DEFAULT 'PAPER',
                        total_stocks INTEGER DEFAULT 10,
                        winning_stocks INTEGER DEFAULT 0,
                        losing_stocks INTEGER DEFAULT 0,
                        avg_return_pct DOUBLE PRECISION DEFAULT 0,
                        total_return_pct DOUBLE PRECISION DEFAULT 0,
                        notes TEXT,
                        created_at TEXT,
                        updated_at TEXT,
                        closed_at TEXT
                    )""").executeUpdate();
                em.createNativeQuery("""
                    CREATE TABLE IF NOT EXISTS top10_trade_batch_stocks (
                        id SERIAL PRIMARY KEY,
                        batch_id TEXT NOT NULL,
                        symbol TEXT NOT NULL,
                        rank INTEGER,
                        buy_price DOUBLE PRECISION DEFAULT 0,
                        sell_price DOUBLE PRECISION,
                        return_pct DOUBLE PRECISION DEFAULT 0,
                        sector TEXT,
                        market_cap TEXT,
                        status TEXT DEFAULT 'OPEN',
                        created_at TEXT
                    )""").executeUpdate();
            });
        } catch (Exception e) {
            log.warn("Trade batch tables creation skipped: {}", e.getMessage());
        }
        // Automatic daily buy/sell cycle columns (added additively — safe for existing rows)
        addColumn("top10_trade_batches", "auto_generated INTEGER DEFAULT 0");
        addColumn("top10_trade_batch_stocks", "buy_date TEXT");
        addColumn("top10_trade_batch_stocks", "buy_time TEXT");
        addColumn("top10_trade_batch_stocks", "sell_date TEXT");
        addColumn("top10_trade_batch_stocks", "sell_time TEXT");
        addColumn("top10_trade_batch_stocks", "profit_loss DOUBLE PRECISION DEFAULT 0");
        addColumn("top10_trade_batch_stocks", "execution_source TEXT DEFAULT 'SIM'");

        addIndex("top10_trade_batches", "idx_t10tb_inst", "institution_id, trade_date DESC");
        addIndex("top10_trade_batches", "idx_t10tb_user", "user_id, trade_date DESC");
        addIndex("top10_trade_batches", "idx_t10tb_status", "status");
        addIndex("top10_trade_batches", "idx_t10tb_strategy", "strategy, status");
        addIndex("top10_trade_batch_stocks", "idx_t10tbs_batch", "batch_id");
        addIndex("top10_trade_batch_stocks", "idx_t10tbs_symbol", "symbol");

        // Failed-symbol log for the automation cycle (exception isolation / observability)
        execute("CREATE TABLE IF NOT EXISTS top10_failed_symbols (" +
            "id BIGSERIAL PRIMARY KEY, institution_id INTEGER DEFAULT 1, symbol TEXT, " +
            "phase TEXT, reason TEXT, batch_id TEXT, created_at TEXT DEFAULT (CURRENT_TIMESTAMP::text))");
        addIndex("top10_failed_symbols", "idx_t10fs_inst", "institution_id, created_at DESC");
        addIndex("top10_failed_symbols", "idx_t10fs_symbol", "symbol, created_at DESC");

        // Startup catch-up synchronization checkpoint/idempotency state (Phase 1).
        execute("CREATE TABLE IF NOT EXISTS sync_state (" +
            "id BIGSERIAL PRIMARY KEY, data_type TEXT NOT NULL, scope_key TEXT NOT NULL, " +
            "last_synced_date TEXT, last_run_at TEXT, status TEXT, detail TEXT, " +
            "CONSTRAINT ux_sync_state UNIQUE(data_type, scope_key))");
        // Speed up the DAILY-candle gap scan (GROUP BY trade_date over the lookback window).

        // Phase 4: functional index matching the normalized-symbol expression used by
        // per-row price updates / removals, so each write is an index lookup not a scan.
        execute("CREATE INDEX IF NOT EXISTS idx_ts_norm_symbol ON tracked_stocks " +
            "(institution_id, (UPPER(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(TRIM(symbol)," +
            "'.NS',''),'.BO',''),'-EQ',''),'-BE',''),'-BZ',''),'-SM',''),'-ST',''))))");

        // Phase 4b: functional indexes matching the UPPER(symbol) expressions in
        // loadDailyBars(), so per-stock daily-bar lookups during ranking eligibility
        // use an index instead of a full table scan (this was the ~3-minute ranking).
        execute("CREATE INDEX IF NOT EXISTS idx_md_usym_date ON market_data (UPPER(symbol), trade_date DESC)");
        execute("CREATE INDEX IF NOT EXISTS idx_bmc_usym ON broker_market_candles ((UPPER(COALESCE(symbol,'')))) ");
        execute("CREATE INDEX IF NOT EXISTS idx_bmc_utsym ON broker_market_candles ((UPPER(COALESCE(trading_symbol,'')))) ");
        execute("CREATE INDEX IF NOT EXISTS idx_bmc_utsym_eq ON broker_market_candles ((UPPER(REPLACE(COALESCE(trading_symbol,''),'-EQ','')))) ");

        // Dhan PIN, stored encrypted like the other broker secrets so the user does not have to
        // retype it on every token generation. Opt-in from the Broker screen; blank means not saved.
        execute("ALTER TABLE IF EXISTS broker_credentials ADD COLUMN IF NOT EXISTS dhan_pin_enc TEXT");

        // Phase 4c: loadLatestDailyPrices() matches on the SAME normalized expression as
        // idx_ts_norm_symbol above (the 8-way REPLACE chain), not the simpler
        // UPPER(COALESCE(symbol,'')) form indexed by idx_bmc_usym. trade_date DESC is part of
        // the key so the per-symbol LATERAL "latest candle" seeks resolve as a one-row index
        // scan. Without this pair those lookups scanned ~31% of a 27M-row table repeatedly,
        // which was the ~200-second ranking stage.
        execute("CREATE INDEX IF NOT EXISTS idx_md_norm_date ON market_data " +
            "(UPPER(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(TRIM(COALESCE(symbol,'')),'.NS',''),'.BO',''),'-EQ',''),'-BE',''),'-BZ',''),'-SM',''),'-ST',''),' ','')), trade_date DESC)");
        execute("CREATE INDEX IF NOT EXISTS idx_bmc_norm_date ON broker_market_candles " +
            "(UPPER(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(TRIM(COALESCE(symbol,'')),'.NS',''),'.BO',''),'-EQ',''),'-BE',''),'-BZ',''),'-SM',''),'-ST',''),' ','')), trade_date DESC)");

        // Seed Stock Upload automation config defaults (idempotent). Flyway is disabled
        // in this app, so seeding here keeps the settings visible in the Admin config list.
        seedAutomationConfigDefaults();
    }

    /** Seed the STOCK_UPLOAD_AUTOMATION settings if the application_settings table exists. */
    private void seedAutomationConfigDefaults() {
        if (!tableExists("application_settings")) return;
        String[][] defaults = {
            {"top10.auto_refresh.enabled",            "Current Top 10 Auto Refresh",       "true",  "BOOLEAN", "1"},
            {"top10.auto_refresh.interval_sec",       "Auto Refresh Interval (sec)",       "300",   "INTEGER", "2"},
            {"prevtop10.auto_cycle.enabled",          "Previous Top 10 Auto Cycle",        "false", "BOOLEAN", "3"},
            {"prevtop10.auto_buy.enabled",            "Auto Buy Enabled",                  "false", "BOOLEAN", "4"},
            {"prevtop10.auto_sell.enabled",           "Auto Sell Enabled",                 "false", "BOOLEAN", "5"},
            {"prevtop10.buy_time",                    "Auto Buy Time (HH:mm IST)",         "15:20", "STRING",  "6"},
            {"prevtop10.sell_time",                   "Auto Sell Time (HH:mm IST)",        "09:30", "STRING",  "7"},
            {"prevtop10.execution_mode",              "Execution Mode",                    "PAPER", "STRING",  "8"},
            {"prevtop10.use_real_price_if_executed",  "Use Real Executed Price",           "true",  "BOOLEAN", "9"},
            {"market.holidays",                       "Market Holidays (yyyy-MM-dd list)", "",      "STRING",  "10"},
        };
        for (String[] d : defaults) {
            try {
                tx.executeWithoutResult(status -> em.createNativeQuery(
                    "INSERT INTO application_settings(category,setting_key,setting_name,setting_value,data_type," +
                    "default_value,is_editable,is_active,display_order,config_version,created_at) " +
                    "VALUES('STOCK_UPLOAD_AUTOMATION',?,?,?,?,?,1,1,?,1,?) ON CONFLICT (setting_key) DO NOTHING")
                    .setParameter(1, d[0]).setParameter(2, d[1]).setParameter(3, d[2]).setParameter(4, d[3])
                    .setParameter(5, d[2]).setParameter(6, Integer.parseInt(d[4]))
                    .setParameter(7, java.time.LocalDateTime.now().toString())
                    .executeUpdate());
            } catch (Exception e) {
                log.debug("Automation config seed skipped for {}: {}", d[0], e.getMessage());
            }
        }
    }

    private void addColumn(String table, String columnDefinition) {
        if (!tableExists(table)) return;
        // Same reasoning as addIndex below, which was fixed first and then not carried over here:
        // "ALTER TABLE ... ADD COLUMN IF NOT EXISTS" still takes an ACCESS EXCLUSIVE lock to learn
        // it has nothing to do. On a busy table it waits out the whole lock_timeout and fails, so
        // three already-present columns cost ~30s of startup and logged three ERRORs every boot.
        // Reading information_schema takes no lock.
        String columnName = columnDefinition.trim().split("\s+")[0];
        if (columnExists(table, columnName)) return;
        try {
            tx.executeWithoutResult(status ->
                em.createNativeQuery("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS " + columnDefinition).executeUpdate());
        } catch (Exception e) {
            log.warn("Schema guard could not add {}.{}: {}", table, columnDefinition, e.getMessage());
        }
    }

    private void addIndex(String table, String indexName, String columns) {
        if (!tableExists(table)) return;
        // Check the catalogue before issuing DDL. "CREATE INDEX IF NOT EXISTS" still has to take
        // a lock on the table to discover the index is already there, and on a 51-million-row
        // table that lock waits behind any running import - then fails on lock_timeout after ten
        // seconds. Three such statements cost ~30s of startup and logged three alarming warnings
        // for indexes that already existed. A catalogue lookup takes no lock and no measurable time.
        if (indexExists(indexName)) return;
        try {
            tx.executeWithoutResult(status ->
                em.createNativeQuery("CREATE INDEX IF NOT EXISTS " + indexName + " ON " + table + "(" + columns + ")").executeUpdate());
        } catch (Exception e) {
            log.warn("Schema guard could not add index {}: {}", indexName, e.getMessage());
        }
    }

    /** True when the index already exists, checked without taking a lock on its table. */
    private boolean indexExists(String indexName) {
        try {
            Object n = em.createNativeQuery(
                "SELECT COUNT(*) FROM pg_indexes WHERE schemaname='public' AND indexname=?")
                .setParameter(1, indexName).getSingleResult();
            return n != null && ((Number) n).intValue() > 0;
        } catch (Exception e) {
            return false;   // cannot tell - fall through and let the DDL decide
        }
    }

    /** True when the column already exists, checked without taking a lock on its table. */
    private boolean columnExists(String table, String column) {
        try {
            Object n = em.createNativeQuery(
                "SELECT COUNT(*) FROM information_schema.columns " +
                "WHERE table_schema='public' AND table_name=? AND column_name=?")
                .setParameter(1, table).setParameter(2, column).getSingleResult();
            return n != null && ((Number) n).intValue() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void addSymbol(String table) {
        if (!tableExists(table)) return;
        try {
            // Same reasoning as addIndex: ADD COLUMN IF NOT EXISTS still locks the table to find
            // out the column is already there, which on broker_instruments meant a 10s stall.
            if (columnExists(table, "symbol")) return;
            tx.executeWithoutResult(status ->
                em.createNativeQuery("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS symbol TEXT").executeUpdate());
            // Only add the lookup index when nothing already leads with symbol. This used to
            // create idx_<table>_symbol unconditionally, duplicating indexes the migrations
            // had already made - on broker_market_candles that was a second full copy of
            // idx_bmc_symbol_date's leading column, paid for on every candle insert.
            if (!hasLeadingIndexOn(table, "symbol")) {
                addIndex(table, "idx_" + table + "_symbol", "symbol");
            }
        } catch (Exception e) {
            log.warn("Symbol schema guard skipped {}: {}", table, e.getMessage());
        }
    }

    /** True when some index on the table already has {@code column} as its first key. */
    private boolean hasLeadingIndexOn(String table, String column) {
        try {
            Object hit = em.createNativeQuery(
                "SELECT 1 FROM pg_index x " +
                "JOIN pg_class c ON c.oid = x.indrelid " +
                "JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = current_schema() " +
                "JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = x.indkey[0] " +
                "WHERE c.relname = ? AND a.attname = ? LIMIT 1")
                .setParameter(1, table)
                .setParameter(2, column)
                .getResultList().stream().findFirst().orElse(null);
            return hit != null;
        } catch (Exception e) {
            // If the probe fails, fall back to the old behaviour rather than skipping an
            // index the app may need.
            return false;
        }
    }

    private boolean tableExists(String table) {
        try {
            Object count = em.createNativeQuery(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=current_schema() AND table_name=?")
                .setParameter(1, table)
                .getSingleResult();
            return count instanceof Number n && n.longValue() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Repairs symbols that predate the column, ONCE, then never again.
     *
     * <p>These are one-time migrations that were running on every boot. Two of them are UPDATE
     * JOINs over broker_market_candles - 51 million rows - so with nothing left to repair they
     * still scanned the table twice. Measured: 193 of a 203-second startup. Spring was ready at
     * 10 seconds and the application sat unusable for another three minutes.
     *
     * <p>Guarding with "does any row still need this?" does not work either: EXISTS is only cheap
     * when it finds something, and proving the absence costs the same full scan - that probe timed
     * at 93 seconds by itself. The only cheap answer is to remember that the work was done.
     *
     * <p>The marker records the schema version the backfill belongs to. Raise BACKFILL_VERSION if a
     * future change genuinely needs the data rewritten, and it will run once more.
     */
    private static final String BACKFILL_VERSION = "v1";

    private void backfillSymbols() {
        if (symbolBackfillAlreadyDone()) {
            log.info("[SchemaGuard] Symbol backfill {} already applied - skipping "
                   + "(this is what used to cost ~3 minutes of every startup).", BACKFILL_VERSION);
            return;
        }
        long t0 = System.currentTimeMillis();
        execute("UPDATE broker_instruments SET symbol=UPPER(TRIM(underlying_symbol)) WHERE broker_name='dhan' AND COALESCE(underlying_symbol,'')<>'' AND (COALESCE(symbol,'')='' OR UPPER(TRIM(symbol))<>UPPER(TRIM(underlying_symbol)))");
        execute("UPDATE broker_market_candles b SET symbol=UPPER(TRIM(bi.underlying_symbol)) FROM broker_instruments bi WHERE b.broker_name='dhan' AND bi.broker_name='dhan' AND b.broker_security_id=bi.broker_security_id AND COALESCE(b.exchange_segment,'')=COALESCE(bi.exchange_segment,'') AND COALESCE(bi.underlying_symbol,'')<>'' AND (COALESCE(b.symbol,'')='' OR UPPER(TRIM(b.symbol))<>UPPER(TRIM(bi.underlying_symbol)))");
        execute("UPDATE broker_market_candles b SET symbol=UPPER(TRIM(di.underlying_symbol)) FROM dhan_instruments di WHERE b.broker_name='dhan' AND b.broker_security_id=di.security_id AND COALESCE(di.underlying_symbol,'')<>'' AND (COALESCE(b.exchange_segment,'')='' OR COALESCE(di.exchange_segment,'')='' OR UPPER(b.exchange_segment)=UPPER(di.exchange_segment)) AND (COALESCE(b.symbol,'')='' OR UPPER(TRIM(b.symbol))<>UPPER(TRIM(di.underlying_symbol)))");
        execute("UPDATE broker_api_fetch_jobs j SET symbol=UPPER(TRIM(di.underlying_symbol)) FROM dhan_instruments di WHERE j.broker_name='dhan' AND j.broker_security_id=di.security_id AND COALESCE(di.underlying_symbol,'')<>'' AND (COALESCE(j.symbol,'')='' OR UPPER(TRIM(j.symbol))<>UPPER(TRIM(di.underlying_symbol)))");
        execute("UPDATE event_calendar SET symbol=UPPER(TRIM(stock_symbol)) WHERE COALESCE(symbol,'')='' AND COALESCE(stock_symbol,'')<>''");
        execute("UPDATE broker_api_fetch_jobs SET symbol=UPPER(TRIM(instrument_symbol)) WHERE COALESCE(symbol,'')='' AND COALESCE(instrument_symbol,'')<>''");
        execute("UPDATE broker_option_chain_snapshots SET symbol=UPPER(TRIM(underlying)) WHERE COALESCE(symbol,'')='' AND COALESCE(underlying,'')<>''");
        execute("UPDATE option_chain SET symbol=UPPER(TRIM(underlying)) WHERE COALESCE(symbol,'')='' AND COALESCE(underlying,'')<>''");
        markSymbolBackfillDone();
        log.info("[SchemaGuard] Symbol backfill {} completed in {} ms - it will not run again.",
            BACKFILL_VERSION, System.currentTimeMillis() - t0);
    }

    /** True when this version of the backfill has already been recorded as applied. */
    private boolean symbolBackfillAlreadyDone() {
        try {
            Object v = em.createNativeQuery(
                "SELECT setting_value FROM application_settings WHERE setting_key = ?")
                .setParameter(1, "schema.symbol_backfill.applied")
                .setMaxResults(1).getResultList().stream().findFirst().orElse(null);
            return v != null && BACKFILL_VERSION.equals(String.valueOf(v));
        } catch (Exception e) {
            // No marker table yet means a fresh install, where the backfill should run.
            return false;
        }
    }

    private void markSymbolBackfillDone() {
        try {
            tx.executeWithoutResult(status -> em.createNativeQuery(
                "INSERT INTO application_settings (category, setting_key, setting_name, "
              + "setting_value, data_type, default_value) "
              + "VALUES ('SYSTEM', ?, 'Symbol backfill applied', ?, 'STRING', ?) "
              + "ON CONFLICT (setting_key) DO UPDATE SET setting_value = EXCLUDED.setting_value")
                .setParameter(1, "schema.symbol_backfill.applied")
                .setParameter(2, BACKFILL_VERSION)
                .setParameter(3, BACKFILL_VERSION)
                .executeUpdate());
        } catch (Exception e) {
            log.warn("[SchemaGuard] Could not record the symbol backfill marker, so it will run "
                   + "again next start: {}", e.getMessage());
        }
    }

    private void execute(String sql) {
        try {
            tx.executeWithoutResult(status -> em.createNativeQuery(sql).executeUpdate());
        } catch (Exception e) {
            log.debug("Symbol backfill skipped: {}", e.getMessage());
        }
    }
}
