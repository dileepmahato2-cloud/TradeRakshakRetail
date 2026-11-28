package com.traderakshak.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Which session a panel answers for.
 *
 * <p>Two readings, both wanted, and picking the wrong one is silent.
 *
 * <ul>
 *   <li>A <b>holding</b> panel shows what was selected at the previous session's cut-off,
 *       because a stock that crosses on Friday is chosen at Friday's cut-off and only becomes a
 *       held position on Monday. Evaluating the newest session for both made the provisional
 *       list and the holding list identical, with nothing ever moving between them.</li>
 *   <li>A <b>screen</b> - "every stock that qualifies" - means now. Showing it a session behind
 *       is how a stock crossing today reads as not qualifying, which is how this was
 *       reported.</li>
 * </ul>
 *
 * <p>The arithmetic is pinned here rather than only in the service, because it is three lines
 * that decide what every row on two screens is about, and an off-by-one in it produces a
 * perfectly plausible list of the wrong day.
 */
class SessionSelectionTest {

    /** The service's choice, extracted so it can be exercised without a database. */
    private static String asOf(List<String> days, boolean useNewestSession) {
        boolean haveTwo = days.size() > 1;
        return (useNewestSession || !haveTwo)
            ? days.get(days.size() - 1)
            : days.get(days.size() - 2);
    }

    private static String prevDay(List<String> days, boolean useNewestSession) {
        boolean haveTwo = days.size() > 1;
        return useNewestSession
            ? (haveTwo ? days.get(days.size() - 2) : null)
            : (days.size() > 2 ? days.get(days.size() - 3) : null);
    }

    /** Trading days as stored today: note 24, 25 and 26 November are absent. */
    private static final List<String> STORED = List.of(
        "2026-11-17", "2026-11-18", "2026-11-19", "2026-11-20", "2026-11-23");

    @Test
    void aScreenAnswersForTheNewestStoredSession() {
        assertEquals("2026-11-23", asOf(STORED, true));
        assertEquals("2026-11-20", prevDay(STORED, true),
            "the screen's previous close must be the session before the one it evaluates");
    }

    @Test
    void aHoldingPanelAnswersForTheSessionBeforeThat() {
        assertEquals("2026-11-20", asOf(STORED, false));
        assertEquals("2026-11-19", prevDay(STORED, false));
    }

    @Test
    void theTwoReadingsAreOneSessionApart() {
        int screen = STORED.indexOf(asOf(STORED, true));
        int holding = STORED.indexOf(asOf(STORED, false));
        assertEquals(1, screen - holding,
            "the screen and the holding panel drifted apart by more than one session");
    }

    @Test
    void aSingleStoredSessionIsUsedByBothRatherThanFailing() {
        // One session: there is no "session before", and blanking the panel would be worse than
        // answering for the only day there is.
        List<String> one = List.of("2026-11-23");
        assertEquals("2026-11-23", asOf(one, true));
        assertEquals("2026-11-23", asOf(one, false));
        assertNull(prevDay(one, true));
        assertNull(prevDay(one, false));
    }

    @Test
    void twoStoredSessionsStillGiveTheHoldingPanelAPreviousClose() {
        List<String> two = List.of("2026-11-20", "2026-11-23");
        assertEquals("2026-11-20", asOf(two, false));
        assertNull(prevDay(two, false), "there is no session before the first one stored");
        assertEquals("2026-11-23", asOf(two, true));
        assertEquals("2026-11-20", prevDay(two, true));
    }

    /**
     * The GODREJCP case, as the data actually stood.
     *
     * <p>base price 919.50, which is exactly the 20 November close. The entry test needs the
     * previous close strictly BELOW the base price, so it can only hold once the previous close
     * is a session after the 20th that closed lower - the 23rd, at 912.30.
     *
     * <p>That requires the evaluated session to be the 24th or later. Those candles do not
     * exist, so no choice of stored session can identify this stock: moving the screen to the
     * newest stored session is necessary and not sufficient, and the remaining gap is data, not
     * logic. Pinned so the distinction is not re-litigated from the code.
     */
    @Test
    void godrejcpNeedsASessionThatIsNotStoredYet() {
        double base = 919.50;
        double close20Nov = 919.50, close19Nov = 929.50, close23Nov = 912.30;

        // Holding reading: evaluates the 20th, so the previous close is the 19th.
        assertEquals(false, close19Nov < base,
            "the 19 November close was below the base price, which is not what the data says");

        // Screen reading: evaluates the 23rd, so the previous close is the 20th - equal to the
        // base price, and the test requires strictly below.
        assertEquals(false, close20Nov < base);

        // Only with the 24th or later evaluated does the previous close become the 23rd.
        assertEquals(true, close23Nov < base,
            "once 24 November is stored, GODREJCP's previous close is 912.30 and the crossing "
          + "holds");
    }
}
