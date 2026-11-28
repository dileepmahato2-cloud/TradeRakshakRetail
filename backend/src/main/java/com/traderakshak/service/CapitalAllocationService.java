package com.traderakshak.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Splits a total investment equally across the selected stocks, in whole shares.
 *
 * <p>Deliberately a service rather than a few lines in a controller, because the numbers have to
 * agree in three places - the screen as boxes are ticked, the order preview, and the order itself
 * immediately before submission - and three implementations of "divide by five and floor" is three
 * chances to disagree about the one figure that must not be wrong.
 *
 * <p><b>Why the recalculation before submission matters.</b> A quantity computed from the price
 * shown when a box was ticked can overshoot available capital by the time Submit is pressed,
 * because the price moved in between. So the allocation is a pure function of (total, selection,
 * current prices) and is simply re-run against a fresh price rather than cached.
 *
 * <p><b>Residual is reported, never absorbed.</b> Flooring five whole-share quantities out of a
 * fixed total always leaves a remainder. Topping one position up to consume it would break the
 * equal-split rule the specification states, so the leftover is shown as residual cash.
 */
@Service
public class CapitalAllocationService {

    private final BrokerageCalculator charges;

    public CapitalAllocationService(BrokerageCalculator charges) {
        this.charges = charges;
    }

    /**
     * Write each stock's share onto the rows the strategy already produced.
     *
     * <p>Deliberately an annotation of the existing list rather than a list of its own. The
     * allocation belongs beside the stock it is for: a second table would have to be kept in step
     * with the first, and the moment the two disagree - a stock qualifying in one and not the
     * other - neither can be trusted. So this adds columns, never rows, and never reorders or
     * removes anything the entry test put there.
     *
     * <p>Quantity comes from the price a trade would actually open at: the row's entry price when
     * it has one, otherwise its current price. Whole shares only - equity delivery has no
     * fractional quantity - which is why a stock priced above its own share of the capital
     * receives nothing and says so rather than rounding up into an order that cannot be funded.
     *
     * @param rows      the qualified stocks, annotated in place.
     * @param investment what the user chose to deploy.
     * @param available  the account's buying power. An allocation that would exceed it is
     *                   refused outright rather than trimmed, because quietly reducing an order
     *                   someone asked for is worse than declining it with a reason.
     * @return a summary for the panel: deployed, residual, charges, and how many stocks were
     *         actually funded.
     */
    public Map<String, Object> attachToRows(List<Map<String, Object>> rows,
                                            double investment, double available,
                                            BrokerageCalculator.Product product,
                                            String exchange) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (rows == null || rows.isEmpty() || investment <= 0) {
            summary.put("allocated", false);
            summary.put("reason", rows == null || rows.isEmpty()
                ? "No qualifying stocks to allocate across."
                : "No investment amount set.");
            return summary;
        }

