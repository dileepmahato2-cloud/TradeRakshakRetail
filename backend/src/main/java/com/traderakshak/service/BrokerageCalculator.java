package com.traderakshak.service;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Statutory charges and brokerage on an equity trade, at Dhan's published rates.
 *
 * <p><b>Why this exists.</b> Nothing in this application computed charges. {@code orderCalculation}
 * returns quantity, stop, target and risk but no cost figure, and the journal insert reads
 * {@code brokerageCharges} straight out of the request body - so a caller that sent nothing got a
 * net P&amp;L identical to its gross P&amp;L. For a strategy that enters at one session's close and
 * exits at the next, that is not a rounding error: a round trip costs about 0.30% of the position
 * (see the worked example in the BRD), so a strategy averaging 0.25% a session reads as profitable
 * and is not.
 *
 * <p><b>Rates.</b> Taken from Dhan's own pricing page (dhan.co/pricing, Equity tab and Demat
 * Account Related Charges), read 2026-11-24. Every rate is a configuration key under
 * {@code charges.*} and is read per call rather than cached in a field, so changing a rate in
 * Application Settings takes effect without a restart - these change by regulation and by broker
 * plan, and a rate frozen into a build is a rate that silently goes stale.
 *
 * <p><b>Delivery vs intraday matters a lot here.</b> They are not variations on one fee schedule:
 * <ul>
 *   <li>brokerage is zero on delivery, capped at Rs 20 per executed order on intraday;</li>
 *   <li>STT is 0.1% on <i>both</i> legs of a delivery trade and 0.025% on the <i>sell leg only</i>
 *       of an intraday one - a four-fold difference in the largest single component;</li>
 *   <li>stamp duty is 0.015% on delivery buys against 0.003% on intraday buys;</li>
 *   <li>the DP charge applies to a delivery sell and not at all to intraday.</li>
 * </ul>
 * A position held from one close to the next is <b>delivery</b>. Charging it as intraday would
 * understate a round trip by roughly a third, so {@link Product} is a required argument rather than
 * something with a convenient default.
 *
 * <p><b>Rounding.</b> Dhan states: "Stamp Duty and STT are rounded off to the nearest rupee and all
 * other charges to the nearest 2 decimals." Followed exactly, so a computed figure reconciles
 * against a contract note instead of landing a rupee or two away from it.
 */
@Service
public class BrokerageCalculator {

    /** Delivery settles and is held overnight; intraday is squared off the same session. */
    public enum Product { DELIVERY, INTRADAY, MTF }

    public enum Side { BUY, SELL }

    private final AppConfigService appConfig;

    public BrokerageCalculator(AppConfigService appConfig) {
        this.appConfig = appConfig;
    }

    /**
     * Charges on one leg.
     *
     * @param exchange NSE or BSE - the transaction charge differs, and defaults to the NSE rate for
     *                 anything unrecognised rather than to zero. A zero default would quietly make
     *                 an unknown exchange look cheaper than a known one.
     * @param isLastDeliverySellOfDay whether the DP charge applies. It is levied per instruction per
     *                 ISIN per day, not per order, so a caller splitting one exit across several
     *                 orders must pass true on one of them only. Ignored outside a delivery sell.
     */
    public Charges charges(Product product, Side side, String exchange, double price, int quantity,
                           boolean isLastDeliverySellOfDay) {
        double turnover = round2(Math.max(0, price) * Math.max(0, quantity));
        if (turnover <= 0) return Charges.zero();

        double brokerage = brokerage(product, turnover);
        double txn       = round2(turnover * pct(txnKey(exchange)));
        double sebi      = round2(turnover * pct("charges.sebi.pct"));
        double ipft      = round2(turnover * pct("charges.ipft.pct"));
        double stt       = roundRupee(turnover * sttPct(product, side));
        double stamp     = roundRupee(turnover * stampPct(product, side));
        // GST is on the SERVICE components only - brokerage, exchange transaction, SEBI and IPFT.
        // It is not charged on STT or stamp duty, which are taxes rather than services. Including
        // them would overstate a delivery round trip by about 18% of the STT, the largest line.
        //
        // pct() already divides by 100, so the rate is applied directly here. Dividing again (as
        // this first did) made GST 1/100th of itself - visible only because the test pinned the
        // expected paise, since a GST of 0.00 on a 23-rupee leg looks entirely plausible.
        double gstRate   = pct("charges.gst.pct");
        double gst       = round2((brokerage + txn + sebi + ipft) * gstRate);
        double dp        = 0, dpGst = 0;
        if (product != Product.INTRADAY && side == Side.SELL && isLastDeliverySellOfDay) {
            dp    = round2(appConfig.getDouble("charges.dp.per.isin", 12.50));
            dpGst = round2(dp * gstRate);
        }
        double total = round2(brokerage + txn + sebi + ipft + stt + stamp + gst + dp + dpGst);
        return new Charges(turnover, turnover, brokerage, txn, stt, sebi, ipft, stamp,
                           gst + dpGst, dp, total);
    }

    /** Both legs of a round trip. The DP charge lands once, on the sell. */
    public Charges roundTrip(Product product, String exchange,
                             double buyPrice, double sellPrice, int quantity) {
        Charges buy  = charges(product, Side.BUY,  exchange, buyPrice,  quantity, false);
        Charges sell = charges(product, Side.SELL, exchange, sellPrice, quantity, true);
        return buy.plus(sell);
    }

