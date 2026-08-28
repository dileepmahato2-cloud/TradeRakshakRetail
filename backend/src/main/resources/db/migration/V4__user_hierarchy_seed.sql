-- =============================================================================
-- V4: Seed all user types, permissions, commission slabs
-- =============================================================================

-- ── User Type definitions ─────────────────────────────────────────────────────
INSERT OR IGNORE INTO user_types(type_code, display_name, hierarchy_level, can_create_types, description) VALUES
('super_admin',       'Super Admin',          1,  '["platform_admin","admin","institution_admin","compliance_officer","research_analyst","trainer","sales_manager","support_executive"]', 'Complete platform control'),
('platform_admin',    'Platform Admin',       2,  '["admin","institution_admin","support_executive","sales_manager"]', 'Platform operations'),
('admin',             'Admin',                3,  '["institution_admin","support_executive"]', 'Day-to-day management'),
('institution_admin', 'Institution Admin',    4,  '["level_1","sales_manager","support_executive"]', 'Institution-level control'),
('compliance_officer','Compliance Officer',   3,  '[]', 'Audit and compliance monitoring'),
('research_analyst',  'Research Analyst',     3,  '[]', 'Market research and recommendations'),
('trainer',           'Trainer',              3,  '[]', 'Training and learning content'),
('sales_manager',     'Sales Manager',        4,  '["agent_l1","agent_l2","agent_l3"]', 'Agent and sales management'),
('support_executive', 'Support Executive',    4,  '[]', 'Ticket handling and user support'),
('level_1',           'Level 1 (Regional Head)', 5, '["level_2","agent_l1"]', 'Regional/Business Head'),
('level_2',           'Level 2 (Team Manager)',  6, '["level_3","agent_l1"]', 'Team Manager'),
('level_3',           'Level 3 (RM/Executive)',  7, '["agent_l1"]', 'Relationship Manager'),
('agent_l1',          'Agent Level 1',        8,  '[]', 'Direct selling agent'),
('agent_l2',          'Agent Level 2 (Master)', 8, '["agent_l1"]', 'Master agent'),
('agent_l3',          'Agent Level 3 (Distributor)', 7, '["agent_l2","agent_l1"]', 'National/Regional distributor'),
('paid_subscriber',   'Paid Subscriber',      9,  '[]', 'Individual paid customer'),
('free_trial',        'Free Trial User',      10, '[]', 'Limited-time free access');

-- ── Subscription Plans (extended with all 6 tiers) ───────────────────────────
INSERT OR IGNORE INTO plans(institution_id,name,price,gst,validity_days,description,features,
  allowed_segments,fo_access,fundamental_access,competition_access,ppt_access,
  max_backtests_day,max_paper_portfolios,max_strategies,live_trading_allowed,ai_access,status) VALUES
(1,'Basic',199,18,30,'Retail investors — screeners, portfolio, watchlist, basic reports',
 '["Screeners","Portfolio","Watchlist","Basic Reports","Price Alerts"]',
 'EQ-INT,EQ-SWG',0,0,0,0,10,1,5,0,0,'active'),
(1,'Premium',799,18,90,'Advanced screeners, backtesting, AI recommendations, alerts, strategy builder',
 '["Advanced Screeners","Backtesting","AI Recommendations","Alerts","Strategy Builder","Sector AI"]',
 'EQ-INT,EQ-SWG,EQ-POS,FUT',1,1,1,0,100,2,15,0,1,'active'),
(1,'Institutional',4999,18,365,'Institute branding, multiple users, user management, dedicated reports',
 '["All Pro Features","Multi-User","User Management","Institute Branding","Dedicated Reports","API Access"]',
 'ALL',1,1,1,1,9999,10,999,1,1,'active'),
(1,'Enterprise',19999,18,365,'White-label, own branding, own URL, own database, own subscription rules',
 '["White-Label","Own Branding","Own URL","Own Database","API Access","Custom Rules","Franchise Module"]',
 'ALL',1,1,1,1,9999,99,9999,1,1,'active');

