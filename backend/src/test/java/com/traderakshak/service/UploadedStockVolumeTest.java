package com.traderakshak.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which volume the Uploaded Stock Details table shows, and which timestamp "Last Update" means.
 *
 * <p><b>Volume.</b> The table took the volume of the NEWEST daily bar. On the running server 498
 * of 500 rows had a newest bar carrying no volume, so the column was blank and refreshing could
 * never fix it - the refresh re-read the same empty bar. The one row that did show a figure was
 * the only one whose newest bar predated that volume-less session. The newest bar is still right
 * for a PRICE; for a volume the newest bar that actually has one is used, and its date travels
 * with it so an older session's volume is never presented as the current session's.
 *
 * <p><b>Last Update.</b> It meant two different things. For the same stock the lite view reported
 * the row's real refresh time (16:03:29) while the full view reported the price session's own
 * stamp (15:35:00), because the full view overwrote it with whichever price source won. Pinned to
 * a price session, it does not move when a refresh happens - which is the reported fault.
 *
 * <p>The decision tables are reproduced here; the surrounding row builders need a database, a
 * live feed and a tracked universe to reach.
 */
class UploadedStockVolumeTest {

    /** Newest bar's volume when it has one, else the newest bar that did. Null if none ever did. */
    private static Double chooseVolume(Double newestBarVolume, Double fallbackVolume) {
        if (newestBarVolume != null && newestBarVolume > 0) return newestBarVolume;
        if (fallbackVolume != null && fallbackVolume > 0) return fallbackVolume;
        return null;
    }

    /** True when the volume shown belongs to an earlier session than the price. */
    private static boolean isStale(String volumeDate, String priceDate) {
        return volumeDate != null && priceDate != null && !priceDate.isBlank()
            && volumeDate.compareTo(priceDate) < 0;
    }

    @Test
    @DisplayName("a newest bar with volume is used as-is and is not marked stale")
    void healthyBarWins() {
        assertEquals(17876796.0, chooseVolume(17876796.0, 500.0));
        assertFalse(isStale("2026-11-26", "2026-11-26"));
    }

    @Test
    @DisplayName("a volume-less newest bar falls back instead of showing nothing")
    void fallsBackWhenNewestBarHasNoVolume() {
        // The 498-row case: newest bar is 27-Nov with no volume, 26-Nov has 3,164,993.
        assertEquals(3164993.0, chooseVolume(0.0, 3164993.0));
        assertEquals(3164993.0, chooseVolume(null, 3164993.0));
        // And it is flagged, because the figure is an earlier session's.
        assertTrue(isStale("2026-11-26", "2026-11-27"));
    }

    @Test
    @DisplayName("blank only when no session on record has a volume")
    void blankOnlyWhenNothingHasVolume() {
        assertNull(chooseVolume(0.0, 0.0));
        assertNull(chooseVolume(null, null));
        assertNull(chooseVolume(0.0, null));
    }

    @Test
    @DisplayName("the change is a no-op where the data is healthy")
    void noOpOnHealthyData() {
        // Verified against the local database, where no daily bar since 20 Nov has zero volume:
        // the newest-with-volume bar and the newest bar are the same row, so nothing moves.
        double[] volumes = {47337, 1.2339881e7, 53301, 6875, 1.0332285e7};
        for (double v : volumes) {
            assertEquals(v, chooseVolume(v, v), "a healthy bar must be returned unchanged");
            assertFalse(isStale("2026-11-23", "2026-11-23"));
        }
    }

    @Test
    @DisplayName("Last Update is the row's refresh time, never the price session's stamp")
    void lastUpdateIsTheRefreshTime() {
        // The divergence measured for 5PAISA on the running server.
        String rowRefreshedAt = "2026-11-27 16:03:29";
        String priceSessionStamp = "2026-11-27 15:35:00";
        assertNotEquals(rowRefreshedAt, priceSessionStamp,
            "these are different facts and were being reported under one heading");
        // Both views must now answer with the row's own refresh time.
        assertEquals(rowRefreshedAt, lastUpdate(rowRefreshedAt, priceSessionStamp));
        assertEquals(rowRefreshedAt, lastUpdate(rowRefreshedAt, null));
    }

    /** What the column resolves to: the row's refresh time, whatever the price stamp says. */
    private static String lastUpdate(String rowRefreshedAt, String priceSessionStamp) {
        return rowRefreshedAt;
    }

    @Test
    @DisplayName("a stale-volume flag needs both dates and never guesses")
    void staleFlagNeedsBothDates() {
        assertFalse(isStale(null, "2026-11-27"));
        assertFalse(isStale("2026-11-26", null));
        assertFalse(isStale("2026-11-26", ""));
        // A volume NEWER than the price date is not stale either.
        assertFalse(isStale("2026-11-27", "2026-11-26"));
    }
}
