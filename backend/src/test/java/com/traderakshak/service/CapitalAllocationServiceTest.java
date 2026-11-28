package com.traderakshak.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Equal-split capital allocation.
 *
 * <p>The specification's own worked examples are pinned first (a lakh across five, then across
 * four), then the cases it does not mention but that decide whether the thing is safe: a share
 * costing more than its allocation, a selection that would overdraw the account, and a price that
 * moves between preview and submission.
 */
class CapitalAllocationServiceTest {

    private CapitalAllocationService alloc;

    @BeforeEach
    void setUp() {
        AppConfigService config = mock(AppConfigService.class);
        when(config.getDouble(anyString(), anyDouble())).thenAnswer(i -> i.getArgument(1));
        alloc = new CapitalAllocationService(new BrokerageCalculator(config));
    }

    private static Map<String, Double> prices(Object... pairs) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((String) pairs[i], ((Number) pairs[i + 1]).doubleValue());
        }
        return m;
    }

    private Map<String, Object> run(double total, double available, Map<String, Double> p) {
        return alloc.allocate(total, available, p,
                              BrokerageCalculator.Product.DELIVERY, "NSE");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> legs(Map<String, Object> out) {
        return (List<Map<String, Object>>) out.get("legs");
    }

    @Test
    void splitsALakhEquallyAcrossFiveStocks() {
        var out = run(100_000, 500_000, prices("A", 200, "B", 100, "C", 400, "D", 50, "E", 250));

        assertEquals(20_000.0, out.get("perStock"), "the specification's own example");
        assertEquals(5, out.get("selectedCount"));
        assertEquals(100, legs(out).get(0).get("quantity"), "20,000 / 200");
        assertEquals(200, legs(out).get(1).get("quantity"), "20,000 / 100");
        assertEquals(50,  legs(out).get(2).get("quantity"), "20,000 / 400");
        assertTrue((Boolean) out.get("success"));
    }

    @Test
    void redistributesAcrossTheRemainderWhenAStockIsDeselected() {
        // The specification's second example: deselect one of five and the rest get 25,000 each.
        var out = run(100_000, 500_000, prices("A", 200, "B", 100, "C", 400, "D", 50));

        assertEquals(25_000.0, out.get("perStock"));
        assertEquals(4, out.get("selectedCount"));
        assertEquals(125, legs(out).get(0).get("quantity"), "25,000 / 200");
    }

    @Test
    void usesWholeSharesAndReportsTheRemainderRatherThanAbsorbingIt() {
        // 20,000 / 333 is 60.06 shares. Sixty shares cost 19,980, leaving 20 unspent.
        var out = run(100_000, 500_000,
                      prices("A", 333, "B", 333, "C", 333, "D", 333, "E", 333));

        assertEquals(60, legs(out).get(0).get("quantity"), "floored, never rounded up");
        assertEquals(99_900.0, out.get("deployedCapital"));
        assertEquals(100.0, out.get("residualCash"),
            "the leftover is shown, not pushed into one position - that would break the equal split");
    }

    @Test
    void aShareCostingMoreThanItsAllocationGetsZeroQuantityAndSaysSo() {
        // 1,000 across five is 200 each, against a 4,111-rupee share. Rounding up to one share
        // would spend twenty times the allocation.
        var out = run(1_000, 500_000,
                      prices("A", 4_111, "B", 10, "C", 10, "D", 10, "E", 10));

        assertEquals(0, legs(out).get(0).get("quantity"));
        assertTrue(String.valueOf(legs(out).get(0).get("note")).contains("4111"),
            "the reason is stated on the row rather than left as a bare zero");
        assertEquals(20, legs(out).get(1).get("quantity"), "the other legs are unaffected");
    }

    @Test
    void blocksAnAllocationThatWouldExceedAvailableFunds() {
        var out = run(100_000, 30_000, prices("A", 100, "B", 100));

        assertFalse((Boolean) out.get("success"));
        assertEquals(100_000.0, out.get("deployedCapital"));
        assertTrue(String.valueOf(out.get("message")).contains("short by"),
            "refused with the shortfall named, not silently trimmed to what fits");
    }

    @Test
    void recalculationAgainstAMovedPriceChangesTheQuantity() {
        // Why the allocation is re-run immediately before submission rather than cached: the same
        // inputs with a higher price buy fewer shares, and a stale quantity can overdraw.
        var before = run(100_000, 100_000, prices("A", 200, "B", 200, "C", 200, "D", 200, "E", 200));
        var after  = run(100_000, 100_000, prices("A", 250, "B", 250, "C", 250, "D", 250, "E", 250));

        assertEquals(100, legs(before).get(0).get("quantity"));
        assertEquals(80,  legs(after).get(0).get("quantity"));
        assertTrue((Double) after.get("deployedCapital") <= 100_000.0,
            "a recalculated allocation must still fit inside the total");
    }

    @Test
    void anUnpricedStockStillConsumesItsShareAndIsVisible() {
        // It must not silently drop out: a four-stock result from a five-stock selection, with no
        // explanation, reads as a bug in the split.
        var out = run(100_000, 500_000, prices("A", 200, "B", 0, "C", 200, "D", 200, "E", 200));

        assertEquals(5, out.get("selectedCount"));
        assertEquals(4, out.get("pricedCount"));
        assertEquals(0, legs(out).get(1).get("quantity"));
        assertTrue(String.valueOf(legs(out).get(1).get("note")).contains("No live price"));
        assertTrue(String.valueOf(out.get("message")).contains("no live price"));
    }

    @Test
    void reportsTheRoundTripChargeAsAPercentageOfWhatIsDeployed() {
        var out = run(100_000, 500_000, prices("A", 200, "B", 200, "C", 200, "D", 200, "E", 200));

        assertEquals(100_000.0, out.get("deployedCapital"));
        // Five legs of 20,000, each costing about 59.23 to go in and out.
        assertEquals(296.15, (Double) out.get("estimatedRoundTripCharges"), 0.5);
        assertEquals(0.30, (Double) out.get("estimatedChargesPct"), 0.01,
            "the hurdle a one-session hold has to clear, shown before the order not after it");
    }

    @Test
    void theFlatDpFeeMakesSmallAllocationsProportionallyDearer() {
        var small = run(10_000, 500_000, prices("A", 200, "B", 200, "C", 200, "D", 200, "E", 200));
        var large = run(500_000, 900_000, prices("A", 200, "B", 200, "C", 200, "D", 200, "E", 200));

        assertTrue((Double) small.get("estimatedChargesPct") > (Double) large.get("estimatedChargesPct"),
            "Rs 12.50 per ISIN is the same fee on a 2,000 position as on a 100,000 one");
    }

    @Test
    void handlesNoSelectionAndNoAmountWithoutThrowing() {
        assertFalse((Boolean) run(100_000, 500_000, prices()).get("success"));
        assertFalse((Boolean) run(0, 500_000, prices("A", 200)).get("success"));
        assertFalse((Boolean) alloc.allocate(100_000, 500_000, null,
                                             BrokerageCalculator.Product.DELIVERY, "NSE")
                                   .get("success"));
    }
}
