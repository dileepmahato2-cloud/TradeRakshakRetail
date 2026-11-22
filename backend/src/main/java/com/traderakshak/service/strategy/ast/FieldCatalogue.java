package com.traderakshak.service.strategy.ast;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every field, indicator and fundamental the rule engine understands.
 *
 * <p>One source, read by three callers: the validator, the {@code GET /fields} endpoint that
 * drives the builder's palette, and the prompt the natural-language interpreter is given. Three
 * hand-maintained lists is how a screener ends up accepting {@code EMA20} in one place and
 * {@code ema_20} in another, and how the AI invents a field the evaluator has never heard of.
 *
 * <p>Availability is part of the definition, not a UI concern. {@code fundamentals} holds no rows
 * today, so every fundamental metric is published as NO_DATA - which lets the builder grey them
 * out rather than offering rules that silently match nothing for months.
 */
@Component
public class FieldCatalogue {

    public enum Category { PRICE, VOLUME, INDICATOR, DERIVED, FUNDAMENTAL }

    /** Whether a rule using this field can actually return anything today. */
    public enum Availability {
        AVAILABLE,
        /** Defined and evaluable, but the table behind it is empty. */
        NO_DATA
    }

    public record ParamDef(String name, String type, Object defaultValue, Double min, Double max) {
        public static ParamDef period(int def) {
            return new ParamDef("period", "int", def, 1.0, 500.0);
        }
    }

    public record FieldDef(String key, String label, Category category,
                           List<ParamDef> params, Availability availability, String unit) {}

    private final Map<String, FieldDef> byKey = new LinkedHashMap<>();

    public FieldCatalogue() {
        // ---- price -----------------------------------------------------------------
        price("close", "Close");
        price("open", "Open");
        price("high", "High");
        price("low", "Low");
        price("prev_close", "Previous Close");
        price("prev_high", "Previous Day High");
        price("prev_low", "Previous Day Low");

        // ---- volume ----------------------------------------------------------------
        add("volume", "Volume", Category.VOLUME, List.of(), Availability.AVAILABLE, "shares");
        add("avg_volume", "Average Volume", Category.VOLUME, List.of(ParamDef.period(20)),
            Availability.AVAILABLE, "shares");
        add("volume_ratio", "Volume Ratio", Category.VOLUME, List.of(ParamDef.period(20)),
            Availability.AVAILABLE, "x");

        // ---- indicators, all backed by TechnicalIndicatorEngine ---------------------
        indicator("RSI", "RSI", List.of(ParamDef.period(14)), "0-100");
        indicator("EMA", "EMA", List.of(ParamDef.period(20)), "price");
        indicator("SMA", "SMA", List.of(ParamDef.period(20)), "price");
        indicator("WMA", "WMA", List.of(ParamDef.period(20)), "price");
        indicator("VWAP", "VWAP", List.of(), "price");
        indicator("ATR", "ATR", List.of(ParamDef.period(14)), "price");
        indicator("ADX", "ADX", List.of(ParamDef.period(14)), "0-100");
        indicator("OBV", "On-Balance Volume", List.of(), "shares");
        indicator("CCI", "CCI", List.of(ParamDef.period(20)), "index");
        indicator("MFI", "Money Flow Index", List.of(ParamDef.period(14)), "0-100");
        indicator("ROC", "Rate of Change", List.of(ParamDef.period(12)), "%");
        indicator("WILLIAMS_R", "Williams %R", List.of(ParamDef.period(14)), "-100-0");

        // Multi-output indicators publish one key per line, because a rule compares a number.
        // Hiding "which line" inside a params map is how MACD ends up compared against its own
        // signal by accident.
        indicator("MACD", "MACD line", macdParams(), "price");
        indicator("MACD_SIGNAL", "MACD signal", macdParams(), "price");
        indicator("MACD_HIST", "MACD histogram", macdParams(), "price");
        indicator("BB_UPPER", "Bollinger upper", bollingerParams(), "price");
        indicator("BB_MIDDLE", "Bollinger middle", bollingerParams(), "price");
        indicator("BB_LOWER", "Bollinger lower", bollingerParams(), "price");
        indicator("BB_WIDTH", "Bollinger width", bollingerParams(), "%");
        indicator("BB_PERCENT_B", "Bollinger %B", bollingerParams(), "0-1");
        indicator("SUPERTREND", "Supertrend", supertrendParams(), "price");
        indicator("STOCH_K", "Stochastic %K", stochasticParams(), "0-100");
        indicator("STOCH_D", "Stochastic %D", stochasticParams(), "0-100");

        // ---- derived ---------------------------------------------------------------
        add("pct_change", "Percentage Change", Category.DERIVED, List.of(ParamDef.period(1)),
            Availability.AVAILABLE, "%");
        add("gap_pct", "Gap %", Category.DERIVED, List.of(), Availability.AVAILABLE, "%");
        add("relative_strength", "Relative Strength vs index", Category.DERIVED,
            List.of(ParamDef.period(55)), Availability.AVAILABLE, "ratio");

        // ---- fundamentals: defined, evaluable, and empty ----------------------------
        // Growth and margins
        fundamental("revenue", "Revenue");
        fundamental("sales", "Sales");
        fundamental("net_profit", "Net Profit");
        fundamental("eps", "EPS");
        fundamental("operating_profit", "Operating Profit");
        fundamental("ebitda", "EBITDA");
        fundamental("opm", "Operating Profit Margin");
        fundamental("net_profit_margin", "Net Profit Margin");
        fundamental("roe", "ROE");
        fundamental("roce", "ROCE");
        fundamental("book_value", "Book Value");
        fundamental("dividend", "Dividend");
        fundamental("cash_flow", "Cash Flow");
        // Balance sheet
        fundamental("debt_equity", "Debt to Equity");
        fundamental("current_ratio", "Current Ratio");
        fundamental("interest_coverage", "Interest Coverage");
        fundamental("cash_reserves", "Cash Reserves");
        fundamental("free_cash_flow", "Free Cash Flow");
        fundamental("promoter_holding", "Promoter Holding %");
        fundamental("institutional_holding", "Institutional Holding %");
        fundamental("pledge_pct", "Pledge %");
        // Valuation
        fundamental("pe", "PE Ratio");
        fundamental("forward_pe", "Forward PE");
        fundamental("peg", "PEG Ratio");
        fundamental("pb", "PB Ratio");
        fundamental("ev_ebitda", "EV/EBITDA");
        fundamental("price_to_sales", "Price to Sales");
        fundamental("dividend_yield", "Dividend Yield");
    }

