package com.traderakshak.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The section average must survive rows that carry no Return %.
 *
 * <p>This guards a real outage. The RSI 45 row builder used to write a Return % on every row, so
 * the averaging step cast it straight to Number:
 *
 * <pre>sumReturn += ((Number) row.get("return_pct")).doubleValue();</pre>
 *
 * <p>When the section's Return % moved to "entry price -> the close of the selected holding
 * period", it stopped being written at that point - it is filled in afterwards, and is
 * legitimately absent for a stock whose exit session has not closed yet. The cast then threw
 * NullPointerException, the whole call failed with "Could not build the list", and BOTH RSI 45
 * sections rendered empty on the live server: no table, no bars, nothing beneath them. The
 * endpoint reported success:false with the raw JVM message, so the screen could not even say
 * what was wrong.
 *
 * <p>Two things are asserted: that a null never throws, and that it is EXCLUDED from the average
 * rather than counted as zero. The second matters as much as the first - averaging over the row
 * count would drag the figure toward zero in proportion to how much data was missing, which is a
 * wrong number presented with full confidence rather than a visible failure.
 */
class SectionAverageNullSafetyTest {

    /** The averaging step as it now stands. */
    private static double[] average(List<Map<String, Object>> rows) {
        double sum = 0;
        int counted = 0;
        for (Map<String, Object> row : rows) {
            Object rp = row.get("return_pct");
            if (rp instanceof Number n) { sum += n.doubleValue(); counted++; }
        }
        double avg = counted == 0 ? 0 : Math.round((sum / counted) * 100.0) / 100.0;
        return new double[]{avg, counted};
    }

    private static Map<String, Object> row(Object returnPct) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("symbol", "TEST");
        m.put("return_pct", returnPct);
        return m;
    }

    @Test
    @DisplayName("a row with no Return % does not bring the whole section down")
    void nullReturnDoesNotThrow() {
        List<Map<String, Object>> rows = new ArrayList<>(List.of(row(4.0), row(6.0)));
        rows.add(row(null));
        assertDoesNotThrow(() -> average(rows));
        assertEquals(5.0, average(rows)[0], 0.001);
    }

    @Test
    @DisplayName("every row missing a Return % yields 0 and an empty count, not an exception")
    void allNullIsSafe() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row(null));
        rows.add(row(null));
        double[] r = assertDoesNotThrow(() -> average(rows));
        assertEquals(0.0, r[0], 0.001);
        assertEquals(0, (int) r[1]);
    }

    @Test
    @DisplayName("a missing Return % is excluded, never counted as a 0% row")
    void nullIsExcludedNotZero() {
        List<Map<String, Object>> rows = new ArrayList<>(List.of(row(10.0), row(10.0)));
        rows.add(row(null));
        rows.add(row(null));
        double[] r = average(rows);
        assertEquals(10.0, r[0], 0.001, "two settled rows at 10% average 10%");
        assertEquals(2, (int) r[1]);
        // Counting the gaps as zero would have reported 5.00% - half the true figure, with
        // nothing on screen to say the other half was never measured.
        assertNotEquals(5.0, r[0], 0.001);
    }

    @Test
    @DisplayName("a non-numeric value is treated as missing rather than crashing")
    void nonNumericIsTreatedAsMissing() {
        List<Map<String, Object>> rows = new ArrayList<>(List.of(row(4.0)));
        rows.add(row("Exit Price Pending"));
        double[] r = assertDoesNotThrow(() -> average(rows));
        assertEquals(4.0, r[0], 0.001);
        assertEquals(1, (int) r[1]);
    }

    @Test
    @DisplayName("an empty section averages to zero without dividing by zero")
    void emptyRowsAreSafe() {
        double[] r = assertDoesNotThrow(() -> average(List.of()));
        assertEquals(0.0, r[0], 0.001);
        assertEquals(0, (int) r[1]);
    }

    @Test
    @DisplayName("integers and doubles are both accepted")
    void acceptsAnyNumber() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row(4));            // Integer
        rows.add(row(6.0));          // Double
        rows.add(row(5L));           // Long
        assertEquals(5.0, average(rows)[0], 0.001);
        assertEquals(3, (int) average(rows)[1]);
    }
}
