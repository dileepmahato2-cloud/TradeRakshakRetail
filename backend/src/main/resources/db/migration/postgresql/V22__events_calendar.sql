CREATE TABLE IF NOT EXISTS event_calendar (
    id BIGSERIAL PRIMARY KEY,
    event_title VARCHAR(240) NOT NULL,
    event_type VARCHAR(100),
    country VARCHAR(80),
    market VARCHAR(80),
    stock_symbol VARCHAR(80),
    sector VARCHAR(120),
    event_date VARCHAR(20),
    event_time VARCHAR(20),
    impact_level VARCHAR(20) DEFAULT 'Medium',
    source VARCHAR(120),
    source_url TEXT,
    description TEXT,
    status VARCHAR(40) DEFAULT 'active',
    trading_logic_enabled INTEGER DEFAULT 0,
    scanner_enabled INTEGER DEFAULT 0,
    ranking_enabled INTEGER DEFAULT 0,
    created_at VARCHAR(40),
    updated_at VARCHAR(40)
);

CREATE TABLE IF NOT EXISTS event_alerts (
    id BIGSERIAL PRIMARY KEY,
    event_id BIGINT REFERENCES event_calendar(id) ON DELETE CASCADE,
    alert_type VARCHAR(40),
    alert_time VARCHAR(40),
    notification_status VARCHAR(40) DEFAULT 'pending',
    created_at VARCHAR(40),
    updated_at VARCHAR(40)
);

CREATE TABLE IF NOT EXISTS event_sources (
    id BIGSERIAL PRIMARY KEY,
    source_name VARCHAR(140) NOT NULL,
    source_type VARCHAR(80),
    api_url TEXT,
    api_key TEXT,
    country VARCHAR(80),
    event_group VARCHAR(80),
    status VARCHAR(40) DEFAULT 'active',
    last_fetch_time VARCHAR(40),
    last_status VARCHAR(40),
    last_message TEXT,
    created_at VARCHAR(40),
    updated_at VARCHAR(40)
);

CREATE TABLE IF NOT EXISTS event_fetch_logs (
    id BIGSERIAL PRIMARY KEY,
    source_id BIGINT,
    source_name VARCHAR(140),
    fetch_status VARCHAR(40),
    message TEXT,
    events_found INTEGER DEFAULT 0,
    started_at VARCHAR(40),
    completed_at VARCHAR(40)
);

CREATE INDEX IF NOT EXISTS idx_event_calendar_date ON event_calendar(event_date);
CREATE INDEX IF NOT EXISTS idx_event_calendar_impact ON event_calendar(impact_level);
CREATE INDEX IF NOT EXISTS idx_event_calendar_symbol ON event_calendar(stock_symbol);
CREATE INDEX IF NOT EXISTS idx_event_sources_status ON event_sources(status);

INSERT INTO event_sources(source_name, source_type, api_url, country, event_group, status, created_at, updated_at)
SELECT 'NSE Market Holidays', 'Public Web', 'https://www.nseindia.com/resources/exchange-communication-holidays', 'India', 'Market Events', 'active', CURRENT_TIMESTAMP::text, CURRENT_TIMESTAMP::text
WHERE NOT EXISTS (SELECT 1 FROM event_sources WHERE source_name='NSE Market Holidays');

INSERT INTO event_sources(source_name, source_type, api_url, country, event_group, status, created_at, updated_at)
SELECT 'BSE Corporate Announcements', 'Public Web', 'https://www.bseindia.com/corporates/ann.html', 'India', 'Corporate Actions', 'active', CURRENT_TIMESTAMP::text, CURRENT_TIMESTAMP::text
WHERE NOT EXISTS (SELECT 1 FROM event_sources WHERE source_name='BSE Corporate Announcements');

INSERT INTO event_sources(source_name, source_type, api_url, country, event_group, status, created_at, updated_at)
SELECT 'US Federal Reserve Calendar', 'RSS/Public Web', 'https://www.federalreserve.gov/feeds/press_all.xml', 'United States', 'Global Events', 'active', CURRENT_TIMESTAMP::text, CURRENT_TIMESTAMP::text
WHERE NOT EXISTS (SELECT 1 FROM event_sources WHERE source_name='US Federal Reserve Calendar');

INSERT INTO event_sources(source_name, source_type, api_url, country, event_group, status, created_at, updated_at)
SELECT 'Economic Calendar Manual Backup', 'Manual Upload', '', 'Global', 'Economic Calendar', 'active', CURRENT_TIMESTAMP::text, CURRENT_TIMESTAMP::text
WHERE NOT EXISTS (SELECT 1 FROM event_sources WHERE source_name='Economic Calendar Manual Backup');
