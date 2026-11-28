package com.traderakshak.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Allocation written onto the rows the strategy produced, rather than into a list of its own.
 *
 * <p>A second table would have to be kept in step with the first, and the moment the two disagree
 * - a stock qualifying in one and not the other - neither can be trusted. So the properties worth
 * pinning are the ones that make it an annotation: the same rows come back, in the same order,
 * with columns added and nothing removed.
 *
 * <p>The arithmetic matters too. Equity delivery has no fractional share, so a stock priced above
 * its own share of the capital must receive nothing and say why, rather than rounding up into an
 * order that cannot be funded.
 */
class InlineAllocationTest {

    private CapitalAllocationService allocation;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        // Charges default to their configured values, as in CapitalAllocationServiceTest.
        AppConfigService config = org.mockito.Mockito.mock(AppConfigService.class);
        org.mockito.Mockito.when(config.getDouble(org.mockito.ArgumentMatchers.anyString(),
                                                  org.mockito.ArgumentMatchers.anyDouble()))
            .thenAnswer(i -> i.getArgument(1));
        allocation = new CapitalAllocationService(new BrokerageCalculator(config));
    }

    private static Map<String, Object> row(String symbol, double currentPrice) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("symbol", symbol);
        r.put("current_price", currentPrice);
        r.put("base_price", currentPrice * 0.98);
        return r;
    }

    private static List<Map<String, Object>> rows(Object... pairs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.add(row((String) pairs[i], ((Number) pairs[i + 1]).doubleValue()));
        }
        return out;
    }

    private Map<String, Object> allocate(List<Map<String, Object>> rows, double investment,
                                         double available) {
        return allocation.attachToRows(rows, investment, available,
            BrokerageCalculator.Product.DELIVERY, "NSE");
    }

    @Test
    void theSameRowsComeBackWithColumnsAdded() {
        List<Map<String, Object>> list = rows("AAA", 100.0, "BBB", 200.0, "CCC", 50.0);
        allocate(list, 30_000, 1_000_000);

        assertEquals(3, list.size(), "no row may be added or removed");
        assertEquals(List.of("AAA", "BBB", "CCC"),
            list.stream().map(r -> String.valueOf(r.get("symbol"))).toList(),
            "and the strategy's order is preserved");
        for (Map<String, Object> r : list) {
            assertTrue(r.containsKey("allocated_amount"));
            assertTrue(r.containsKey("quantity"));
            assertTrue(r.containsKey("estimated_investment"));
            assertTrue(r.containsKey("allocation_status"));
            assertTrue(r.containsKey("base_price"), "existing columns survive");
        }
    }

    @Test
    void quantitiesAreWholeSharesAndFitTheirShare() {
        List<Map<String, Object>> list = rows("AAA", 100.0, "BBB", 200.0);
        allocate(list, 10_000, 1_000_000);   // 5,000 each

        assertEquals(50, list.get(0).get("quantity"), "5,000 / 100");
        assertEquals(25, list.get(1).get("quantity"), "5,000 / 200");
        for (Map<String, Object> r : list) {
            int qty = (Integer) r.get("quantity");
            double px = (Double) r.get("entry_price_used");
            double share = (Double) r.get("allocated_amount");
            assertTrue(qty * px <= share + 0.001,
                r.get("symbol") + ": " + qty + " x " + px + " must fit inside " + share);
        }
    }

    @Test
    void aStockDearerThanItsShareGetsNothingAndSaysWhy() {
        // One share of BBB costs more than its slice of the capital. Rounding up would create an
        // order that cannot be funded; rounding down to zero without explanation would look like
        // the screen had simply skipped it.
        List<Map<String, Object>> list = rows("AAA", 100.0, "BBB", 9_000.0);
        allocate(list, 2_000, 1_000_000);   // 1,000 each

        assertEquals(10, list.get(0).get("quantity"));
        assertEquals(0, list.get(1).get("quantity"));
        String status = String.valueOf(list.get(1).get("allocation_status"));
        assertFalse(status.isBlank(), "a zero quantity must carry a reason");
        assertFalse("Allocated".equals(status));
    }

    @Test
    void aRowWithNoPriceIsReportedNotGuessed() {
        List<Map<String, Object>> list = rows("AAA", 100.0);
        list.add(row("NOPRICE", 0.0));

        allocate(list, 10_000, 1_000_000);

        assertEquals(0, list.get(1).get("quantity"));
        assertTrue(String.valueOf(list.get(1).get("allocation_status")).toLowerCase()
                .contains("price"),
            "the reason must name the missing price: " + list.get(1).get("allocation_status"));
    }

    @Test
    void anAllocationBeyondTheBalanceIsRefusedNotTrimmed() {
        // Quietly reducing an order someone asked for is worse than declining it with a reason:
        // the user would act on a position size they never chose.
        List<Map<String, Object>> list = rows("AAA", 100.0, "BBB", 200.0);
        Map<String, Object> summary = allocate(list, 500_000, 10_000);

        assertEquals(Boolean.FALSE, summary.get("allocated"));
        assertTrue(summary.containsKey("reason"), "and it must say why: " + summary);
    }

    @Test
    void nothingIsWrittenWhenNoInvestmentIsSet() {
        List<Map<String, Object>> list = rows("AAA", 100.0);
        Map<String, Object> summary = allocate(list, 0, 1_000_000);

        assertEquals(Boolean.FALSE, summary.get("allocated"));
        assertFalse(list.get(0).containsKey("quantity"),
            "an unset investment must leave the list exactly as the strategy produced it");
    }

    @Test
    void totalDeployedNeverExceedsTheInvestment() {
        List<Map<String, Object>> list = rows("AAA", 137.0, "BBB", 913.0, "CCC", 44.5, "DDD", 2_210.0);
        Map<String, Object> summary = allocate(list, 50_000, 1_000_000);

        double deployed = 0;
        for (Map<String, Object> r : list) {
            deployed += (Integer) r.get("quantity") * (Double) r.get("entry_price_used");
        }
        assertTrue(deployed <= 50_000.001, "deployed " + deployed + " must fit the investment");
        assertEquals(4, list.size());
        assertTrue(((Number) summary.get("funded_stocks")).intValue() > 0);
    }
}
