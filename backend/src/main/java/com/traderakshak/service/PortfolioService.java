package com.traderakshak.service;

import com.traderakshak.entity.User;
import com.traderakshak.exception.TradeRakshakException;
import com.traderakshak.util.DateUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.*;

@Service @RequiredArgsConstructor @Slf4j
public class PortfolioService {

    @PersistenceContext private EntityManager em;
    private final AuditService auditService;

    // ── Portfolio CRUD ─────────────────────────────────────────────────────────
    @Transactional
    public Map<String, Object> createPortfolio(User user, Map<String, Object> req) {
        String name = (String) req.getOrDefault("name", "My Portfolio");
        double capital = getDouble(req, "initialCapital", 100000.0);
        em.createNativeQuery(
            "INSERT INTO portfolios(user_id,institution_id,name,description,portfolio_type," +
            "initial_capital,current_value,cash_balance,is_default,risk_profile,status,created_at,updated_at) " +
            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")
            .setParameter(1, user.getId()).setParameter(2, user.getInstitutionId())
            .setParameter(3, name).setParameter(4, req.getOrDefault("description",""))
            .setParameter(5, req.getOrDefault("portfolioType","paper"))
            .setParameter(6, capital).setParameter(7, capital).setParameter(8, capital)
            .setParameter(9, req.getOrDefault("isDefault", 0))
            .setParameter(10, req.getOrDefault("riskProfile","moderate"))
            .setParameter(11, "active")
            .setParameter(12, DateUtil.now()).setParameter(13, DateUtil.now()).executeUpdate();
        auditService.logActivity(user.getId(),"Portfolio","Create","success",name,"","");
        return Map.of("message","Portfolio created.", "name", name);
    }

    public List<?> listPortfolios(User user) {
        return em.createNativeQuery("SELECT * FROM portfolios WHERE user_id=:uid AND status='active' ORDER BY is_default DESC, id DESC")
            .setParameter("uid", user.getId()).getResultList();
    }

    public Map<String, Object> getPortfolioDetail(Long portfolioId, Long userId) {
        Object[] p = (Object[]) em.createNativeQuery("SELECT * FROM portfolios WHERE id=:id AND user_id=:uid")
            .setParameter("id", portfolioId).setParameter("uid", userId).getSingleResult();
        List<?> positions = em.createNativeQuery("SELECT * FROM positions WHERE portfolio_id=:pid AND status='open' ORDER BY created_at DESC")
            .setParameter("pid", portfolioId).getResultList();
        List<?> recentTrades = em.createNativeQuery("SELECT * FROM trades WHERE portfolio_id=:pid ORDER BY created_at DESC LIMIT 20")
            .setParameter("pid", portfolioId).getResultList();
        // Risk metrics
        double invested = positions.stream().mapToDouble(pos -> {
            Object[] r = (Object[]) pos; return r[11] != null ? ((Number)r[11]).doubleValue() : 0;
        }).sum();
        return Map.of("portfolio", p, "positions", positions, "recentTrades", recentTrades,
                      "totalInvested", invested, "openPositions", positions.size());
    }

    // ── Trade entry ────────────────────────────────────────────────────────────
    @Transactional
    public Map<String, Object> addTrade(User user, Long portfolioId, Map<String, Object> req) {
        String symbol = (String) req.get("symbol");
        String tradeType = (String) req.getOrDefault("tradeType","BUY");
        int qty = getInt(req, "qty", 1);
        double price = getDouble(req, "price", 0);
        if (symbol == null || price <= 0 || qty <= 0)
            throw new TradeRakshakException("PRT-001","Symbol, qty, and price are required.");

        double amount = qty * price;
        double brok = Math.round(amount * 0.0003 * 100) / 100.0; // 0.03% brokerage
        double taxes = Math.round(amount * 0.001 * 100) / 100.0; // STT + other charges ~0.1%
        double net = amount + brok + taxes;

        // Check cash balance for BUY
        if ("BUY".equals(tradeType)) {
            Object pf = em.createNativeQuery("SELECT cash_balance FROM portfolios WHERE id=:id")
                .setParameter("id", portfolioId).getSingleResult();
            double cash = pf != null ? ((Number) pf).doubleValue() : 0;
            if (cash < net) throw new TradeRakshakException("PRT-002","Insufficient cash balance.");
        }

        String tradeId = "TR-" + DateUtil.today().replace("-","") + "-" +
                         symbol.substring(0,Math.min(symbol.length(),6)) + "-" +
                         (int)(Math.random()*9000+1000);

        em.createNativeQuery(
            "INSERT INTO trades(portfolio_id,user_id,institution_id,trade_id,exchange,symbol,segment," +
            "trade_type,qty,price,amount,brokerage,taxes,net_amount,execution_type,strategy_id," +
            "status,trade_date,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
            .setParameter(1,portfolioId).setParameter(2,user.getId()).setParameter(3,user.getInstitutionId())
            .setParameter(4,tradeId).setParameter(5,req.getOrDefault("exchange","NSE"))
            .setParameter(6,symbol).setParameter(7,req.getOrDefault("segment","EQ-SWG"))
            .setParameter(8,tradeType).setParameter(9,qty).setParameter(10,price)
            .setParameter(11,amount).setParameter(12,brok).setParameter(13,taxes).setParameter(14,net)
            .setParameter(15,"manual").setParameter(16,req.get("strategyId"))
            .setParameter(17,"executed").setParameter(18,DateUtil.today()).setParameter(19,DateUtil.now())
            .executeUpdate();

        // Update position and portfolio
        updatePositionAfterTrade(portfolioId, symbol, (String)req.getOrDefault("exchange","NSE"),
            tradeType, qty, price, net);
        updatePortfolioAfterTrade(portfolioId, tradeType, net);

        auditService.logActivity(user.getId(),"Portfolio","Trade","success",
            tradeType+" "+qty+" "+symbol+" @ "+price,"","");
        return Map.of("tradeId",tradeId,"symbol",symbol,"qty",qty,"price",price,
                      "amount",amount,"brokerage",brok,"taxes",taxes,"net",net);
    }

    // ── Portfolio rebalancing ──────────────────────────────────────────────────
    public Map<String, Object> getRebalanceSuggestions(Long portfolioId, Long userId) {
        List<?> positions = em.createNativeQuery(
            "SELECT p.symbol, p.sector, p.current_value, p.unrealised_pct " +
            "FROM positions p WHERE p.portfolio_id=:pid AND p.status='open'")
            .setParameter("pid", portfolioId).getResultList();

        Map<String, Double> sectorExposure = new LinkedHashMap<>();
        double totalValue = 0;
        for (Object o : positions) {
            Object[] r = (Object[]) o;
            String sector = r[1] != null ? r[1].toString() : "Unknown";
            double val = r[2] != null ? ((Number)r[2]).doubleValue() : 0;
            sectorExposure.merge(sector, val, Double::sum);
            totalValue += val;
        }

        List<Map<String, Object>> suggestions = new ArrayList<>();
        double idealMax = 30.0; // max 30% per sector
        for (Map.Entry<String, Double> e : sectorExposure.entrySet()) {
            double pct = totalValue > 0 ? e.getValue() / totalValue * 100 : 0;
            if (pct > idealMax) {
                suggestions.add(Map.of(
                    "sector", e.getKey(),
                    "currentPct", Math.round(pct*10)/10.0,
                    "idealMax", idealMax,
                    "action", "REDUCE",
                    "message", "Reduce " + e.getKey() + " exposure from " +
                               Math.round(pct*10)/10.0 + "% to below " + idealMax + "%"
                ));
            }
        }
        return Map.of("sectorExposure", sectorExposure, "totalPositions", positions.size(),
                      "suggestions", suggestions, "totalValue", totalValue);
    }

    // ── Risk analysis ──────────────────────────────────────────────────────────
    public Map<String, Object> getRiskAnalysis(Long portfolioId, Long userId) {
        Object[] pf = (Object[]) em.createNativeQuery("SELECT * FROM portfolios WHERE id=:id")
            .setParameter("id", portfolioId).getSingleResult();
        double initial = pf[6] != null ? ((Number)pf[6]).doubleValue() : 100000;
        double current = pf[7] != null ? ((Number)pf[7]).doubleValue() : 100000;
        double maxDD   = pf[12]!= null ? ((Number)pf[12]).doubleValue(): 0;

        double returnPct = initial > 0 ? (current - initial) / initial * 100 : 0;
        String riskLevel = maxDD > 20 ? "HIGH" : maxDD > 10 ? "MEDIUM" : "LOW";

        return Map.of(
            "initialCapital", initial, "currentValue", current,
            "returnPct", Math.round(returnPct*100)/100.0,
            "maxDrawdown", maxDD, "riskLevel", riskLevel,
            "valueAtRisk", Math.round(current * 0.05 * 100) / 100.0, // 5% VaR estimate
            "sharpeRatio", pf[13] != null ? pf[13] : 0
        );
    }

    @Transactional
    private void updatePositionAfterTrade(Long portfolioId, String symbol, String exchange,
                                           String tradeType, int qty, double price, double net) {
        try {
            long exists = ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM positions WHERE portfolio_id=:pid AND symbol=:sym AND status='open'")
                .setParameter("pid", portfolioId).setParameter("sym", symbol).getSingleResult()).longValue();

            if ("BUY".equals(tradeType)) {
                if (exists > 0) {
                    em.createNativeQuery(
                        "UPDATE positions SET qty=qty+:q, invested_value=invested_value+:amt, " +
                        "avg_price=((avg_price*qty+:price*:q)/(qty+:q)), updated_at=:t " +
                        "WHERE portfolio_id=:pid AND symbol=:sym AND status='open'")
                        .setParameter("q", qty).setParameter("amt", net)
                        .setParameter("price", price).setParameter("t", DateUtil.now())
                        .setParameter("pid", portfolioId).setParameter("sym", symbol).executeUpdate();
                } else {
                    em.createNativeQuery(
                        "INSERT INTO positions(portfolio_id,user_id,institution_id,exchange,symbol," +
                        "segment,position_type,qty,avg_price,current_price,invested_value,current_value," +
                        "unrealised_pnl,entry_date,status,created_at,updated_at) " +
                        "VALUES(:pid,(SELECT user_id FROM portfolios WHERE id=:pid)," +
                        "(SELECT institution_id FROM portfolios WHERE id=:pid),:ex,:sym," +
                        "'EQ-SWG','LONG',:q,:p,:p,:amt,:amt,0,:d,'open',:t,:t)")
                        .setParameter("pid", portfolioId).setParameter("ex", exchange)
                        .setParameter("sym", symbol).setParameter("q", qty).setParameter("p", price)
                        .setParameter("amt", net).setParameter("d", DateUtil.today())
                        .setParameter("t", DateUtil.now()).executeUpdate();
                }
            } else if ("SELL".equals(tradeType) && exists > 0) {
                em.createNativeQuery(
                    "UPDATE positions SET qty=qty-:q WHERE portfolio_id=:pid AND symbol=:sym AND status='open'")
                    .setParameter("q", qty).setParameter("pid", portfolioId).setParameter("sym", symbol).executeUpdate();
                em.createNativeQuery(
                    "UPDATE positions SET status='closed' WHERE portfolio_id=:pid AND symbol=:sym AND qty<=0")
                    .setParameter("pid", portfolioId).setParameter("sym", symbol).executeUpdate();
            }
        } catch (Exception e) { log.debug("Position update error: {}", e.getMessage()); }
    }

    @Transactional
    private void updatePortfolioAfterTrade(Long portfolioId, String tradeType, double net) {
        try {
            if ("BUY".equals(tradeType)) {
                em.createNativeQuery("UPDATE portfolios SET cash_balance=cash_balance-:n, updated_at=:t WHERE id=:id")
                    .setParameter("n", net).setParameter("t", DateUtil.now()).setParameter("id", portfolioId).executeUpdate();
            } else {
                em.createNativeQuery("UPDATE portfolios SET cash_balance=cash_balance+:n, updated_at=:t WHERE id=:id")
                    .setParameter("n", net).setParameter("t", DateUtil.now()).setParameter("id", portfolioId).executeUpdate();
            }
        } catch (Exception e) { log.debug("Portfolio balance update error: {}", e.getMessage()); }
    }

    private int    getInt(Map<?,?> m, String k, int d)       { Object v=m.get(k); return v==null?d:((Number)v).intValue(); }
    private double getDouble(Map<?,?> m, String k, double d) { Object v=m.get(k); return v==null?d:((Number)v).doubleValue(); }
}