-- ── Commission slabs ──────────────────────────────────────────────────────────
INSERT OR IGNORE INTO commission_slabs(agent_level, plan_name, commission_pct, effective_from, status) VALUES
(1,'Free Trial',     0,   date('now'), 'active'),
(1,'Basic',          8,   date('now'), 'active'),
(1,'Starter',        10,  date('now'), 'active'),
(1,'Premium',        10,  date('now'), 'active'),
(1,'Pro',            12,  date('now'), 'active'),
(1,'Elite',          12,  date('now'), 'active'),
(1,'Institutional',  15,  date('now'), 'active'),
(1,'Enterprise',     15,  date('now'), 'active'),
(2,'Basic',          5,   date('now'), 'active'),
(2,'Starter',        6,   date('now'), 'active'),
(2,'Premium',        6,   date('now'), 'active'),
(2,'Pro',            7,   date('now'), 'active'),
(2,'Elite',          7,   date('now'), 'active'),
(2,'Institutional',  8,   date('now'), 'active'),
(2,'Enterprise',     8,   date('now'), 'active'),
(3,'Basic',          3,   date('now'), 'active'),
(3,'Starter',        3,   date('now'), 'active'),
(3,'Premium',        3,   date('now'), 'active'),
(3,'Pro',            4,   date('now'), 'active'),
(3,'Elite',          4,   date('now'), 'active'),
(3,'Institutional',  5,   date('now'), 'active'),
(3,'Enterprise',     5,   date('now'), 'active');

-- ── Default permissions matrix ────────────────────────────────────────────────
-- Modules: dashboard, users, institutions, market_data, strategies, journal,
--          paper_trades, sector_ai, ml_engine, reports, billing, agents,
--          competitions, sop, events, data_import, admin_health, permissions

INSERT OR IGNORE INTO permissions(user_type, module, can_view, can_create, can_edit, can_delete, can_approve, can_export) VALUES
-- super_admin — full access
('super_admin','dashboard',1,1,1,1,1,1),
('super_admin','users',1,1,1,1,1,1),
('super_admin','institutions',1,1,1,1,1,1),
('super_admin','market_data',1,1,1,1,1,1),
('super_admin','strategies',1,1,1,1,1,1),
('super_admin','journal',1,1,1,1,1,1),
('super_admin','billing',1,1,1,1,1,1),
('super_admin','agents',1,1,1,1,1,1),
('super_admin','ml_engine',1,1,1,1,1,1),
('super_admin','reports',1,1,1,1,1,1),
('super_admin','permissions',1,1,1,1,1,1),
('super_admin','api_keys',1,1,1,1,1,1),
-- platform_admin
('platform_admin','dashboard',1,1,1,0,1,1),
('platform_admin','users',1,1,1,1,1,1),
('platform_admin','institutions',1,1,1,0,1,1),
('platform_admin','market_data',1,1,1,0,1,1),
('platform_admin','billing',1,1,1,0,1,1),
('platform_admin','agents',1,1,1,0,1,1),
('platform_admin','reports',1,0,0,0,0,1),
('platform_admin','ml_engine',1,0,1,0,0,0),
('platform_admin','permissions',1,0,1,0,0,1),
-- admin
('admin','dashboard',1,0,0,0,1,1),
('admin','users',1,1,1,0,1,1),
('admin','market_data',1,1,1,0,0,1),
('admin','reports',1,0,0,0,0,1),
('admin','billing',1,0,0,0,1,1),
('admin','agents',1,1,1,0,1,1),
-- institution_admin
('institution_admin','dashboard',1,0,0,0,1,1),
('institution_admin','users',1,1,1,0,1,1),
('institution_admin','reports',1,0,0,0,0,1),
('institution_admin','billing',1,0,0,0,0,1),
-- compliance_officer
('compliance_officer','dashboard',1,0,0,0,0,1),
('compliance_officer','users',1,0,0,0,0,1),
('compliance_officer','reports',1,0,0,0,0,1),
('compliance_officer','activity_logs',1,0,0,0,0,1),
-- research_analyst
('research_analyst','dashboard',1,0,0,0,0,1),
('research_analyst','market_data',1,1,1,0,0,1),
('research_analyst','sector_ai',1,1,1,0,0,1),
('research_analyst','reports',1,1,1,0,0,1),
-- trainer
('trainer','dashboard',1,0,0,0,0,0),
('trainer','training',1,1,1,0,0,1),
-- sales_manager
('sales_manager','dashboard',1,0,0,0,0,1),
('sales_manager','agents',1,1,1,0,1,1),
('sales_manager','billing',1,0,0,0,1,1),
('sales_manager','reports',1,0,0,0,0,1),
-- support_executive
('support_executive','dashboard',1,0,0,0,0,0),
('support_executive','support_tickets',1,1,1,0,1,1),
('support_executive','users',1,0,0,0,0,0),
-- level_1
('level_1','dashboard',1,0,0,0,0,1),
('level_1','users',1,1,1,0,1,1),
('level_1','reports',1,0,0,0,0,1),
('level_1','strategies',1,1,1,0,0,1),
('level_1','journal',1,1,1,1,0,1),
-- level_2
('level_2','dashboard',1,0,0,0,0,1),
('level_2','users',1,1,1,0,0,1),
('level_2','strategies',1,1,1,0,0,1),
('level_2','journal',1,1,1,1,0,1),
-- level_3
('level_3','dashboard',1,0,0,0,0,1),
('level_3','strategies',1,1,1,0,0,1),
('level_3','journal',1,1,1,1,0,1),
-- agent_l1/l2/l3
('agent_l1','dashboard',1,0,0,0,0,1),
('agent_l2','dashboard',1,0,0,0,0,1),
('agent_l2','agents',1,1,0,0,0,1),
('agent_l3','dashboard',1,0,0,0,0,1),
('agent_l3','agents',1,1,1,0,0,1),
-- paid_subscriber
('paid_subscriber','dashboard',1,0,0,0,0,1),
('paid_subscriber','strategies',1,1,1,1,0,1),
('paid_subscriber','journal',1,1,1,1,0,1),
('paid_subscriber','screener',1,1,0,0,0,0),
-- free_trial
('free_trial','dashboard',1,0,0,0,0,0),
('free_trial','strategies',1,1,0,0,0,0),
('free_trial','journal',1,1,1,0,0,0);

