-- =============================================================================
-- TradeRakshakâ„¢ v11.4 â€” V6: Teaching Module Seed Data
-- Demo institution "Sahi Traders Academy" + sample courses, batch, quizzes
-- =============================================================================

-- â”€â”€ Demo Teaching Institution â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
INSERT INTO institutions(id, name, short_code, tagline,
  inst_type, teaching_enabled, max_courses, max_students,
  allow_live_class, allow_cert_issue, city, state,
  subscription_plan, status, created_at, updated_at)
VALUES(2, 'Sahi Traders Academy', 'STA',
  'Learn. Trade. Profit.',
  'trading_academy', 1, 20, 200,
  1, 1, 'Mumbai', 'Maharashtra',
  'Institutional', 'active', CURRENT_TIMESTAMP::text, CURRENT_TIMESTAMP::text) ON CONFLICT DO NOTHING;

-- â”€â”€ Academy Admin user (institution_id=2) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
INSERT INTO users(tr_user_id, institution_id, name, email, password,
  role, user_type, level, plan, segments, status, mobile, created_at, updated_at)
VALUES('TR-2026-000010', 2, 'Academy Admin', 'academy@sta.demo',
  '$PLACEHOLDER_BCRYPT$',
  'institution_admin','institution_admin','level_1',
  'Institutional','EQ-INT,EQ-SWG,EQ-POS','active','9876543210',
  CURRENT_TIMESTAMP::text, CURRENT_TIMESTAMP::text) ON CONFLICT DO NOTHING;

-- â”€â”€ Academy Trainer â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
INSERT INTO users(tr_user_id, institution_id, name, email, password,
  role, user_type, level, plan, segments, status, mobile, created_at, updated_at)
VALUES('TR-2026-000011', 2, 'Rahul Sharma (Trainer)', 'trainer@sta.demo',
  '$PLACEHOLDER_BCRYPT$',
  'trainer','trainer','trainer',
  'Pro','EQ-INT,EQ-SWG,EQ-POS,FUT','active','9876543211',
  CURRENT_TIMESTAMP::text, CURRENT_TIMESTAMP::text) ON CONFLICT DO NOTHING;

-- â”€â”€ 3 Demo Students â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
INSERT INTO users(tr_user_id,institution_id,name,email,password,
  role,user_type,level,plan,segments,status,mobile,created_at,updated_at)
VALUES
('TR-2026-000012',2,'Priya Patel','student1@sta.demo','$PLACEHOLDER_BCRYPT$',
 'paid_subscriber','paid_subscriber','level_2','Starter','EQ-INT,EQ-SWG','active','9876543212',CURRENT_TIMESTAMP::text,CURRENT_TIMESTAMP::text),
('TR-2026-000013',2,'Amit Kumar','student2@sta.demo','$PLACEHOLDER_BCRYPT$',
 'paid_subscriber','paid_subscriber','level_2','Starter','EQ-INT,EQ-SWG','active','9876543213',CURRENT_TIMESTAMP::text,CURRENT_TIMESTAMP::text),
('TR-2026-000014',2,'Sneha Gupta','student3@sta.demo','$PLACEHOLDER_BCRYPT$',
 'paid_subscriber','paid_subscriber','level_2','Pro','EQ-INT,EQ-SWG,EQ-POS','active','9876543214',CURRENT_TIMESTAMP::text,CURRENT_TIMESTAMP::text) ON CONFLICT DO NOTHING;

-- â”€â”€ Sample Course 1: Stock Market Basics â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
INSERT INTO courses(institution_id, created_by, title, slug, description,
  short_description, category, level, duration_hours, course_type,
  required_plan, certificate_enabled, status, published_at, created_at, updated_at)
VALUES(2, (SELECT id FROM users WHERE email='trainer@sta.demo' LIMIT 1),
  'Stock Market Basics for Beginners',
  'stock-market-basics',
  'A complete beginner''s guide to the Indian stock market. Covers NSE/BSE, how equity works, reading charts, understanding delivery vs intraday, and building your first watchlist.',
  'Master stock market fundamentals in 12 hours.',
  'equity_basics', 'Beginner', 12.0, 'self_paced',
  'Starter', 1, 'published', CURRENT_TIMESTAMP::text, CURRENT_TIMESTAMP::text, CURRENT_TIMESTAMP::text) ON CONFLICT DO NOTHING;

-- â”€â”€ Sample Course 2: Technical Analysis â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
INSERT INTO courses(institution_id, created_by, title, slug, description,
  short_description, category, level, duration_hours, course_type,
  required_plan, certificate_enabled, status, published_at, created_at, updated_at)
