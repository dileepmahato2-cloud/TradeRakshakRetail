package com.traderakshak.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traderakshak.util.DateUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class EventCalendarService {
    @PersistenceContext
    private EntityManager em;
    private final BlockingHttpService http;
    private final ObjectMapper mapper;

    public EventCalendarService(BlockingHttpService http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
    }

    public String now() {
        return DateUtil.now();
    }

    @Transactional
    public Map<String, Object> dashboard(String country, String eventType, String stock, String sector,
                                         String from, String to, String impact, String search) {
        ensureRecurringEvents();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("events", events(country, eventType, stock, sector, from, to, impact, search, 500));
        data.put("today", todayEvents());
        data.put("highImpact", highImpactEvents());
        data.put("upcoming7Days", upcoming7Days());
        data.put("watchlistEvents", watchlistEvents());
        data.put("sources", sources());
        data.put("fetchLogs", fetchLogs());
        data.put("summary", summary());
        data.put("status", dataStatus());
        return data;
    }

    @Transactional
    public Long saveEvent(Long id, Map<String, Object> req) {
        if (id == null) id = lng(req.get("id"));
        String symbol = stockSymbol(req);
        if (id == null) {
            em.createNativeQuery("INSERT INTO event_calendar(event_title,event_type,country,market,stock_symbol,symbol,sector,event_date,event_time,impact_level,source,source_url,description,status,trading_logic_enabled,scanner_enabled,ranking_enabled,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
                .setParameter(1, clamp(textOr(req.get("eventTitle"), "Untitled Event"), 1000))
                .setParameter(2, textOr(req.get("eventType"), "Market Event"))
                .setParameter(3, textOr(req.get("country"), "India"))
                .setParameter(4, text(req.get("market")))
                .setParameter(5, symbol)
                .setParameter(6, symbol)
                .setParameter(7, text(req.get("sector")))
                .setParameter(8, textOr(req.get("eventDate"), DateUtil.today()))
                .setParameter(9, text(req.get("eventTime")))
                .setParameter(10, textOr(req.get("impactLevel"), "Medium"))
                .setParameter(11, textOr(req.get("source"), "Manual"))
                .setParameter(12, text(req.get("sourceUrl")))
                .setParameter(13, text(req.get("description")))
                .setParameter(14, textOr(req.get("status"), "active"))
                .setParameter(15, bool(req.get("tradingLogicEnabled")) ? 1 : 0)
                .setParameter(16, bool(req.get("scannerEnabled")) ? 1 : 0)
                .setParameter(17, bool(req.get("rankingEnabled")) ? 1 : 0)
                .setParameter(18, now()).setParameter(19, now()).executeUpdate();
            id = lng(single("SELECT id FROM event_calendar ORDER BY id DESC LIMIT 1"));
        } else {
            em.createNativeQuery("UPDATE event_calendar SET event_title=?,event_type=?,country=?,market=?,stock_symbol=?,symbol=?,sector=?,event_date=?,event_time=?,impact_level=?,source=?,source_url=?,description=?,status=?,trading_logic_enabled=?,scanner_enabled=?,ranking_enabled=?,updated_at=? WHERE id=?")
                .setParameter(1, clamp(textOr(req.get("eventTitle"), "Untitled Event"), 1000))
                .setParameter(2, textOr(req.get("eventType"), "Market Event"))
                .setParameter(3, textOr(req.get("country"), "India"))
                .setParameter(4, text(req.get("market")))
                .setParameter(5, symbol)
                .setParameter(6, symbol)
                .setParameter(7, text(req.get("sector")))
                .setParameter(8, textOr(req.get("eventDate"), DateUtil.today()))
                .setParameter(9, text(req.get("eventTime")))
                .setParameter(10, textOr(req.get("impactLevel"), "Medium"))
                .setParameter(11, textOr(req.get("source"), "Manual"))
                .setParameter(12, text(req.get("sourceUrl")))
                .setParameter(13, text(req.get("description")))
                .setParameter(14, textOr(req.get("status"), "active"))
                .setParameter(15, bool(req.get("tradingLogicEnabled")) ? 1 : 0)
                .setParameter(16, bool(req.get("scannerEnabled")) ? 1 : 0)
                .setParameter(17, bool(req.get("rankingEnabled")) ? 1 : 0)
                .setParameter(18, now()).setParameter(19, id).executeUpdate();
        }
        if ("High".equalsIgnoreCase(text(req.get("impactLevel")))) createDefaultAlerts(id);
        return id;
    }

    @Transactional
    public void saveSource(Map<String, Object> req) {
        Long id = lng(req.get("id"));
        if (id == null) {
            em.createNativeQuery("INSERT INTO event_sources(source_name,source_type,api_url,api_key,country,event_group,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)")
                .setParameter(1, textOr(req.get("sourceName"), "New Source"))
                .setParameter(2, textOr(req.get("sourceType"), "Public API"))
                .setParameter(3, text(req.get("apiUrl")))
                .setParameter(4, text(req.get("apiKey")))
                .setParameter(5, textOr(req.get("country"), "Global"))
                .setParameter(6, textOr(req.get("eventGroup"), "Market Events"))
                .setParameter(7, textOr(req.get("status"), "active"))
                .setParameter(8, now()).setParameter(9, now()).executeUpdate();
        } else {
            em.createNativeQuery("UPDATE event_sources SET source_name=?,source_type=?,api_url=?,api_key=?,country=?,event_group=?,status=?,updated_at=? WHERE id=?")
                .setParameter(1, textOr(req.get("sourceName"), "Source"))
                .setParameter(2, textOr(req.get("sourceType"), "Public API"))
                .setParameter(3, text(req.get("apiUrl")))
                .setParameter(4, text(req.get("apiKey")))
                .setParameter(5, textOr(req.get("country"), "Global"))
                .setParameter(6, textOr(req.get("eventGroup"), "Market Events"))
                .setParameter(7, textOr(req.get("status"), "active"))
                .setParameter(8, now()).setParameter(9, id).executeUpdate();
        }
    }

    // @Transactional is required on the scheduled entry points themselves. The
    // scheduler invokes these through the Spring proxy, so a transaction is active
    // for the whole chain. fetchLatestWithRetry()'s own @Transactional is a
    // self-invocation from here and would otherwise be bypassed (no proxy), causing
    // "TransactionRequiredException: Executing an update/delete query".
    @Scheduled(cron = "0 0 8 * * MON-FRI", zone = "Asia/Kolkata")
    @Transactional
    public void marketStartFetch() {
        fetchLatestWithRetry("Market Start Scheduler");
    }

    @Scheduled(cron = "0 0 18 * * MON-FRI", zone = "Asia/Kolkata")
    @Transactional
    public void marketCloseFetch() {
        fetchLatestWithRetry("Market Close Scheduler");
    }

    @Transactional
    public Map<String, Object> fetchLatestWithRetry(String trigger) {
        int total = 0;
        List<Map<String, Object>> logs = new ArrayList<>();
        for (Map<String, Object> source : sources()) {
            if (!"active".equalsIgnoreCase(text(source.get("status")))) continue;
            int attempts = 0;
            boolean success = false;
            Exception last = null;
            while (attempts < 3 && !success) {
                attempts++;
                try {
                    int count = fetchSource(source, trigger);
                    total += count;
                    success = true;
                    logs.add(Map.of("source", source.get("sourceName"), "status", "success", "eventsFound", count));
                } catch (Exception e) {
                    last = e;
                }
            }
            if (!success) {
                String message = last == null ? "Fetch failed." : last.getMessage();
                updateSourceStatus(lng(source.get("id")), "failed", message);
                logFetch(lng(source.get("id")), text(source.get("sourceName")), "failed", message, 0);
                logs.add(Map.of("source", source.get("sourceName"), "status", "failed", "message", message));
            }
        }
        ensureRecurringEvents();
        createHighImpactAlerts();
        return Map.of("trigger", trigger, "eventsFound", total, "logs", logs, "status", dataStatus());
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> events(String country, String eventType, String stock, String sector,
                                            String from, String to, String impact, String search, int limit) {
        String symbolExpr = "COALESCE(NULLIF(symbol,''), stock_symbol)";
        StringBuilder sql = new StringBuilder("SELECT id,event_title,event_type,country,market,stock_symbol," + symbolExpr + " AS symbol,sector,event_date,event_time,impact_level,source,source_url,description,status,trading_logic_enabled,scanner_enabled,ranking_enabled,updated_at FROM event_calendar WHERE status<>'deleted'");
        if (!text(country).isBlank()) sql.append(" AND country='").append(esc(country)).append("'");
        if (!text(eventType).isBlank()) sql.append(" AND event_type='").append(esc(eventType)).append("'");
        if (!text(stock).isBlank()) sql.append(" AND UPPER(").append(symbolExpr).append(") LIKE '%").append(esc(upper(stock, ""))).append("%'");
        if (!text(sector).isBlank()) sql.append(" AND sector='").append(esc(sector)).append("'");
        if (!text(from).isBlank()) sql.append(" AND event_date>='").append(esc(from)).append("'");
        if (!text(to).isBlank()) sql.append(" AND event_date<='").append(esc(to)).append("'");
        if (!text(impact).isBlank()) sql.append(" AND impact_level='").append(esc(impact)).append("'");
        if (!text(search).isBlank()) sql.append(" AND (UPPER(event_title) LIKE '%").append(esc(upper(search, ""))).append("%' OR UPPER(description) LIKE '%").append(esc(upper(search, ""))).append("%')");
        sql.append(" ORDER BY event_date ASC,event_time ASC,id DESC LIMIT ").append(limit);
        List<Object[]> rows = em.createNativeQuery(sql.toString()).getResultList();
        List<Map<String, Object>> list = new ArrayList<>();
        String[] keys = {"id", "eventTitle", "eventType", "country", "market", "stockSymbol", "symbol", "sector", "eventDate", "eventTime", "impactLevel", "source", "sourceUrl", "description", "status", "tradingLogicEnabled", "scannerEnabled", "rankingEnabled", "updatedAt"};
        for (Object[] r : rows) list.add(row(keys, r));
        return list;
    }

    public List<Map<String, Object>> todayEvents() {
        return events("", "", "", "", DateUtil.today(), DateUtil.today(), "", "", 100);
    }

    public List<Map<String, Object>> highImpactEvents() {
        return events("", "", "", "", DateUtil.today(), LocalDate.now().plusDays(30).toString(), "High", "", 100);
    }

    public List<Map<String, Object>> upcoming7Days() {
        return events("", "", "", "", DateUtil.today(), LocalDate.now().plusDays(7).toString(), "", "", 100);
    }

    public List<Map<String, Object>> watchlistEvents() {
        try {
            return list("SELECT DISTINCT e.id,e.event_title,e.event_type,e.country,e.market,e.stock_symbol,COALESCE(NULLIF(e.symbol,''),e.stock_symbol) AS symbol,e.sector,e.event_date,e.event_time,e.impact_level,e.source,e.source_url,e.description,e.status,e.trading_logic_enabled,e.scanner_enabled,e.ranking_enabled,e.updated_at FROM event_calendar e JOIN watchlist_items w ON UPPER(w.symbol)=UPPER(COALESCE(NULLIF(e.symbol,''),e.stock_symbol)) WHERE e.status='active' ORDER BY e.event_date ASC LIMIT 100",
                "id", "eventTitle", "eventType", "country", "market", "stockSymbol", "symbol", "sector", "eventDate", "eventTime", "impactLevel", "source", "sourceUrl", "description", "status", "tradingLogicEnabled", "scannerEnabled", "rankingEnabled", "updatedAt");
        } catch (Exception ignored) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> sources() {
        return list("SELECT id,source_name,source_type,api_url,api_key,country,event_group,status,last_fetch_time,last_status,last_message FROM event_sources ORDER BY id",
            "id", "sourceName", "sourceType", "apiUrl", "apiKey", "country", "eventGroup", "status", "lastFetchTime", "lastStatus", "lastMessage");
    }

    private Map<String, Object> summary() {
        return Map.of(
            "today", count("SELECT COUNT(*) FROM event_calendar WHERE status='active' AND event_date='" + DateUtil.today() + "'"),
            "highImpact", count("SELECT COUNT(*) FROM event_calendar WHERE status='active' AND impact_level='High' AND event_date>='" + DateUtil.today() + "'"),
            "upcoming7Days", count("SELECT COUNT(*) FROM event_calendar WHERE status='active' AND event_date>='" + DateUtil.today() + "' AND event_date<='" + LocalDate.now().plusDays(7) + "'"),
            "sources", count("SELECT COUNT(*) FROM event_sources WHERE status='active'")
        );
    }

    private Map<String, Object> dataStatus() {
        Object last = single("SELECT MAX(last_fetch_time) FROM event_sources");
        long failed = count("SELECT COUNT(*) FROM event_sources WHERE status='active' AND COALESCE(last_status,'')='failed'");
        return Map.of(
            "updated", last != null && failed == 0,
            "message", last == null ? "Data not updated" : (failed > 0 ? "Data not updated from all sources" : "Data updated"),
            "lastUpdated", last == null ? "" : String.valueOf(last)
        );
    }

    private int fetchSource(Map<String, Object> source, String trigger) throws Exception {
        String url = text(source.get("apiUrl"));
        String name = text(source.get("sourceName"));
        if (url.isBlank()) {
            updateSourceStatus(lng(source.get("id")), "manual", "Manual upload source. No API URL configured.");
            logFetch(lng(source.get("id")), name, "manual", "Manual source skipped.", 0);
            return 0;
        }
        String body = http.get(url, Map.of("User-Agent", "TradeRakshak/11.5"), 15000);
        int count = parseEventsFromBody(source, body);
        updateSourceStatus(lng(source.get("id")), "success", "Fetched by " + trigger);
        logFetch(lng(source.get("id")), name, "success", "Fetched by " + trigger, count);
        return count;
    }

    private int parseEventsFromBody(Map<String, Object> source, String body) {
        String sourceName = text(source.get("sourceName"));
        String group = textOr(source.get("eventGroup"), "Market Events");
        String country = textOr(source.get("country"), "Global");
        int count = 0;
        if (body.contains("<item")) {
            Matcher m = Pattern.compile("<item[\\s\\S]*?<title>([\\s\\S]*?)</title>[\\s\\S]*?(?:<link>([\\s\\S]*?)</link>)?[\\s\\S]*?</item>").matcher(body);
            while (m.find() && count < 25) {
                String title = cleanXml(m.group(1));
                String link = m.group(2) == null ? "" : cleanXml(m.group(2));
                if (title.length() < 5) continue;
                upsertEvent(title, classifyType(title, group), country, country.equals("India") ? "NSE/BSE" : "Global", "", "", DateUtil.today(), "", impactFromTitle(title), sourceName, link, "Auto fetched public feed item.", 0, 0, 0);
                count++;
            }
        }
        if (count == 0) {
            String lower = body.toLowerCase(Locale.ROOT);
            if (lower.contains("holiday")) count += upsertEvent(sourceName + " Holiday Update", "Market Holiday", country, country.equals("India") ? "NSE/BSE" : "Global", "", "", DateUtil.today(), "", "Medium", sourceName, text(source.get("apiUrl")), "Holiday calendar source was checked.", 0, 0, 0);
            if (lower.contains("policy") || lower.contains("meeting")) count += upsertEvent(sourceName + " Policy Meeting Update", "Central Bank Meeting", country, "Global", "", "", DateUtil.today(), "", "High", sourceName, text(source.get("apiUrl")), "Policy meeting source was checked.", 1, 1, 1);
        }
        return count;
    }

    private void ensureRecurringEvents() {
        // Cleanup: F&O expiry events are no longer generated at all - the weekly ones went
        // earlier, and the monthly one has now been removed too. Soft-delete any a previous
        // build already wrote so the section and the database stop showing them. Only rows
        // this service generated (source "System Calendar") are withdrawn, so a manually
        // entered F&O event is left alone.
        try {
            em.createNativeQuery(
                "UPDATE event_calendar SET status='deleted', updated_at=? " +
                "WHERE status<>'deleted' AND (" +
                "LOWER(event_title) LIKE '%weekly f&o expiry%' OR " +
                "LOWER(event_title) LIKE '%weekly f & o%' OR " +
                "LOWER(event_title) LIKE '%weekly expiry%' OR " +
                "LOWER(event_title) LIKE '%monthly f&o expiry%' OR " +
                "(event_type='F&O Expiry' AND source='System Calendar'))")
                .setParameter(1, now())
                .executeUpdate();
        } catch (Exception ignored) {}

        LocalDate today = LocalDate.now();
        for (int i = 0; i <= 45; i++) {
            LocalDate d = today.plusDays(i);
            if (d.getDayOfMonth() == 1) {
                upsertEvent("Monthly Auto Watch: RBI, CPI, Jobs, Crude, Corporate Results", "Economic Calendar", "Global", "Global", "", "", d.toString(), "08:00", "Medium", "System Calendar", "", "System reminder to verify monthly macro and results calendar.", 1, 1, 1);
            }
        }
    }

    private int upsertEvent(String title, String type, String country, String market, String stock, String sector,
                            String date, String time, String impact, String source, String sourceUrl, String desc,
                            int trading, int scanner, int ranking) {
        long exists = count("SELECT COUNT(*) FROM event_calendar WHERE event_title='" + esc(title) + "' AND event_date='" + esc(date) + "' AND COALESCE(NULLIF(symbol,''),stock_symbol,'')='" + esc(stock) + "' AND status<>'deleted'");
        if (exists > 0) return 0;
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventTitle", title);
        event.put("eventType", type);
        event.put("country", country);
        event.put("market", market);
        event.put("stockSymbol", stock);
        event.put("sector", sector);
        event.put("eventDate", date);
        event.put("eventTime", time);
        event.put("impactLevel", impact);
        event.put("source", source);
        event.put("sourceUrl", sourceUrl);
        event.put("description", desc);
        event.put("tradingLogicEnabled", trading == 1);
        event.put("scannerEnabled", scanner == 1);
        event.put("rankingEnabled", ranking == 1);
        saveEvent(null, event);
        return 1;
    }

    private void createHighImpactAlerts() {
        for (Map<String, Object> e : highImpactEvents()) {
            Long id = lng(e.get("id"));
            if (id != null) createDefaultAlerts(id);
        }
    }

    private void createDefaultAlerts(Long eventId) {
        long exists = count("SELECT COUNT(*) FROM event_alerts WHERE event_id=" + eventId);
        if (exists > 0) return;
        em.createNativeQuery("INSERT INTO event_alerts(event_id,alert_type,alert_time,notification_status,created_at,updated_at) VALUES(?,?,?,?,?,?)")
            .setParameter(1, eventId).setParameter(2, "In-App").setParameter(3, "1 hour before").setParameter(4, "pending").setParameter(5, now()).setParameter(6, now()).executeUpdate();
        em.createNativeQuery("INSERT INTO event_alerts(event_id,alert_type,alert_time,notification_status,created_at,updated_at) VALUES(?,?,?,?,?,?)")
            .setParameter(1, eventId).setParameter(2, "Email").setParameter(3, "1 hour before").setParameter(4, "pending").setParameter(5, now()).setParameter(6, now()).executeUpdate();
        em.createNativeQuery("INSERT INTO event_alerts(event_id,alert_type,alert_time,notification_status,created_at,updated_at) VALUES(?,?,?,?,?,?)")
            .setParameter(1, eventId).setParameter(2, "Telegram").setParameter(3, "1 hour before").setParameter(4, "pending").setParameter(5, now()).setParameter(6, now()).executeUpdate();
    }

    private void updateSourceStatus(Long id, String status, String message) {
        if (id == null) return;
        em.createNativeQuery("UPDATE event_sources SET last_fetch_time=?,last_status=?,last_message=?,updated_at=? WHERE id=?")
            .setParameter(1, now()).setParameter(2, status).setParameter(3, message).setParameter(4, now()).setParameter(5, id).executeUpdate();
    }

    private void logFetch(Long sourceId, String sourceName, String status, String message, int found) {
        em.createNativeQuery("INSERT INTO event_fetch_logs(source_id,source_name,fetch_status,message,events_found,started_at,completed_at) VALUES(?,?,?,?,?,?,?)")
            .setParameter(1, sourceId).setParameter(2, sourceName).setParameter(3, status).setParameter(4, message)
            .setParameter(5, found).setParameter(6, now()).setParameter(7, now()).executeUpdate();
    }

    private List<Map<String, Object>> fetchLogs() {
        return list("SELECT source_name,fetch_status,message,events_found,completed_at FROM event_fetch_logs ORDER BY id DESC LIMIT 20",
            "sourceName", "fetchStatus", "message", "eventsFound", "completedAt");
    }

    private String classifyType(String title, String fallback) {
        String t = title.toLowerCase(Locale.ROOT);
        if (t.contains("fed") || t.contains("rbi") || t.contains("policy") || t.contains("central bank")) return "Central Bank Meeting";
        if (t.contains("cpi") || t.contains("ppi") || t.contains("gdp") || t.contains("jobs")) return "Economic Data";
        if (t.contains("holiday")) return "Market Holiday";
        if (t.contains("dividend") || t.contains("bonus") || t.contains("split") || t.contains("buyback")) return "Corporate Action";
        if (t.contains("result") || t.contains("earnings")) return "Corporate Results";
        return fallback;
    }

    private String impactFromTitle(String title) {
        String t = title.toLowerCase(Locale.ROOT);
        if (t.contains("fed") || t.contains("rbi") || t.contains("cpi") || t.contains("gdp") || t.contains("jobs") || t.contains("war") || t.contains("election") || t.contains("budget")) return "High";
        if (t.contains("holiday") || t.contains("result") || t.contains("dividend") || t.contains("expiry")) return "Medium";
        return "Low";
    }

    private String cleanXml(String s) {
        return s == null ? "" : s.replaceAll("<!\\[CDATA\\[|\\]\\]>", "").replaceAll("<[^>]+>", "").replace("&amp;", "&").trim();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(String sql, String... keys) {
        List<Object[]> rows = em.createNativeQuery(sql).getResultList();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object[] r : rows) list.add(row(keys, r));
        return list;
    }

    private Map<String, Object> row(String[] keys, Object[] r) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keys.length && i < r.length; i++) m.put(keys[i], r[i]);
        return m;
    }

    private long count(String sql) {
        try { Object r = em.createNativeQuery(sql).getSingleResult(); return r == null ? 0 : ((Number) r).longValue(); } catch (Exception e) { return 0; }
    }

    private Object single(String sql) {
        try { return em.createNativeQuery(sql).getSingleResult(); } catch (Exception e) { return null; }
    }

    private String stockSymbol(Map<String, Object> req) {
        String symbol = upper(req.get("symbol"), "");
        if (symbol.isBlank()) symbol = upper(req.get("stockSymbol"), "");
        return symbol;
    }

    private String text(Object v) { return v == null ? "" : String.valueOf(v).trim(); }
    /**
     * Caps a value from an external feed before it reaches the database. The column is TEXT
     * now, so this is belt and braces - but an oversized value used to abort the surrounding
     * transaction and take the whole calendar refresh down with it, and a feed we do not
     * control should never be able to do that again.
     */
    private String clamp(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max - 1) + "…";
    }

    private String textOr(Object v, String fallback) { String s = text(v); return s.isBlank() ? fallback : s; }
    private String upper(Object v, String fallback) { String s = text(v); return s.isBlank() ? fallback : s.toUpperCase(Locale.ROOT); }
    private boolean bool(Object v) { return Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v)) || "1".equals(String.valueOf(v)); }
    private Long lng(Object v) { try { return v == null || text(v).isBlank() ? null : Long.parseLong(text(v)); } catch (Exception e) { return null; } }
    private String esc(Object s) { return text(s).replace("'", "''"); }
}
