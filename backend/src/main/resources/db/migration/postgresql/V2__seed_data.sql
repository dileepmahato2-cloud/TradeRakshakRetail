-- =============================================================================
-- TradeRakshakâ„¢ v11.4 â€” Flyway V2 â€” Seed Data
-- Replaces Python _seed() + _seed_feature_flags() + _seed_checklist() calls
-- =============================================================================

-- â”€â”€ Default institution â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
INSERT INTO institutions(id, name, short_code, tagline, status, created_at, updated_at)
VALUES(1, 'TradeRakshakâ„¢', 'TR',
       'Protecting Capital. Powering Decisions. Creating Wealth.',
       'active', CURRENT_TIMESTAMP::text, CURRENT_TIMESTAMP::text) ON CONFLICT DO NOTHING;

-- â”€â”€ Subscription plans â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
INSERT INTO plans(institution_id,name,price,gst,validity_days,
  allowed_segments,fo_access,fundamental_access,competition_access,ppt_access,
  max_backtests_day,max_paper_portfolios,max_strategies,live_trading_allowed,ai_access,status)
VALUES
(1,'Free Trial',0,18,7,'EQ-INT,EQ-SWG',0,0,0,0,3,1,3,0,0,'active'),
(1,'Starter',499,18,30,'EQ-INT,EQ-SWG,EQ-POS',0,0,1,0,50,1,10,0,0,'active'),
(1,'Pro',999,18,90,'EQ-INT,EQ-SWG,EQ-POS,FUT,OPT-CE,OPT-PE,IDX',1,1,1,1,200,3,25,0,1,'active'),
(1,'Elite',9999,18,365,'ALL',1,1,1,1,9999,5,999,0,1,'active') ON CONFLICT DO NOTHING;

-- â”€â”€ System settings â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
INSERT INTO system_settings(key,value) VALUES
('app_name',          'TradeRakshakâ„¢'),
('app_tagline',       'Protecting Capital. Powering Decisions. Creating Wealth.'),
('currency',          'INR'),
('currency_symbol',   'â‚¹'),
('market_breadth_index','NIFTY 500'),
('live_trading_enabled','0'),
('auto_ml_retrain',   '1'),
('backup_schedule',   'daily'),
('data_retention_years','5'),
('session_idle_minutes','15'),
('default_broker',    'Dhan'),
('payment_gateway',   'razorpay'),
('sebi_disclaimer',   'âš  DISCLAIMER: TradeRakshakâ„¢ is a Technology Tools Platform. It is NOT a SEBI-registered Investment Advisor or Research Analyst. All content is for educational purposes only. Past performance does not guarantee future results. Consult a SEBI-empanelled advisor before trading.'),
('telegram_bot_token',''),
('telegram_chat_id',  ''),
('alert_telegram',    '0'),
('smtp_host',         ''),
('smtp_port',         '587'),
('smtp_user',         ''),
('smtp_password',     ''),
('smtp_from_email',   ''),
('admin_alert_email', ''),
('alert_unusual_email','0'),
('local_data_path',   'E:/Share Market/Stock for Server/Stock Market Application') ON CONFLICT DO NOTHING;

-- â”€â”€ Pre-trade checklist items (BRD Â§5) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
INSERT INTO checklist_items
  (user_id,institution_id,item_text,category,is_mandatory,display_order,status,created_at)
VALUES
(0,1,'NIFTY50 direction checked today','Market',1,1,'active',CURRENT_TIMESTAMP::text),
(0,1,'Gift Nifty pre-market signal checked','Market',1,2,'active',CURRENT_TIMESTAMP::text),
(0,1,'FII/DII data checked from previous day','Market',1,3,'active',CURRENT_TIMESTAMP::text),
(0,1,'No major economic event today (RBI/Budget/CPI)','Risk',1,4,'active',CURRENT_TIMESTAMP::text),
(0,1,'Target sector is positive and in uptrend','Sector',1,5,'active',CURRENT_TIMESTAMP::text),
(0,1,'Sector trend score â‰¥ 55 (AI check)','Sector AI',1,6,'active',CURRENT_TIMESTAMP::text),
(0,1,'All required filter steps passed for this stock','Technical',1,7,'active',CURRENT_TIMESTAMP::text),
(0,1,'Stop loss is pre-defined and noted in entry','Risk Management',1,8,'active',CURRENT_TIMESTAMP::text),
(0,1,'Position size within 2% portfolio risk limit','Risk Management',1,9,'active',CURRENT_TIMESTAMP::text),
(0,1,'Risk:Reward ratio is at least 1:2','Risk Management',1,10,'active',CURRENT_TIMESTAMP::text),
(0,1,'NOT chasing an overextended stock','Discipline',1,11,'active',CURRENT_TIMESTAMP::text),
(0,1,'Trading on logic, not fear or greed','Psychology',1,12,'active',CURRENT_TIMESTAMP::text),
(0,1,'SEBI disclaimer acknowledged â€” not investment advice','Compliance',1,13,'active',CURRENT_TIMESTAMP::text) ON CONFLICT DO NOTHING;

-- â”€â”€ Feature flags â€” disabled (R2: disable not delete) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
INSERT INTO feature_flags
  (feature_name,enabled,disabled_reason,disabled_at,brd_reference)