-- ── Seed demo users for all hierarchy levels ──────────────────────────────────
INSERT OR IGNORE INTO users(tr_user_id,institution_id,name,email,password,role,level,
  user_type,plan,segments,status,mobile,created_at,updated_at) VALUES
('TR-2026-000003',1,'Compliance Officer','compliance@demo.local','$PLACEHOLDER_BCRYPT$',
 'compliance_officer','compliance_officer','compliance_officer','Elite','ALL','active','9999999993',datetime('now'),datetime('now')),
('TR-2026-000004',1,'Research Analyst','analyst@demo.local','$PLACEHOLDER_BCRYPT$',
 'research_analyst','research_analyst','research_analyst','Elite','ALL','active','9999999994',datetime('now'),datetime('now')),
('TR-2026-000005',1,'Support Executive','support@demo.local','$PLACEHOLDER_BCRYPT$',
 'support_executive','support_executive','support_executive','Starter','EQ-INT,EQ-SWG','active','9999999995',datetime('now'),datetime('now')),
('TR-2026-000006',1,'Trainer User','trainer@demo.local','$PLACEHOLDER_BCRYPT$',
 'trainer','trainer','trainer','Pro','EQ-INT,EQ-SWG','active','9999999996',datetime('now'),datetime('now')),
('TR-2026-000007',1,'Sales Manager','salesmanager@demo.local','$PLACEHOLDER_BCRYPT$',
 'sales_manager','sales_manager','sales_manager','Pro','EQ-INT,EQ-SWG','active','9999999997',datetime('now'),datetime('now')),
('TR-2026-000008',1,'Agent L2 Demo','agentl2@demo.local','$PLACEHOLDER_BCRYPT$',
 'agent','agent_l2','agent_l2','Starter','EQ-INT,EQ-SWG','active','9999999998',datetime('now'),datetime('now')),
('TR-2026-000009',1,'Paid Subscriber','subscriber@demo.local','$PLACEHOLDER_BCRYPT$',
 'user','level_2','paid_subscriber','Pro','EQ-INT,EQ-SWG','active','9999999999',datetime('now'),datetime('now'));