        // Selection order is the strategy's order, so the allocation follows the same ranking the
        // table shows rather than imposing one of its own.
        Map<String, Double> prices = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String sym = String.valueOf(r.get("symbol"));
            double px = priceFor(r);
            if (px > 0) prices.put(sym, px);
        }

        Map<String, Object> result = allocate(investment, available, prices, product, exchange);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> legs = (List<Map<String, Object>>) result.getOrDefault("legs", List.of());

        Map<String, Map<String, Object>> bySymbol = new LinkedHashMap<>();
        for (Map<String, Object> leg : legs) bySymbol.put(String.valueOf(leg.get("symbol")), leg);

        int funded = 0;
        for (Map<String, Object> r : rows) {
            Map<String, Object> leg = bySymbol.get(String.valueOf(r.get("symbol")));
            double px = priceFor(r);

            if (leg == null) {
                r.put("allocated_amount", 0.0);
                r.put("quantity", 0);
                r.put("estimated_investment", 0.0);
                r.put("allocation_status", px > 0
                    ? "Not allocated" : "No price available, so no quantity could be calculated.");
                continue;
            }

            int qty = leg.get("quantity") instanceof Number n ? n.intValue() : 0;
            double allocated = num(leg.get("allocated"));
            double deployed = num(leg.get("deployed"));
            double charges = num(leg.get("estimatedCharges"));

            r.put("allocated_amount", allocated);
            r.put("quantity", qty);
            // What leaving this order actually costs: the shares plus the charges to get in and
            // out, which is the figure that has to fit inside the balance.
            r.put("estimated_investment", round2(deployed + charges));
            r.put("entry_price_used", px);
            r.put("allocation_charges", charges);
            if (qty > 0) {
                funded++;
                r.put("allocation_status", "Allocated");
            } else {
                String note = leg.get("note") == null ? "" : String.valueOf(leg.get("note"));
                r.put("allocation_status", note.isBlank()
                    ? String.format("Share of %.2f is below one share at %.2f.", allocated, px)
                    : note);
            }
        }

        summary.put("allocated", Boolean.TRUE.equals(result.get("success")));
        summary.put("investment", result.get("totalInvestment"));
        summary.put("available_balance", result.get("availableBalance"));
        summary.put("deployed", result.get("deployedCapital"));
        summary.put("residual", result.get("residualCash"));
        summary.put("balance_after", result.get("balanceAfter"));
        summary.put("estimated_charges", result.get("estimatedRoundTripCharges"));
        summary.put("funded_stocks", funded);
        summary.put("considered_stocks", rows.size());
        if (!Boolean.TRUE.equals(result.get("success"))) {
            summary.put("reason", result.getOrDefault("message",
                "The allocation would exceed the available balance."));
        }
        return summary;
    }

    /** The price a trade would open at: the row's entry price, else its current price. */
    private static double priceFor(Map<String, Object> row) {
        double entry = num(row.get("entry_price"));
        if (entry > 0) return entry;
        return num(row.get("current_price"));
    }

    private static double num(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }

    /** One stock's share of the total. */
    public record Leg(String symbol, double price, int quantity, double allocated,
                      double deployed, double residual, double estimatedCharges, String note) {}

    /**
     * @param total        the amount to split.
     * @param available    the user's available funds. The allocation is blocked, not silently
     *                     reduced, when the deployed total would exceed it - quietly trimming an
     *                     order the user asked for is worse than refusing it with a reason.
     * @param prices       latest executable price per symbol, in selection order.
     * @param product      delivery for a close-to-close hold. Decides the charge schedule, and the
     *                     difference is not cosmetic: delivery pays no brokerage but STT on both
     *                     legs, intraday the reverse.
     */
    public Map<String, Object> allocate(double total, double available,
                                        Map<String, Double> prices,
                                        BrokerageCalculator.Product product, String exchange) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Leg> legs = new ArrayList<>();
        List<String> selected = prices == null ? List.of() : new ArrayList<>(prices.keySet());

        int n = selected.size();
        if (n == 0 || total <= 0) {
            out.put("success", false);
            out.put("legs", List.of());
            out.put("message", n == 0
                ? "Select at least one stock to allocate across."
                : "Enter a total investment amount greater than zero.");
            out.put("selectedCount", n);
            out.put("totalInvestment", round2(total));
            return out;
        }

        // Equal split, as specified. Deselecting a stock redistributes across the remainder simply
        // by calling this again with a shorter selection - there is no incremental state to drift.
        double perStock = round2(total / n);
        double deployed = 0, totalCharges = 0;
        int priced = 0;

        for (String symbol : selected) {
            double price = prices.getOrDefault(symbol, 0.0);
            if (price <= 0) {
                // Zero quantity with a stated reason, not a silent omission: a stock in the
                // selection that cannot be priced has to be visible, or the user is left to work
                // out why four stocks consumed a five-way split.
                legs.add(new Leg(symbol, 0, 0, perStock, 0, perStock, 0,
                    "No live price available, so no quantity could be calculated."));
                continue;
            }
            priced++;
            int qty = (int) Math.floor(perStock / price);
            double legDeployed = round2(qty * price);
            double legResidual = round2(perStock - legDeployed);
            double legCharges = qty > 0
                ? charges.roundTrip(product, exchange, price, price, qty).total() : 0;
            String note = qty == 0
                // Said plainly rather than rounded up to one share. Rounding up would exceed the
                // per-stock allocation, and on a five-way split of a small total that compounds.
                ? "One share costs " + round2(price) + ", which is more than the "
                  + round2(perStock) + " allocated to this stock."
                : "";
            legs.add(new Leg(symbol, round2(price), qty, perStock, legDeployed, legResidual,
                             round2(legCharges), note));
            deployed += legDeployed;
            totalCharges += legCharges;
        }

        deployed = round2(deployed);
        totalCharges = round2(totalCharges);
        double residual = round2(total - deployed);
        boolean affordable = deployed <= available;

        out.put("success", affordable);
        out.put("legs", legs.stream().map(CapitalAllocationService::legMap).toList());
        out.put("totalInvestment", round2(total));
        out.put("selectedCount", n);
        out.put("pricedCount", priced);
        out.put("perStock", perStock);
        out.put("availableBalance", round2(available));
        out.put("deployedCapital", deployed);
        out.put("residualCash", residual);
        out.put("balanceAfter", round2(available - deployed));
        out.put("product", product.name());
        // Charges on the WHOLE round trip, shown before the order rather than discovered after it.
        // A one-session hold pays roughly 0.30% to go in and out, so this is the hurdle the trade
        // has to clear, and the per-ISIN DP fee makes it proportionally heavier on small positions.
        out.put("estimatedRoundTripCharges", totalCharges);
        out.put("estimatedChargesPct", deployed > 0
            ? Math.round((totalCharges / deployed) * 10000.0) / 100.0 : 0);
        out.put("netDeployed", round2(deployed + totalCharges));

        if (!affordable) {
            out.put("message", "This allocation needs " + deployed + " but only "
                + round2(available) + " is available - short by "
                + round2(deployed - available) + ". Reduce the total or deselect a stock.");
        } else if (priced < n) {
            out.put("message", (n - priced) + " of " + n + " selected stocks have no live price, "
                + "so no quantity was calculated for them.");
        }
        return out;
    }

    private static Map<String, Object> legMap(Leg l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("symbol", l.symbol());
        m.put("price", l.price());
        m.put("quantity", l.quantity());
        m.put("allocated", l.allocated());
        m.put("deployed", l.deployed());
        m.put("residual", l.residual());
        m.put("estimatedCharges", l.estimatedCharges());
        m.put("note", l.note());
        return m;
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