VALUES(2, (SELECT id FROM users WHERE email='trainer@sta.demo' LIMIT 1),
  'Technical Analysis Masterclass',
  'technical-analysis-masterclass',
  'Deep dive into candlesticks, chart patterns, RSI, MACD, EMA, Bollinger Bands, volume analysis, and building a complete trading system using TradeRakshakâ„¢ tools.',
  'From charts to consistent profits with TA.',
  'technical_analysis', 'Intermediate', 20.0, 'batch',
  'Pro', 1, 'published', CURRENT_TIMESTAMP::text, CURRENT_TIMESTAMP::text, CURRENT_TIMESTAMP::text) ON CONFLICT DO NOTHING;

-- â”€â”€ Course 1 Topics â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
INSERT INTO course_topics(course_id, institution_id, title, display_order, status, created_at)
VALUES
((SELECT id FROM courses WHERE slug='stock-market-basics'), 2, 'Introduction to Indian Markets', 1, 'active', CURRENT_TIMESTAMP::text),
((SELECT id FROM courses WHERE slug='stock-market-basics'), 2, 'How Stock Exchanges Work (NSE & BSE)', 2, 'active', CURRENT_TIMESTAMP::text),
((SELECT id FROM courses WHERE slug='stock-market-basics'), 2, 'Reading Stock Data & Charts', 3, 'active', CURRENT_TIMESTAMP::text),
((SELECT id FROM courses WHERE slug='stock-market-basics'), 2, 'Delivery vs Intraday vs F&O', 4, 'active', CURRENT_TIMESTAMP::text),
((SELECT id FROM courses WHERE slug='stock-market-basics'), 2, 'Building Your First Watchlist', 5, 'active', CURRENT_TIMESTAMP::text) ON CONFLICT DO NOTHING;

-- â”€â”€ Sample Batch â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
INSERT INTO batches(institution_id, course_id, trainer_id, batch_code,
  name, start_date, end_date, mode, max_seats, timezone, status, fee, gst_pct,
  schedule_json, created_at, updated_at)
VALUES(2,
  (SELECT id FROM courses WHERE slug='technical-analysis-masterclass'),
  (SELECT id FROM users WHERE email='trainer@sta.demo' LIMIT 1),
  'STA-TA-2026-01',
  'Technical Analysis Batch â€” June 2026',
  CURRENT_DATE, (CURRENT_DATE + INTERVAL '60 days'),
  'online', 30, 'Asia/Kolkata', 'ongoing', 2999, 18,
  '[{"day":"Mon","time":"19:00","duration_min":90},{"day":"Wed","time":"19:00","duration_min":90},{"day":"Sat","time":"10:00","duration_min":120}]',
  CURRENT_TIMESTAMP::text, CURRENT_TIMESTAMP::text) ON CONFLICT DO NOTHING;

-- â”€â”€ Sample Live Class â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
INSERT INTO live_classes(institution_id, batch_id, course_id, trainer_id,
  title, description, scheduled_at, duration_minutes, platform, status, created_at)
VALUES(2,
  (SELECT id FROM batches WHERE batch_code='STA-TA-2026-01'),
  (SELECT id FROM courses WHERE slug='technical-analysis-masterclass'),
  (SELECT id FROM users WHERE email='trainer@sta.demo' LIMIT 1),
  'RSI & MACD â€” Entry/Exit Signals Deep Dive',
  'Live session: RSI divergence, MACD crossover, and how TradeRakshakâ„¢ Sector AI uses these indicators.',
  (CURRENT_TIMESTAMP + INTERVAL '1 day 10 hours')::text, 90, 'zoom', 'scheduled', CURRENT_TIMESTAMP::text) ON CONFLICT DO NOTHING;

-- â”€â”€ Sample Quiz â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
INSERT INTO quizzes(institution_id, course_id, created_by, title,
  description, quiz_type, time_limit_mins, pass_score, max_attempts, status,
  total_questions, total_marks, created_at)
VALUES(2,
  (SELECT id FROM courses WHERE slug='stock-market-basics'),
  (SELECT id FROM users WHERE email='trainer@sta.demo' LIMIT 1),
  'Module 1 Assessment: Indian Markets Basics',
  'Test your understanding of NSE, BSE, Nifty 50, and basic market concepts.',
  'graded', 20, 60, 2, 'active', 5, 10, CURRENT_TIMESTAMP::text) ON CONFLICT DO NOTHING;

-- â”€â”€ Quiz Questions â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
INSERT INTO quiz_questions(quiz_id, institution_id, question_text,
  question_type, options_json, correct_answer, explanation, marks, difficulty, display_order, created_at)
VALUES
((SELECT id FROM quizzes WHERE title='Module 1 Assessment: Indian Markets Basics'),
 2, 'Which index tracks the top 50 companies on NSE by market capitalisation?',
 'mcq', '["Sensex","Nifty 50","Nifty Bank","Nifty IT"]', '1',
 'Nifty 50 tracks the 50 largest companies listed on NSE by free-float market cap.',
 2, 'easy', 1, CURRENT_TIMESTAMP::text),

