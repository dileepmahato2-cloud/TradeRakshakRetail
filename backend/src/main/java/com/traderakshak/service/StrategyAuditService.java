package com.traderakshak.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traderakshak.util.DateUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Audit trail for stock selection, deselection and capital allocation.
 *
 * <p>Specification section 11 asks for a daily historical record of which stocks were selected and
 * deselected and how capital was allocated, alongside the order events already captured. It writes
 * to {@code trading_audit_log} - the table order events already use - rather than a new one,
 * because section 12 says not to duplicate a module that exists and the existing shape
 * ({@code event_type}, {@code symbol}, {@code details_json}) carries this without change.
 *
 * <p><b>Deduplicated on purpose.</b> The allocation preview recalculates on every tick and untick,
 * so recording every call would write a row per keystroke and bury the decisions in the noise. A
 * row is written only when the selection or the amount actually differs from the last one
 * recorded, which turns the log into a history of what changed rather than of how often the user
 * clicked.
 *
 * <p>Writes in their own transaction. An audit record that rolls back with the thing it was
 * auditing is not an audit record, and - more practically - a failure to log must never be able to
 * fail the allocation or the order it describes.
 */
@Service
public class StrategyAuditService {

    private static final Logger log = LoggerFactory.getLogger(StrategyAuditService.class);

    @PersistenceContext
    private EntityManager em;

    private final ObjectMapper mapper;

    public StrategyAuditService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Record the current selection and the allocation derived from it.
     *
     * @return true when a row was written; false when it matched the previous one and was skipped.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordSelection(Long userId, Long institutionId, String strategyKey,
                                   String mode, List<String> selected,
                                   double totalInvestment, Map<String, Object> allocation) {
        try {
            List<String> symbols = new ArrayList<>();
            if (selected != null) {
                for (String s : selected) {
                    String u = s == null ? "" : s.trim().toUpperCase(java.util.Locale.ROOT);
                    if (!u.isBlank() && !symbols.contains(u)) symbols.add(u);
                }
            }
            java.util.Collections.sort(symbols);   // order must not count as a change

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("selected", symbols);
            detail.put("selectedCount", symbols.size());
            detail.put("totalInvestment", round(totalInvestment));
            if (allocation != null) {
                detail.put("perStock", allocation.get("perStock"));
                detail.put("deployedCapital", allocation.get("deployedCapital"));
                detail.put("availableBalance", allocation.get("availableBalance"));
                detail.put("residualCash", allocation.get("residualCash"));
                detail.put("estimatedRoundTripCharges", allocation.get("estimatedRoundTripCharges"));
                // The per-leg quantities are the part worth being able to check later: a dispute
                // about an order is nearly always about how many shares, not about the total.
                detail.put("legs", allocation.get("legs"));
            }
            String json = mapper.writeValueAsString(detail);

            String signature = symbols + "|" + round(totalInvestment);
            if (signature.equals(lastSignature(userId, strategyKey))) return false;

            em.createNativeQuery(
                "INSERT INTO trading_audit_log(user_id,institution_id,strategy_key,strategy_name,"
              + "mode,event_type,symbol,side,status,message,details_json,created_at) "
              + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")
                .setParameter(1, userId)
                .setParameter(2, institutionId == null ? 1L : institutionId)
                .setParameter(3, strategyKey)
                .setParameter(4, "Volume Breakout")
                .setParameter(5, mode == null ? "PAPER" : mode)
                .setParameter(6, "SELECTION")
                // The symbols live in details_json; this column holds a readable summary so the
                // log is scannable without parsing every row.
                .setParameter(7, symbols.isEmpty() ? "(none)" : String.join(",", symbols))
                .setParameter(8, "")
                .setParameter(9, "recorded")
                .setParameter(10, symbols.size() + " stock(s) selected, "
                    + round(totalInvestment) + " to allocate")
                .setParameter(11, json)
                .setParameter(12, DateUtil.now())
                .executeUpdate();
            return true;
        } catch (Exception e) {
            // Never propagate. A failed audit write must not take down the allocation it describes.
            log.warn("[Audit] Selection record skipped: {}", e.getMessage());
            return false;
        }
    }

    /** The last recorded selection signature for this user and strategy, or null. */
    private String lastSignature(Long userId, String strategyKey) {
        try {
            @SuppressWarnings("unchecked")
            List<Object> rows = em.createNativeQuery(
                "SELECT COALESCE(details_json,'') FROM trading_audit_log "
              + "WHERE user_id=? AND strategy_key=? AND event_type='SELECTION' "
              + "ORDER BY id DESC LIMIT 1")
                .setParameter(1, userId).setParameter(2, strategyKey).getResultList();
            if (rows.isEmpty()) return null;
            var node = mapper.readTree(String.valueOf(rows.get(0)));
            List<String> prev = new ArrayList<>();
            node.path("selected").forEach(n -> prev.add(n.asText()));
            return prev + "|" + round(node.path("totalInvestment").asDouble(0));
        } catch (Exception e) {
            return null;
        }
    }

    /** One line per audited event, newest first. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> trail(Long userId, String strategyKey, String eventType,
                                           String from, String to, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            StringBuilder w = new StringBuilder(" WHERE user_id=?");
            List<Object> p = new ArrayList<>();
            p.add(userId);
            if (notBlank(strategyKey)) { w.append(" AND strategy_key=?"); p.add(strategyKey); }
            if (notBlank(eventType))   { w.append(" AND event_type=?");   p.add(eventType); }
            if (notBlank(from))        { w.append(" AND SUBSTRING(created_at,1,10) >= ?"); p.add(from); }
            if (notBlank(to))          { w.append(" AND SUBSTRING(created_at,1,10) <= ?"); p.add(to); }
            var q = em.createNativeQuery(
                "SELECT id,created_at,strategy_key,mode,event_type,symbol,side,status,message,"
              + "COALESCE(details_json,'') FROM trading_audit_log" + w
              + " ORDER BY id DESC LIMIT " + Math.max(1, Math.min(limit, 1000)));
            for (int i = 0; i < p.size(); i++) q.setParameter(i + 1, p.get(i));
            for (Object[] r : (List<Object[]>) q.getResultList()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", r[0]);
                m.put("at", str(r[1]));
                m.put("strategyKey", str(r[2]));
                m.put("mode", str(r[3]));
                m.put("eventType", str(r[4]));
                m.put("symbol", str(r[5]));
                m.put("side", str(r[6]));
                m.put("status", str(r[7]));
                m.put("message", str(r[8]));
                m.put("detail", str(r[9]));
                out.add(m);
            }
        } catch (Exception e) {
            log.warn("[Audit] Trail read failed: {}", e.getMessage());
        }
        return out;
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }
    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }
}
