package com.traderakshak.service;

import org.springframework.stereotype.Service;

/**
 * Port of Python compute_trade_score() — BRD §12.1
 * 0-100 score combining delivery%, R:R, EMA alignment, sector AI, fundamentals.
 */
@Service
public class TradeScoreService {

    public double compute(double cmp, double deliveryPct, double rrRatio,
                          boolean aboveEma20, boolean aboveEma50, boolean aboveEma200,
                          String sectorSignal, Double distFromAthPct,
                          double volumeRatio, Double pe, Double roe, String oiSignal) {
        double score = 0;

        // Delivery volume (20 pts)
        double dvRatio = deliveryPct / 60.0;
        score += dvRatio >= 2.0 ? 20 : dvRatio >= 1.5 ? 14 : dvRatio >= 1.3 ? 9 : 0;

        // R:R ratio (15 pts)
        score += rrRatio >= 3.0 ? 15 : rrRatio >= 2.5 ? 11 : rrRatio >= 2.0 ? 8 : 0;

        // EMA alignment (12 pts)
        if (aboveEma200) score += 12;
        else if (aboveEma50) score += 8;
        else if (aboveEma20) score += 4;

        // Sector AI signal (10 pts)
        score += switch (sectorSignal != null ? sectorSignal : "HOLD") {
            case "BUY_AGGRESSIVE" -> 10;
            case "BUY"            -> 7;
            case "WATCH_BUY"      -> 4;
            case "HOLD"           -> 3;
            default               -> 0;
        };

        // Fundamentals PE + ROE (10 pts)
        if (pe != null && roe != null) {
            if (pe < 20 && roe > 20) score += 10;
            else if (pe < 30 || roe > 15) score += 5;
        }

        // OI signal for F&O (8 pts)
        if ("bullish".equals(oiSignal)) score += 8;
        else if ("neutral".equals(oiSignal)) score += 4;

        // Distance from ATH (5 pts)
        if (distFromAthPct != null) {
            score += distFromAthPct < 5 ? 5 : distFromAthPct < 10 ? 3 : distFromAthPct < 20 ? 1 : 0;
        }

        // Volume ratio (5 pts)
        score += volumeRatio >= 1.5 ? 5 : volumeRatio >= 1.2 ? 3 : 0;

        return Math.round(Math.min(100, Math.max(0, score)) * 10.0) / 10.0;
    }
}
