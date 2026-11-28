package com.traderakshak.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A counter bumped whenever the stored holiday calendar changes.
 *
 * <p>{@link StockTrackerService#isMarketHoliday} is called per date inside loops that walk a year
 * of sessions, so it reads the calendar through a short-lived in-memory cache rather than a query
 * each time. That cache then has to be told when a fetch has replaced the calendar underneath it,
 * or a refresh appears to have done nothing until the entry expires.
 *
 * <p>A separate bean rather than one service calling the other: the fetch lives in
 * {@link NseExchangeFetchService} and the cache in {@code StockTrackerService}, and wiring either
 * into the other to pass one notification would couple two large services - and, through the
 * controller that drives the fetch, risk a dependency cycle. Both depend on this leaf instead.
 */
@Component
public class HolidayCalendarVersion {

    private final AtomicLong version = new AtomicLong();

    /** Called after the calendar has been written, to retire caches built from the old one. */
    public void bump() { version.incrementAndGet(); }

    /** The current version; a cache entry stamped with a different value is stale. */
    public long get() { return version.get(); }
}
