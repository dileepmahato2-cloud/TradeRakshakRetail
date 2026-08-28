package com.traderakshak;

// =============================================================================
// TradeRakshak™ v11.4 — Spring Boot Application Entry Point
// Rules: R1-Scope R2-NoDelete R3-Consent R4-SEBI R5-Docker
//        R6-Security R7-Performance R8-Testing R9-Queries R10-MobileAPI
//        R11-Comments R12-NoTempFiles R13-ProgressDialogs
//        R14-FreeAPIFirst R15-ExactErrors
// =============================================================================

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.lang.reflect.Field;

@SpringBootApplication
@EnableCaching
@EnableAsync
@EnableScheduling
@ConfigurationPropertiesScan
@Slf4j
public class TradeRakshakApplication {

    public static void main(String[] args) {
        forceTcpSelectorPipeOnWindows();
        SpringApplication.run(TradeRakshakApplication.class, args);
        log.info("=================================================================");
        log.info("  TradeRakshak™ v11.5 — Backend API Started (No Docker)");
        log.info("  Swagger UI : http://localhost:8080/api/swagger-ui.html");
        log.info("  API Docs   : http://localhost:8080/api/v3/api-docs");
        log.info("  Health     : http://localhost:8080/api/actuator/health");
        log.info("=================================================================");
    }

    private static void forceTcpSelectorPipeOnWindows() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return;
        }
        try {
            Field field = Class.forName("sun.nio.ch.PipeImpl").getDeclaredField("noUnixDomainSockets");
            field.setAccessible(true);
            field.setBoolean(null, true);
        } catch (Throwable ex) {
            log.debug("Java selector pipe fallback was not changed: {}", ex.toString());
        }
    }
}