((SELECT id FROM quizzes WHERE title='Module 1 Assessment: Indian Markets Basics'),
 2, 'What does "delivery percentage" indicate in stock trading?',
 'mcq', '["Price change on the day","Percentage of traded volume taken for delivery (not squared off)","Total FII buying","Dividend declared"]', '1',
 'Delivery % = (delivery volume / total traded volume) Ã— 100. High delivery % signals genuine investor interest.',
 2, 'medium', 2, CURRENT_TIMESTAMP::text),

((SELECT id FROM quizzes WHERE title='Module 1 Assessment: Indian Markets Basics'),
 2, 'Indian stock markets close at 3:30 PM IST on weekdays.',
 'true_false', '["True","False"]', 'true',
 'NSE and BSE equity segment closes at 15:30 IST, with a post-closing session until 16:00.',
 2, 'easy', 3, CURRENT_TIMESTAMP::text),

((SELECT id FROM quizzes WHERE title='Module 1 Assessment: Indian Markets Basics'),
 2, 'Which of the following are components of an OHLC candle? (Select all correct)',
 'multi_select', '["Open","High","Low","Close","Volume","Delivery"]', '0,1,2,3',
 'OHLC stands for Open, High, Low, Close. Volume and Delivery are separate data fields.',
 2, 'medium', 4, CURRENT_TIMESTAMP::text),

((SELECT id FROM quizzes WHERE title='Module 1 Assessment: Indian Markets Basics'),
 2, 'TradeRakshakâ„¢ is a SEBI-registered Investment Advisor.',
 'true_false', '["True","False"]', 'false',
 'TradeRakshakâ„¢ is a Technology Tools Platform and is NOT a SEBI-registered Investment Advisor or Research Analyst.',
 2, 'easy', 5, CURRENT_TIMESTAMP::text) ON CONFLICT DO NOTHING;

-- â”€â”€ Sample Assignment â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
INSERT INTO assignments(institution_id, course_id, batch_id, created_by,
  title, description, instructions, due_date, max_marks, pass_marks,
  submission_type, status, created_at)
VALUES(2,
  (SELECT id FROM courses WHERE slug='technical-analysis-masterclass'),
  (SELECT id FROM batches WHERE batch_code='STA-TA-2026-01'),
  (SELECT id FROM users WHERE email='trainer@sta.demo' LIMIT 1),
  'Practical: Identify RSI Divergence on a Live Chart',
  'Using TradeRakshakâ„¢ screener, identify 3 stocks showing RSI divergence and submit your analysis.',
  '1. Open TradeRakshakâ„¢ Screener\n2. Filter for RSI > 50 and slope > 0\n3. For each stock, screenshot the chart and explain the signal\n4. Add your Stop Loss and Target for each\n5. Submit as PDF.',
  (CURRENT_DATE + INTERVAL '7 days'), 100, 60,
  'file', 'active', CURRENT_TIMESTAMP::text) ON CONFLICT DO NOTHING;

-- â”€â”€ Enroll demo students in Course 1 â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
INSERT INTO enrollments(institution_id, course_id, user_id, enrollment_type, status, enrolled_at)
VALUES
(2, (SELECT id FROM courses WHERE slug='stock-market-basics'), (SELECT id FROM users WHERE email='student1@sta.demo'), 'admin_enrolled', 'active', CURRENT_TIMESTAMP::text),
(2, (SELECT id FROM courses WHERE slug='stock-market-basics'), (SELECT id FROM users WHERE email='student2@sta.demo'), 'admin_enrolled', 'active', CURRENT_TIMESTAMP::text),
(2, (SELECT id FROM courses WHERE slug='stock-market-basics'), (SELECT id FROM users WHERE email='student3@sta.demo'), 'self', 'active', CURRENT_TIMESTAMP::text) ON CONFLICT DO NOTHING;

-- â”€â”€ Enroll demo students in Batch â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
INSERT INTO enrollments(institution_id, course_id, batch_id, user_id, enrollment_type, status, fee_paid, enrolled_at)
VALUES
(2, (SELECT id FROM courses WHERE slug='technical-analysis-masterclass'),
 (SELECT id FROM batches WHERE batch_code='STA-TA-2026-01'),
 (SELECT id FROM users WHERE email='student3@sta.demo'), 'self', 'active', 3534.82, CURRENT_TIMESTAMP::text) ON CONFLICT DO NOTHING;

-- â”€â”€ Initial Announcement â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
INSERT INTO announcements(institution_id, created_by, title, content,
  priority, target_roles, is_pinned, published_at, created_at)
VALUES(2,
  (SELECT id FROM users WHERE email='academy@sta.demo' LIMIT 1),
  'Welcome to Sahi Traders Academy!',
  'Welcome to our platform! All enrolled students can access course materials, join live classes, and take quizzes. Remember: TradeRakshakâ„¢ is a Technology Tools Platform. All content is for educational purposes only.',
  'high', 'all', 1, CURRENT_TIMESTAMP::text, CURRENT_TIMESTAMP::text) ON CONFLICT DO NOTHING;