    private static List<ParamDef> macdParams() {
        return List.of(new ParamDef("fast", "int", 12, 1.0, 200.0),
                       new ParamDef("slow", "int", 26, 1.0, 400.0),
                       new ParamDef("signal", "int", 9, 1.0, 100.0));
    }

    private static List<ParamDef> bollingerParams() {
        return List.of(ParamDef.period(20),
                       new ParamDef("stdMult", "double", 2.0, 0.1, 10.0));
    }

    private static List<ParamDef> supertrendParams() {
        return List.of(ParamDef.period(10),
                       new ParamDef("multiplier", "double", 3.0, 0.1, 20.0));
    }

    private static List<ParamDef> stochasticParams() {
        return List.of(new ParamDef("kPeriod", "int", 14, 1.0, 200.0),
                       new ParamDef("dPeriod", "int", 3, 1.0, 100.0));
    }

    private void price(String key, String label) {
        add(key, label, Category.PRICE, List.of(), Availability.AVAILABLE, "price");
    }

    private void indicator(String key, String label, List<ParamDef> params, String unit) {
        add(key, label, Category.INDICATOR, params, Availability.AVAILABLE, unit);
    }

    /** Every fundamental is NO_DATA until the financials pipeline lands. */
    private void fundamental(String key, String label) {
        add(key, label, Category.FUNDAMENTAL, List.of(), Availability.NO_DATA, "varies");
    }

    private void add(String key, String label, Category category, List<ParamDef> params,
                     Availability availability, String unit) {
        byKey.put(key.toUpperCase(Locale.ROOT),
                  new FieldDef(key, label, category, params, availability, unit));
    }

    public FieldDef get(String key) {
        return key == null ? null : byKey.get(key.trim().toUpperCase(Locale.ROOT));
    }

    public boolean has(String key) { return get(key) != null; }

    public List<FieldDef> all() { return new ArrayList<>(byKey.values()); }

    public List<FieldDef> byCategory(Category c) {
        List<FieldDef> out = new ArrayList<>();
        for (FieldDef f : byKey.values()) if (f.category() == c) out.add(f);
        return out;
    }
}
