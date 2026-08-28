package com.traderakshak.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

// R6-Security: All secrets via environment — never hardcoded
@ConfigurationProperties(prefix = "app")
@Data
public class AppProperties {

    private String version = "11.4";
    private String sebiDisclaimer;
    private int dataRetentionYears = 5;

    private Jwt jwt = new Jwt();
    private Cors cors = new Cors();
    private RateLimit rateLimit = new RateLimit();
    private Security security = new Security();
    private Storage storage = new Storage();
    private Telegram telegram = new Telegram();
    private Razorpay razorpay = new Razorpay();
    private Msg91 msg91 = new Msg91();
    private Recaptcha recaptcha = new Recaptcha();

    @Data
    public static class Jwt {
        private String secret;
        private long expirationMs = 1_800_000L;        // 30 min (BRD §17 L2)
        private long refreshExpirationMs = 86_400_000L; // 24 h
    }

    @Data
    public static class Cors {
        private String allowedOrigins = "http://localhost:4200";
    }

    @Data
    public static class RateLimit {
        private int loginPerMinute = 5;   // BRD §17 L5
        private int apiPerMinute = 100;
        private int uploadPerMinute = 20;
    }

    @Data
    public static class Security {
        private int bcryptStrength = 12;  // BRD §17 L4
        private int maxFailedLogins = 5;
        private int lockoutMinutes = 30;
        private String fernetKey;         // PAN encryption key (BRD §17 L4)
    }

    @Data
    public static class Storage {
        private String uploadDir = "./uploads";
        private String exportDir = "./exports";
        private String backupDir = "./backups";
        private String archiveDir = "./archive";
        private String dataDir = "./data";
        private int maxBatchFiles = 250;
        private String localDataPath = "E:/Share Market/Stock for Server/Stock Market Application";
    }

    @Data
    public static class Telegram {
        private String botToken;
        private String chatId;
    }

    @Data
    public static class Razorpay {
        private String keyId;
        private String keySecret;
        private String webhookSecret;
    }

    @Data
    public static class Msg91 {
        private String apiKey;
        private String senderId = "TRDRK";
        private String otpTemplateId;
    }

    @Data
    public static class Recaptcha {
        private String siteKey;
        private String secretKey;
    }
}