    /**
     * Brokerage on one leg.
     *
     * <p>"Rs 20 or 0.03% of trade value per executed order, whichever is lower" - so it is a cap
     * and not a floor. A small intraday order pays the percentage, not Rs 20; reading it as a flat
     * fee would overcharge a Rs 5,000 order by about Rs 18.50 and make small positions look
     * uneconomic when they are not.
     */
    private double brokerage(Product product, double turnover) {
        if (product == Product.DELIVERY) {
            return round2(appConfig.getDouble("charges.equity.delivery.brokerage", 0));
        }
        double byPct = turnover * pct("charges.equity.intraday.brokerage.pct");
        double cap   = appConfig.getDouble("charges.equity.intraday.brokerage.cap", 20);
        return round2(Math.min(byPct, cap));
    }

    /** Delivery: both legs. Intraday: the sell leg only. */
    private double sttPct(Product product, Side side) {
        if (product == Product.INTRADAY) {
            return side == Side.SELL ? pct("charges.stt.intraday.sell.pct") : 0;
        }
        return pct("charges.stt.delivery.pct");
    }

    /** Buy side only, in every product. */
    private double stampPct(Product product, Side side) {
        if (side != Side.BUY) return 0;
        return product == Product.INTRADAY
            ? pct("charges.stampduty.intraday.buy.pct")
            : pct("charges.stampduty.delivery.buy.pct");
    }

    private String txnKey(String exchange) {
        return "BSE".equalsIgnoreCase(exchange == null ? "" : exchange.trim())
            ? "charges.txn.bse.pct" : "charges.txn.nse.pct";
    }

    /** Config values are held as percentages, as the pricing page states them. */
    private double pct(String key) {
        return appConfig.getDouble(key, DEFAULTS.getOrDefault(key, 0.0)) / 100.0;
    }

    private static double round2(double v)     { return Math.round(v * 100.0) / 100.0; }
    private static double roundRupee(double v) { return Math.round(v); }

    /**
     * Fallbacks used when a key is absent from Application Settings, so a fresh database computes
     * correct charges before anything has been seeded. Values are percentages except the DP charge
     * and the brokerage cap, which are rupees.
     */
    static final Map<String, Double> DEFAULTS = Map.ofEntries(
        Map.entry("charges.equity.delivery.brokerage",     0.0),
        Map.entry("charges.equity.intraday.brokerage.pct", 0.03),
        Map.entry("charges.equity.intraday.brokerage.cap", 20.0),
        Map.entry("charges.txn.nse.pct",                   0.0030699),
        Map.entry("charges.txn.bse.pct",                   0.00375),
        Map.entry("charges.stt.delivery.pct",              0.1),
        Map.entry("charges.stt.intraday.sell.pct",         0.025),
        Map.entry("charges.sebi.pct",                      0.0001),
        Map.entry("charges.stampduty.delivery.buy.pct",    0.015),
        Map.entry("charges.stampduty.intraday.buy.pct",    0.003),
        Map.entry("charges.ipft.pct",                      0.0000001),
        Map.entry("charges.gst.pct",                       18.0),
        Map.entry("charges.dp.per.isin",                   12.50)
    );

    /**
     * One leg's or one round trip's cost, itemised. Itemised rather than reduced to a total because
     * a total that disagrees with a contract note gives a reader nothing to check, and because the
     * journal has to store the breakdown (BRD section 7.2).
     */
    public record Charges(double turnover, double positionValue, double brokerage,
                          double transactionCharges, double stt, double sebiTurnoverFee,
                          double ipft, double stampDuty, double gst, double dpCharges,
                          double total) {

        static Charges zero() { return new Charges(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0); }

        /**
         * Adds a second leg. {@code turnover} sums, because both legs are taxed and the combined
         * figure is the tax base. {@code positionValue} does NOT sum: the left operand's is kept,
         * so for a round trip it stays the entry leg's value - the capital actually committed.
         * {@link #roundTrip} relies on being called entry-leg-first.
         */
        Charges plus(Charges o) {
            return new Charges(
                round(turnover + o.turnover), positionValue,
                round(brokerage + o.brokerage),
                round(transactionCharges + o.transactionCharges), round(stt + o.stt),
                round(sebiTurnoverFee + o.sebiTurnoverFee), round(ipft + o.ipft),
                round(stampDuty + o.stampDuty), round(gst + o.gst),
                round(dpCharges + o.dpCharges), round(total + o.total));
        }

        /**
         * Cost as a percentage of the capital committed - the hurdle a trade has to clear.
         *
         * <p>Measured against {@code positionValue}, not {@code turnover}. On a round trip those
         * differ by a factor of two, and dividing by the two-leg turnover would report a 0.30%
         * round trip as 0.15% - halving exactly the number that decides whether the strategy is
         * worth running.
         */
        public double totalPct() {
            return positionValue <= 0 ? 0 : Math.round((total / positionValue) * 10000.0) / 100.0;
        }

        public Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("turnover", turnover);
            m.put("positionValue", positionValue);
            m.put("brokerage", brokerage);
            m.put("transactionCharges", transactionCharges);
            m.put("stt", stt);
            m.put("sebiTurnoverFee", sebiTurnoverFee);
            m.put("ipft", ipft);
            m.put("stampDuty", stampDuty);
            m.put("gst", gst);
            m.put("dpCharges", dpCharges);
            m.put("total", total);
            m.put("totalPct", totalPct());
            return m;
        }

        private static double round(double v) { return Math.round(v * 100.0) / 100.0; }
    }
}
