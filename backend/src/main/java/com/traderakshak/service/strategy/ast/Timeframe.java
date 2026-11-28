package com.traderakshak.service.strategy.ast;

import java.util.Locale;

/**
 * The bar width a condition is measured on.
 *
 * <p>A timeframe is a property of the condition, not of the strategy. "RSI(14) below 30 on the
 * daily chart while the hourly RSI is already turning up" is one rule, and forcing a single
 * timeframe per strategy would make it two strategies that cannot be combined.
 *
 * <p>Whether a timeframe can actually be measured is a separate question from whether it exists
 * here, and deliberately so. This enum is the vocabulary; {@link TimeframeAvailability} answers
 * what the stored candles support. A screener that offers 5-minute conditions while holding only
 * hourly bars does not fail loudly - it returns an empty list, which reads exactly like a
 * strategy that found nothing, and that is the worst failure a screener has.
 */
public enum Timeframe {

    M1("1 Minute", 1),
    M3("3 Minutes", 3),
    M5("5 Minutes", 5),
    M10("10 Minutes", 10),
    M15("15 Minutes", 15),
    M30("30 Minutes", 30),
    H1("1 Hour", 60),
    DAILY("Daily", null),
    /** Aggregated from daily bars rather than stored: see {@link #derived()}. */
    WEEKLY("Weekly", null),
    MONTHLY("Monthly", null);

    private final String label;
    private final Integer minutes;

    Timeframe(String label, Integer minutes) {
        this.label = label;
        this.minutes = minutes;
    }

    public String label() { return label; }

    /** Bar width in minutes, or null for daily and above. */
    public Integer minutes() { return minutes; }

    /** True for the intraday widths, which are stored as INTRADAY rows with interval_minutes. */
    public boolean intraday() { return minutes != null; }

    /**
     * True when no candle of this width is stored and the bars are rolled up from daily ones.
     *
     * <p>Weekly and monthly are the only derived widths. Rolling an hourly bar up from minutes
     * would also be possible in principle, but a rolled-up intraday bar whose session is still
     * open is a different number every time it is read, and a screener that gives a different
     * answer on a refresh is not one anybody will trust.
     */
    public boolean derived() { return this == WEEKLY || this == MONTHLY; }

    /** The width this one is aggregated from, or null when it is stored directly. */
    public Timeframe derivedFrom() { return derived() ? DAILY : null; }

    /**
     * Accepts the spellings that reach us: the enum name, the label, and the chart shorthand
     * ({@code 5m}, {@code 1h}, {@code 1d}) that both hand-written JSON and the builder use.
     *
     * @return DAILY for null or blank, which is the only safe default - every rule written before
     *         timeframes existed was a daily rule.
     */
    public static Timeframe from(String s) {
        if (s == null || s.isBlank()) return DAILY;
        String raw = s.trim().replace(" ", "").replace("_", "");

        // Case-sensitive first, and only here. Chart shorthand separates 1m (one minute) from
        // 1M (one month) by case alone, so uppercasing before this point would destroy the one
        // distinction that matters and silently turn a minute rule into a month rule.
        switch (raw) {
            case "1m":  return M1;
            case "3m":  return M3;
            case "5m":  return M5;
            case "10m": return M10;
            case "15m": return M15;
            case "30m": return M30;
            case "60m": return H1;
            case "1M":  return MONTHLY;
            default: break;
        }

        String t = raw.toUpperCase(Locale.ROOT);
        for (Timeframe tf : values()) {
            if (tf.name().equals(t)) return tf;
            if (tf.label.toUpperCase(Locale.ROOT).replace(" ", "").equals(t)) return tf;
        }
        switch (t) {
            case "MIN": case "1MIN": case "1MINUTE": case "MINUTE":   return M1;
            case "3M": case "3MIN": case "3MINUTES":                  return M3;
            case "5M": case "5MIN": case "5MINUTES":                   return M5;
            case "10M": case "10MIN": case "10MINUTES":                return M10;
            case "15M": case "15MIN": case "15MINUTES":                return M15;
            case "30M": case "30MIN": case "30MINUTES":                return M30;
            case "H": case "1H": case "60M": case "60MIN":
            case "1HOUR": case "HOUR": case "HOURLY":                  return H1;
            case "D": case "1D": case "DAY": case "EOD":               return DAILY;
            case "W": case "1W": case "WEEK":                          return WEEKLY;
            case "MO": case "1MO": case "MONTH":                       return MONTHLY;
            default:
                throw new IllegalArgumentException("Unknown timeframe \"" + s + "\". Expected "
                    + "one of 1m, 3m, 5m, 10m, 15m, 30m, 1h, daily, weekly or monthly.");
        }
    }
}