VALUES
('nse_futures_sell_7step',0,'Replaced by unified Strategy Builder (BRD v9.0)',CURRENT_TIMESTAMP::text,'BRD v8 Â§8'),
('nse_options_buy_6step',0,'Replaced by unified F&O Strategy Builder (BRD v9.0)',CURRENT_TIMESTAMP::text,'BRD v8 Â§9'),
('stock_buy_8step',0,'Replaced by unified Strategy Builder with all conditions (BRD v9.0)',CURRENT_TIMESTAMP::text,'BRD v8 Â§10') ON CONFLICT DO NOTHING;

-- â”€â”€ Default SOPs (BRD Â§19) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
INSERT INTO sops
  (institution_id,title,category,content,is_mandatory,checklist_mode,version,status,created_by,created_at,updated_at)
VALUES
(1,'Daily Market Open Routine','Market Routine',
 '1. Check NSE/BSE market status.
2. Review Gift Nifty pre-market signal.
3. Check USD/INR and Crude Oil.
4. Review FII/DII from previous day.
5. Check Observation Gate score.
6. Identify active sectors from AI dashboard.
7. Run screener if Gate is GREEN.
8. Review Trade Score > 70 candidates.',
 1,0,'1.0','active',1,CURRENT_TIMESTAMP::text,CURRENT_TIMESTAMP::text),

(1,'Trade Entry SOP (Equity BUY)','Trade Execution',
 '1. Confirm all filter conditions pass.
2. Calculate position size (2% portfolio risk rule).
3. Set stop loss price.
4. Set target price.
5. Verify R:R â‰¥ 2.
6. Enter via trade window (15:10-15:20).
7. Complete journal entry remark.',
 1,0,'1.0','active',1,CURRENT_TIMESTAMP::text,CURRENT_TIMESTAMP::text),

(1,'Trade Exit SOP','Trade Execution',
 '1. Check if trailing SL is active.
2. Check if target hit.
3. If manual exit: confirm reason in journal closing remark.
4. Log exit in Trade Journal.
5. Update portfolio.',
 0,0,'1.0','active',1,CURRENT_TIMESTAMP::text,CURRENT_TIMESTAMP::text),

(1,'Weekly Review SOP','Review',
 '1. Review all closed trades.
2. Calculate win rate for the week.
3. Identify sector performance.
4. Review journal entries for patterns.
5. Adjust strategy if win rate < 50% for 3 consecutive weeks.',
 0,0,'1.0','active',1,CURRENT_TIMESTAMP::text,CURRENT_TIMESTAMP::text),

(1,'Emergency Capital Protection SOP','Risk Management',
 '1. If gate score < 25 for 3 consecutive days: exit all open positions.
2. Move to 100% cash.
3. Do not enter new trades until gate turns GREEN for 2 consecutive days.',
 1,0,'1.0','active',1,CURRENT_TIMESTAMP::text,CURRENT_TIMESTAMP::text) ON CONFLICT DO NOTHING;

-- â”€â”€ Default ML Model â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
INSERT INTO ml_models(institution_id,model_name,model_type,target,scope,
  accuracy,precision_val,recall,f1_score,win_rate,profit_factor,max_drawdown,
  buy_logic,sell_logic,hold_logic,model_version,status,auto_retrain,
  retrain_frequency,last_trained,next_train,notes,created_at,updated_at)
VALUES(1,'Sector Trend Classifier v1','Rule-Based + Statistical','Sector Signal','sector',
  72.4,68.1,74.2,71.0,64.5,1.82,18.3,
  'RSI(14W)>=45 â†’ Uptrend confirmed. RSI Slope>0 â†’ Momentum building. Price above EMA20 â†’ Short-term trend. Volume>=1.2x â†’ Institutional participation.',
  'RSI(14W)<40 â†’ Downtrend. RSI Slope<-2 â†’ Fading. Price below EMA20+EMA50 â†’ Trend breakdown.',
  'RSI 40-55 â†’ Neutral. Mixed EMA signals â†’ Wait for confirmation.',
  'v1.0','active',1,'weekly',CURRENT_DATE,(CURRENT_DATE + INTERVAL '7 days'),
  'Default sector trend model â€” RSI+EMA+Volume',CURRENT_TIMESTAMP::text,CURRENT_TIMESTAMP::text) ON CONFLICT DO NOTHING;

-- â”€â”€ Admin users (IMPORTANT: passwords MUST be re-hashed with BCrypt on first startup) â”€
-- NOTE: The application DataInitializer bean overwrites these with BCrypt hashes.
-- These are placeholder rows only â€” plaintext passwords never stored in prod.
INSERT INTO users(tr_user_id,institution_id,name,email,password,
  role,level,plan,segments,status,mobile,otp_enabled,created_at)
VALUES
('TR-2026-000001',1,'Super Admin','superadmin@traderakshak.local',
 '$PLACEHOLDER_BCRYPT$','super_admin','super_admin','Elite','ALL','active','9999999999',0,CURRENT_TIMESTAMP::text),
('TR-2026-000002',1,'Platform Admin','admin@algo.local',
 '$PLACEHOLDER_BCRYPT$','platform_admin','platform_admin','Elite','ALL','active','9999999998',0,CURRENT_TIMESTAMP::text) ON CONFLICT DO NOTHING;

