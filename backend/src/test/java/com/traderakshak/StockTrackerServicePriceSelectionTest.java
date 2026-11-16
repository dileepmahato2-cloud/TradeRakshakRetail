package com.traderakshak;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traderakshak.service.BlockingHttpService;
import com.traderakshak.service.DhanBrokerService;
import com.traderakshak.service.AppConfigService;
import com.traderakshak.service.LivePriceBroadcaster;
import com.traderakshak.service.MarketCalendarService;
import com.traderakshak.service.StockTrackerService;
import com.traderakshak.service.StockUploadHistoryService;
import com.traderakshak.service.TechnicalIndicatorEngine;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class StockTrackerServicePriceSelectionTest {

    @Test
    void staleMarketCloseCannotReplaceNewerTrackedPrice() throws Exception {
        StockTrackerService service = new StockTrackerService(
            mock(TechnicalIndicatorEngine.class),
            mock(MarketCalendarService.class),
            new ObjectMapper(),
            mock(BlockingHttpService.class),
            mock(DhanBrokerService.class),
            mock(PlatformTransactionManager.class),
            mock(AppConfigService.class),
            mock(LivePriceBroadcaster.class),
            mock(StockUploadHistoryService.class)
        );

        Method shouldUseNewerPrice = StockTrackerService.class
            .getDeclaredMethod("shouldUseNewerPrice", String.class, String.class);
        shouldUseNewerPrice.setAccessible(true);

        assertFalse((Boolean) shouldUseNewerPrice.invoke(service, "2026-06-30", "2026-07-02"),
            "Older daily close must not overwrite a newer tracked/Dhan LTP date.");
        assertTrue((Boolean) shouldUseNewerPrice.invoke(service, "2026-07-02", "2026-07-02"),
            "Same-day official close may replace the same-day LTP after market close.");
        assertTrue((Boolean) shouldUseNewerPrice.invoke(service, "2026-07-03", "2026-07-02"),
            "Newer market data may replace an older tracked price.");
        assertFalse((Boolean) shouldUseNewerPrice.invoke(service, "", "2026-07-02"),
            "Undated market data must not replace a dated tracked price.");
    }
}
