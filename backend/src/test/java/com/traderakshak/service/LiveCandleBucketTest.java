package com.traderakshak.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a live candle starts.
 *
 * <p>This is not a formatting detail. Every price the Stock Upload section trades on is read by
 * seeking FORWARD to the first stored candle at or after a configured time, so a bar has to begin
 * exactly on the times that section buys and sells at. It buys at 15:15 and sells at 10:15 the
 * next trading day.
 *
 * <p>The original flooring was {@code withMinute((getMinute()/step)*step)}. At a 5-minute width it
 * agreed with the exchange by coincidence - 09:15 is itself a multiple of 5. At 60 it floors to the
 * top of the hour and produces 09:00, 10:00 ... 15:00, which breaks two things at once and neither
 * loudly: those stamps do not match Dhan's own 60-minute candles (09:15, 10:15 ... 15:15), so the
 * upsert key stops colliding and a day ends up holding two parallel sets of rows; and there is no
 * bar beginning at 15:15, so the forward seek for a 15:15 buy finds nothing and the trade is
 * dropped. Hence anchoring on the session open, and hence this test.
 */
class LiveCandleBucketTest {

    /** intervalMinutes is @Value-injected, so set it the way Spring would. */
    private static LiveCandleAggregator aggregatorWithInterval(int minutes) throws Exception {
        LiveCandleAggregator agg = new LiveCandleAggregator(null, null, null);
        Field f = LiveCandleAggregator.class.getDeclaredField("intervalMinutes");
        f.setAccessible(true);
        f.setInt(agg, minutes);
        return agg;
    }

    private static LocalTime bucketOf(LiveCandleAggregator agg, String hhmmss) {
        return agg.bucketStart(LocalDateTime.parse("2026-09-07T" + hhmmss)).toLocalTime();
    }

    @Test
    void hourlyBarsStartOnTheSessionOpenNotTheTopOfTheHour() throws Exception {
        LiveCandleAggregator agg = aggregatorWithInterval(60);

        assertEquals(LocalTime.of(9, 15), bucketOf(agg, "09:15:00"), "the open is its own bucket");
        assertEquals(LocalTime.of(9, 15), bucketOf(agg, "10:14:59"), "still inside the first hour");
        assertEquals(LocalTime.of(10, 15), bucketOf(agg, "10:15:00"), "the sell leg's bar");
        assertEquals(LocalTime.of(12, 15), bucketOf(agg, "12:40:11"), "mid-session");
        assertEquals(LocalTime.of(14, 15), bucketOf(agg, "15:14:59"), "one second short of the buy");
        assertEquals(LocalTime.of(15, 15), bucketOf(agg, "15:15:00"), "the buy leg's bar");
        assertEquals(LocalTime.of(15, 15), bucketOf(agg, "15:29:59"), "the short final bar");
    }

    @Test
    void aFullSessionProducesSevenHourlyBucketsAndBothTradeLegsAreAmongThem() throws Exception {
        LiveCandleAggregator agg = aggregatorWithInterval(60);

        TreeSet<LocalTime> starts = new TreeSet<>();
        for (LocalDateTime ts = LocalDateTime.parse("2026-09-07T09:15:00");
             !ts.toLocalTime().isAfter(LocalTime.of(15, 29)); ts = ts.plusSeconds(37)) {
            starts.add(agg.bucketStart(ts).toLocalTime());
        }

        assertEquals(7, starts.size(), "09:15 to 15:30 is six full hours plus a short final bar");
        assertEquals(LocalTime.of(9, 15), starts.first());
        assertEquals(LocalTime.of(15, 15), starts.last());
        assertTrue(starts.contains(LocalTime.of(10, 15)),
            "the 10:15 sell must land on a bar start, or the forward seek prices off a later bar");
        assertTrue(starts.contains(LocalTime.of(15, 15)),
            "the 15:15 buy must land on a bar start, or the forward seek finds no candle at all");
    }

    @Test
    void theOldFiveMinuteWidthStillBucketsTheWayItAlwaysDid() throws Exception {
        LiveCandleAggregator agg = aggregatorWithInterval(5);

        // Anchoring on the open must not have changed the behaviour that was already correct:
        // at 5 minutes the session open is itself on the grid, so these are unchanged.
        assertEquals(LocalTime.of(9, 15), bucketOf(agg, "09:17:30"));
        assertEquals(LocalTime.of(9, 20), bucketOf(agg, "09:20:00"));
        assertEquals(LocalTime.of(15, 25), bucketOf(agg, "15:29:59"));
    }

    @Test
    void ticksBeforeTheOpenCannotLandInYesterdaysBucket() throws Exception {
        LiveCandleAggregator agg = aggregatorWithInterval(60);

        // Pre-open ticks are ignored by the caller, but bucketStart is also used to compute the
        // flush cut-off. Returning the open rather than a negative offset keeps that cut-off on
        // today, so bars left over from the previous session are flushed instead of resurrected.
        assertEquals(LocalTime.of(9, 15), bucketOf(agg, "08:00:00"));
        assertEquals(LocalTime.of(9, 15), bucketOf(agg, "00:00:00"));
    }
}
