package com.traderakshak.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traderakshak.util.DateUtil;
import com.traderakshak.util.InrUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.GZIPInputStream;

@Service
@RequiredArgsConstructor
public class NseExchangeFetchService {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter DDMMYYYY = DateTimeFormatter.ofPattern("ddMMyyyy");
    private static final DateTimeFormatter NSE_DDMMMYYYY = DateTimeFormatter.ofPattern("ddMMMyyyy", Locale.ENGLISH);
    private static final DateTimeFormatter NSE_DD_MMM_YYYY = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH);
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125 Safari/537.36";

    private final DataImportService importService;
    private final ObjectMapper mapper;
    @PersistenceContext private EntityManager em;

    @Transactional
    public Map<String, Object> fetch(String dataType, String source, String fromDate, String toDate) {
        String type = dataType == null ? "" : dataType.toLowerCase(Locale.ROOT);
        if (type.contains("option")) return fetchOptionChain();
        if (type.contains("fii") || type.contains("dii")) return fetchFiiDii(source);

        LocalDate from = LocalDate.parse(fromDate, ISO);
        LocalDate to = LocalDate.parse(toDate, ISO);
        if (to.isBefore(from)) throw new IllegalArgumentException("To date must be same as or after From date.");
        if (from.datesUntil(to.plusDays(1)).count() > 10) {
            throw new IllegalArgumentException("Please fetch maximum 10 dates at a time for NSE reports.");
        }

        List<Map<String, Object>> files = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        int totalRows = 0;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            // Skip weekends — NSE is closed on Saturday and Sunday
            if (day.getDayOfWeek().getValue() >= 6) {
                skipped.add(Map.of("date", day.toString(), "reason", "Weekend (" + day.getDayOfWeek() + ")"));
                continue;
            }

            DownloadedFile file;
            try {
                file = downloadReportForDate(type, source, day);
            } catch (Exception e) {
                skipped.add(Map.of("date", day.toString(), "reason", shortError(e.getMessage())));
                continue;
            }

            String content;
            try {
                content = importService.decode(file.bytes(), file.filename());
            } catch (Exception e) {
                skipped.add(Map.of("date", day.toString(), "reason", "Decode failed: " + shortError(e.getMessage())));
                continue;
            }

            try {
                Map<String, Object> result = importService.importWarehouseText(dataType, content, day.toString(), source);
                int rows = ((Number) result.getOrDefault("rowsImported", 0)).intValue();
                totalRows += rows;
                files.add(Map.of(
                    "date", day.toString(),
                    "file", file.filename(),
                    "rowsImported", rows,
                    "targetTable", result.getOrDefault("targetTable", "")
                ));
            } catch (Exception e) {
                skipped.add(Map.of("date", day.toString(), "reason", "Import failed: " + shortError(e.getMessage())));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", source);
        out.put("dataType", dataType);
        out.put("rowsImported", totalRows);
        out.put("filesFetched", files.size());
        out.put("details", files);
        out.put("skippedDates", skipped);
        out.put("skippedCount", skipped.size());
        out.put("targetTable", files.isEmpty() ? "" : files.get(0).getOrDefault("targetTable", ""));
        out.put("message", "Fetched " + files.size() + " NSE report file(s) and imported " + totalRows + " rows."
            + (skipped.isEmpty() ? "" : " Skipped " + skipped.size() + " date(s) where data was unavailable."));
        return out;
    }

    private DownloadedFile downloadReportForDate(String type, String source, LocalDate day) {
        List<String> urls = reportUrls(type, source, day);
        List<String> errors = new ArrayList<>();
        for (String url : urls) {
            try {
                byte[] bytes = requestBytes(url);
                if (bytes.length > 0) return new DownloadedFile(filenameFromUrl(url), bytes);
            } catch (Exception e) {
                errors.add(shortError(e.getMessage()));
            }
        }
        throw new IllegalStateException("NSE report was not available for " + day + ". Tried official archive links. " + String.join("; ", errors));
    }

    private List<String> reportUrls(String type, String source, LocalDate day) {
        String yyyy = String.valueOf(day.getYear());
        String mon = day.format(MONTH).toUpperCase(Locale.ROOT);
        String ddMonYear = day.format(NSE_DDMMMYYYY).toUpperCase(Locale.ROOT);
        String ymd = day.format(DateTimeFormatter.BASIC_ISO_DATE);
        String dmy = day.format(DDMMYYYY);
        List<String> urls = new ArrayList<>();

        if (type.contains("delivery") || source.toUpperCase(Locale.ROOT).contains("MTO")) {
            urls.add("https://nsearchives.nseindia.com/archives/equities/mto/MTO_" + dmy + ".DAT");
            urls.add("https://archives.nseindia.com/archives/equities/mto/MTO_" + dmy + ".DAT");
            urls.add("https://nsearchives.nseindia.com/products/content/sec_bhavdata_full_" + dmy + ".csv");
            return urls;
        }

        if (type.contains("f&o") || type.contains("fo ") || type.contains("derivative")) {
            urls.add("https://nsearchives.nseindia.com/content/fo/BhavCopy_NSE_FO_0_0_0_" + ymd + "_F_0000.csv.zip");
            urls.add("https://archives.nseindia.com/content/historical/DERIVATIVES/" + yyyy + "/" + mon + "/fo" + ddMonYear + "bhav.csv.zip");
            return urls;
        }

        urls.add("https://nsearchives.nseindia.com/content/cm/BhavCopy_NSE_CM_0_0_0_" + ymd + "_F_0000.csv.zip");
        urls.add("https://archives.nseindia.com/content/historical/EQUITIES/" + yyyy + "/" + mon + "/cm" + ddMonYear + "bhav.csv.zip");
        return urls;
    }

    private Map<String, Object> fetchFiiDii(String source) {
        try {
            String json = requestText("https://www.nseindia.com/api/fiidiiTradeReact");
            JsonNode root = mapper.readTree(json);
            if (!root.isArray()) throw new IllegalStateException("NSE FII/DII response was not in the expected format.");
            Map<String, Flow> byDate = new LinkedHashMap<>();
            for (JsonNode row : root) {
                String category = text(row, "category");
                String tradeDate = normalizeDate(text(row, "date"));
                if (tradeDate.isBlank()) continue;
                Flow flow = byDate.computeIfAbsent(tradeDate, d -> new Flow());
                double buy = num(row, "buyValue");
                double sell = num(row, "sellValue");
                double net = num(row, "netValue");
                if (category.toUpperCase(Locale.ROOT).contains("DII")) {
                    flow.diiBuy = buy; flow.diiSell = sell; flow.diiNet = net;
                } else if (category.toUpperCase(Locale.ROOT).contains("FII") || category.toUpperCase(Locale.ROOT).contains("FPI")) {
                    flow.fiiBuy = buy; flow.fiiSell = sell; flow.fiiNet = net;
                }
            }
            int rows = 0;
            for (Map.Entry<String, Flow> entry : byDate.entrySet()) {
                Flow f = entry.getValue();
                em.createNativeQuery(
                    "INSERT INTO fii_dii_data(trade_date,fii_buy,fii_sell,fii_net,dii_buy,dii_sell,dii_net,source,created_at) " +
                    "VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT (trade_date) DO UPDATE SET " +
                    "fii_buy=EXCLUDED.fii_buy,fii_sell=EXCLUDED.fii_sell,fii_net=EXCLUDED.fii_net,dii_buy=EXCLUDED.dii_buy," +
                    "dii_sell=EXCLUDED.dii_sell,dii_net=EXCLUDED.dii_net,source=EXCLUDED.source,created_at=EXCLUDED.created_at")
                    .setParameter(1, entry.getKey()).setParameter(2, f.fiiBuy).setParameter(3, f.fiiSell).setParameter(4, f.fiiNet)
                    .setParameter(5, f.diiBuy).setParameter(6, f.diiSell).setParameter(7, f.diiNet)
                    .setParameter(8, source).setParameter(9, DateUtil.now()).executeUpdate();
                rows++;
            }
            return Map.of(
                "source", source,
                "dataType", "FII/DII Data",
                "rowsImported", rows,
                "targetTable", "fii_dii_data",
                "message", "Fetched latest FII/DII trading activity from NSE and imported " + rows + " date row(s)."
            );
        } catch (Exception e) {
            throw new IllegalStateException("NSE FII/DII data could not be fetched: " + shortError(e.getMessage()), e);
        }
    }

    private Map<String, Object> fetchOptionChain() {
        try {
            String json = requestText("https://www.nseindia.com/api/option-chain-indices?symbol=NIFTY");
            JsonNode records = mapper.readTree(json).path("records");
            String tradeDate = normalizeDate(records.path("timestamp").asText(""));
            if (tradeDate.isBlank()) tradeDate = DateUtil.today();
            int rows = 0;
            for (JsonNode item : records.path("data")) {
                rows += insertOptionSide("NIFTY", tradeDate, item.path("expiryDate").asText(""), item.path("strikePrice").asDouble(0), item.path("CE"));
                rows += insertOptionSide("NIFTY", tradeDate, item.path("expiryDate").asText(""), item.path("strikePrice").asDouble(0), item.path("PE"));
            }
            return Map.of(
                "source", "NSE",
                "dataType", "Option Chain",
                "rowsImported", rows,
                "targetTable", "option_chain",
                "message", "Fetched current NIFTY option-chain snapshot from NSE and imported " + rows + " option rows."
            );
        } catch (Exception e) {
            throw new IllegalStateException("NSE option chain could not be fetched: " + shortError(e.getMessage()), e);
        }
    }

    private int insertOptionSide(String underlying, String tradeDate, String expiry, double strike, JsonNode side) {
        if (side == null || side.isMissingNode() || side.isNull()) return 0;
        String optionType = side.path("optionType").asText("");
        if (optionType.isBlank()) optionType = side.has("identifier") && side.path("identifier").asText("").contains("PE") ? "PE" : "CE";
        em.createNativeQuery(
            "INSERT INTO option_chain(underlying,symbol,exchange,trade_date,expiry_date,strike,option_type,ltp,open,high,low,close,volume,oi,change_oi,iv,underlying_price,source,created_at) " +
            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (underlying,exchange,trade_date,expiry_date,strike,option_type) DO UPDATE SET " +
            "symbol=EXCLUDED.symbol,ltp=EXCLUDED.ltp,volume=EXCLUDED.volume,oi=EXCLUDED.oi,change_oi=EXCLUDED.change_oi,iv=EXCLUDED.iv,underlying_price=EXCLUDED.underlying_price,source=EXCLUDED.source,created_at=EXCLUDED.created_at")
            .setParameter(1, underlying).setParameter(2, normalizeSymbol(underlying)).setParameter(3, "NSE").setParameter(4, tradeDate).setParameter(5, normalizeDate(expiry))
            .setParameter(6, strike).setParameter(7, optionType).setParameter(8, side.path("lastPrice").asDouble(0))
            .setParameter(9, side.path("openPrice").asDouble(0)).setParameter(10, side.path("highPrice").asDouble(0)).setParameter(11, side.path("lowPrice").asDouble(0))
            .setParameter(12, side.path("closePrice").asDouble(0)).setParameter(13, side.path("totalTradedVolume").asInt(0))
            .setParameter(14, side.path("openInterest").asInt(0)).setParameter(15, side.path("changeinOpenInterest").asInt(0))
            .setParameter(16, side.path("impliedVolatility").asDouble(0)).setParameter(17, side.path("underlyingValue").asDouble(0))
            .setParameter(18, "NSE").setParameter(19, DateUtil.now()).executeUpdate();
        return 1;
    }

    private byte[] requestBytes(String url) throws Exception {
        HttpClient client = client();
        warmup(client, url);
        HttpRequest request = baseRequest(url).GET().build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        byte[] body = response.body();
        if (url.toLowerCase(Locale.ROOT).endsWith(".gz")) {
            try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(body))) {
                return gz.readAllBytes();
            }
        }
        return body;
    }

    private String requestText(String url) throws Exception {
        return new String(requestBytes(url), StandardCharsets.UTF_8);
    }

    private volatile HttpClient sharedNseClient;
    private final Object nseClientLock = new Object();

    private HttpClient client() {
        HttpClient c = sharedNseClient;
        if (c == null) {
            synchronized (nseClientLock) {
                c = sharedNseClient;
                if (c == null) {
                    CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
                    c = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .cookieHandler(cookies)
                        .connectTimeout(java.time.Duration.ofSeconds(15))
                        .build();
                    sharedNseClient = c;
                }
            }
        }
        return c;
    }

    private void warmup(HttpClient client, String url) {
        try {
            String host = URI.create(url).getHost();
            String home = host != null && host.contains("niftyindices") ? "https://www.niftyindices.com/" : "https://www.nseindia.com/";
            client.send(baseRequest(home).GET().build(), HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {}
    }

    private HttpRequest.Builder baseRequest(String url) {
        return HttpRequest.newBuilder(URI.create(url))
            .timeout(java.time.Duration.ofSeconds(25))
            .header("User-Agent", UA)
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", refererFor(url));
    }

    private String refererFor(String url) {
        if (url.contains("/content/fo/") || url.contains("/DERIVATIVES/")) return "https://www.nseindia.com/all-reports-derivatives";
        if (url.contains("fiidii")) return "https://www.nseindia.com/reports/fii-dii";
        if (url.contains("option-chain")) return "https://www.nseindia.com/option-chain";
        return "https://www.nseindia.com/all-reports";
    }

    private String filenameFromUrl(String url) {
        int slash = url.lastIndexOf('/');
        return slash >= 0 ? url.substring(slash + 1) : "nse-report.csv";
    }

    private String text(JsonNode node, String field) {
        return node.path(field).asText("").trim();
    }

    private double num(JsonNode node, String field) {
        return InrUtil.safeFloat(node.path(field).asText("0"), 0.0);
    }

    private String normalizeDate(String value) {
        if (value == null || value.isBlank()) return "";
        String text = value.trim();
        if (text.length() >= 11 && Character.isDigit(text.charAt(0)) && text.charAt(2) == '-') {
            try { return LocalDate.parse(text.substring(0, 11), NSE_DD_MMM_YYYY).toString(); } catch (Exception ignored) {}
        }
        try { return LocalDate.parse(text, ISO).toString(); } catch (Exception ignored) {}
        try { return LocalDate.parse(text.replace(" ", "-"), NSE_DD_MMM_YYYY).toString(); } catch (Exception ignored) {}
        return text;
    }

    private String normalizeSymbol(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT)
            .replace(".NS", "")
            .replace(".BO", "")
            .replaceAll("\\s+", "");
    }

    private String shortError(String value) {
        if (value == null || value.isBlank()) return "no response";
        String clean = value.replaceAll("\\s+", " ").trim();
        return clean.length() > 180 ? clean.substring(0, 180) + "..." : clean;
    }

    private record DownloadedFile(String filename, byte[] bytes) {}
    private static class Flow {
        double fiiBuy;
        double fiiSell;
        double fiiNet;
        double diiBuy;
        double diiSell;
        double diiNet;
    }
}
