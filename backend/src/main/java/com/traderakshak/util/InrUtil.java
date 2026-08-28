package com.traderakshak.util;

/**
 * Indian number formatting — mirrors Python's Jinja2 filters:
 * inr(), inr_auto(), pct(), num()
 * These are exposed as Angular pipes in the frontend.
 */
public final class InrUtil {

    private InrUtil() {}

    /** Indian comma grouping: 1,23,45,678 */
    public static String formatInr(double value) {
        if (value == 0) return "0";
        boolean neg = value < 0;
        value = Math.abs(value);
        String s = String.format("%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
        String[] parts = s.split("\\.");
        String w = parts[0];
        String d = parts.length > 1 ? parts[1] : "";

        if (w.length() > 3) {
            String last3 = w.substring(w.length() - 3);
            String rest  = w.substring(0, w.length() - 3);
            StringBuilder sb = new StringBuilder();
            while (rest.length() > 2) {
                sb.insert(0, "," + rest.substring(rest.length() - 2));
                rest = rest.substring(0, rest.length() - 2);
            }
            if (!rest.isEmpty()) sb.insert(0, rest);
            w = sb + "," + last3;
        }

        return (neg ? "−" : "") + w + (d.isEmpty() ? "" : "." + d);
    }

    /** Auto-scale: Cr / L / plain */
    public static String formatInrAuto(double value) {
        double av = Math.abs(value);
        if (av >= 1e7) return String.format("%.2f Cr", value / 1e7);
        if (av >= 1e5) return String.format("%.2f L",  value / 1e5);
        return String.format("%,.0f", value);
    }

    public static String formatPct(double value) {
        return String.format("%.2f%%", value);
    }

    /** Safe float conversion — strips ₹, commas, % */
    public static double safeFloat(Object v, double defaultVal) {
        if (v == null) return defaultVal;
        try {
            return Double.parseDouble(
                v.toString().replace(",","").replace("₹","").replace("%","").trim());
        } catch (Exception e) {
            return defaultVal;
        }
    }

    /** Normalise stock symbol: uppercase alphanumeric + & - only */
    public static String normaliseSymbol(String s) {
        if (s == null) return "";
        return s.toUpperCase().trim().replaceAll("[^A-Z0-9&\\-]", "");
    }
}
