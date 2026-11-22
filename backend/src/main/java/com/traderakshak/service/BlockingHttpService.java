package com.traderakshak.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BlockingHttpService {

    private final ObjectMapper mapper;

    public String get(String url, Map<String, String> headers, int timeoutMs) throws Exception {
        String finalUrl = normalizeDhanUrl(url);
        logRequest("GET", finalUrl, headers, null);
        HttpURLConnection con = open(finalUrl, "GET", headers, timeoutMs);
        return read(con);
    }

    /**
     * Streams a response line by line instead of materialising it.
     *
     * <p>get() calls readAllBytes() and then wraps the result in a String, so a 40 MB download
     * costs 80 MB of heap before anything has been parsed. On a 2 GB box the JVM defaults to a
     * 512 MB heap, and the Dhan scrip master alone was enough to exhaust it. Nothing needs the
     * whole document at once - every caller here is walking rows - so hand the reader out and
     * let the caller consume it a line at a time. Peak heap becomes one line.
     *
     * <p>The connection is closed when this returns, so the consumer must not retain the reader.
     */
    public void forEachLine(String url, Map<String, String> headers, int timeoutMs,
                            java.util.function.Consumer<String> onLine) throws Exception {
        String finalUrl = normalizeDhanUrl(url);
        logRequest("GET", finalUrl, headers, null);
        HttpURLConnection con = open(finalUrl, "GET", headers, timeoutMs);
        int code = con.getResponseCode();
        InputStream stream = code >= 400 ? con.getErrorStream() : con.getInputStream();
        if (stream == null) throw new IllegalStateException("HTTP " + code + ": empty response");
        if (code >= 400) {
            try (InputStream is = stream) {
                throw new IllegalStateException("HTTP " + code + ": "
                    + new String(is.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        long started = System.currentTimeMillis();
        long lines = 0, bytes = 0;
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(stream, StandardCharsets.UTF_8), 1 << 16)) {
            String line;
            while ((line = r.readLine()) != null) {
                lines++;
                bytes += line.length() + 1;
                onLine.accept(line);
            }
        } finally {
            con.disconnect();
        }
        log.info("HTTP GET {} streamed status={} lines={} approxBytes={} in {} ms",
            finalUrl, code, lines, bytes, System.currentTimeMillis() - started);
    }

    public String postJson(String url, Map<String, String> headers, Object body, int timeoutMs) throws Exception {
        String finalUrl = normalizeDhanUrl(url);
        byte[] payload = mapper.writeValueAsBytes(body);
        HttpURLConnection con = open(finalUrl, "POST", headers, timeoutMs);
        con.setRequestProperty("Content-Type", "application/json");
        Map<String, String> requestHeaders = new LinkedHashMap<>(headers);
        requestHeaders.put("Content-Type", "application/json");
        logRequest("POST", finalUrl, requestHeaders, new String(payload, StandardCharsets.UTF_8));
        con.setDoOutput(true);
        try (OutputStream os = con.getOutputStream()) {
            os.write(payload);
        }
        return read(con);
    }

    public String postForm(String url, Map<String, String> headers,
                           Map<String, String> form, int timeoutMs) throws Exception {
        String finalUrl = normalizeDhanUrl(url);
        String body = form.entrySet().stream()
            .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
            .collect(Collectors.joining("&"));
        HttpURLConnection con = open(finalUrl, "POST", headers, timeoutMs);
        con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        Map<String, String> requestHeaders = new LinkedHashMap<>(headers);
        requestHeaders.put("Content-Type", "application/x-www-form-urlencoded");
        logRequest("POST", finalUrl, requestHeaders, body);
        con.setDoOutput(true);
        try (OutputStream os = con.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return read(con);
    }

    public String postFormBasic(String url, String username, String password,
                                Map<String, String> form, int timeoutMs) throws Exception {
        String finalUrl = normalizeDhanUrl(url);
        String body = form.entrySet().stream()
            .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
            .collect(Collectors.joining("&"));
        HttpURLConnection con = open(finalUrl, "POST", Map.of(), timeoutMs);
        con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        String basic = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        con.setRequestProperty("Authorization", "Basic " + basic);
        logRequest("POST", finalUrl, Map.of("Content-Type", "application/x-www-form-urlencoded", "Authorization", "Basic ****"), body);
        con.setDoOutput(true);
        try (OutputStream os = con.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return read(con);
    }

    private HttpURLConnection open(String url, String method, Map<String, String> headers, int timeoutMs) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setRequestMethod(method);
        con.setConnectTimeout(timeoutMs);
        con.setReadTimeout(timeoutMs);
        headers.forEach(con::setRequestProperty);
        return con;
    }

    private static final int MAX_RETRIES = 3;
    private static final long BASE_BACKOFF_MS = 1000;

    public String postJsonWithRetry(String url, Map<String, String> headers, Object body, int timeoutMs) throws Exception {
        Exception lastException = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                return postJson(url, headers, body, timeoutMs);
            } catch (Exception e) {
                lastException = e;
                if (!isRetryable(e) || attempt == MAX_RETRIES) throw e;
                long delay = BASE_BACKOFF_MS * (1L << attempt);
                log.warn("Retryable error on attempt {}/{} for {}, backing off {}ms: {}",
                    attempt + 1, MAX_RETRIES + 1, url, delay, e.getMessage());
                Thread.sleep(delay);
            }
        }
        throw lastException;
    }

    public String getWithRetry(String url, Map<String, String> headers, int timeoutMs) throws Exception {
        Exception lastException = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                return get(url, headers, timeoutMs);
            } catch (Exception e) {
                lastException = e;
                if (!isRetryable(e) || attempt == MAX_RETRIES) throw e;
                long delay = BASE_BACKOFF_MS * (1L << attempt);
                log.warn("Retryable error on attempt {}/{} for {}, backing off {}ms: {}",
                    attempt + 1, MAX_RETRIES + 1, url, delay, e.getMessage());
                Thread.sleep(delay);
            }
        }
        throw lastException;
    }

    private boolean isRetryable(Exception e) {
        if (e instanceof java.net.SocketTimeoutException || e instanceof java.net.ConnectException) return true;
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.contains("HTTP 429") || msg.contains("HTTP 502") || msg.contains("HTTP 503") || msg.contains("HTTP 504")) return true;
        return false;
    }

    private String read(HttpURLConnection con) throws Exception {
        int code = con.getResponseCode();
        InputStream stream = code >= 400 ? con.getErrorStream() : con.getInputStream();
        if (stream == null) {
            log.warn("HTTP {} {} returned status {} with empty response", con.getRequestMethod(), con.getURL(), code);
            return "";
        }
        try (InputStream is = stream) {
            String text = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            if (code >= 400) {
                // Dhan DH-905 ("no data present" / "missing required fields") on a bulk
                // backfill is an expected per-symbol skip (delisted/illiquid/new listings),
                // not a system error - log it as WARN so it does not spam red ERRORs.
                String low = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT);
                boolean benignData = low.contains("dh-905") || low.contains("no data present")
                    || low.contains("missing required fields") || low.contains("incorrect parameters");
                if (benignData) {
                    // DEBUG, not WARN. Dhan answers DH-907 "no data present" for every delisted,
                    // suspended or never-traded instrument in the range, so a single backfill
                    // across ~3,000 symbols emits hundreds of these and buries everything else -
                    // it reads like a failing job when it is a job working exactly as intended.
                    // Nothing is lost by quieting it: the backfill writes each skip to
                    // dhan_historical_errors with the symbol, the dates, the request body and
                    // Dhan's own message, which is where the detail belongs anyway.
                    log.debug("HTTP {} {} status {} - symbol skipped (no data / bad params).", con.getRequestMethod(), con.getURL(), code);
                } else {
                    log.error("HTTP {} {} failed with status {} response={}", con.getRequestMethod(), con.getURL(), code, text);
                }
                throw new IllegalStateException("HTTP " + code + ": " + text);
            }
            String requestUrl = con.getURL().toString();
            if (isBulkDhanCall(requestUrl)) {
                log.debug("HTTP {} {} completed status={} responseBytes={}", con.getRequestMethod(), maskUrl(requestUrl), code, text.length());
            } else {
                log.info("HTTP {} {} completed status={} responseBytes={}", con.getRequestMethod(), maskUrl(requestUrl), code, text.length());
            }
            return text;
        }
    }

    private String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String normalizeDhanUrl(String url) {
        if (url == null) return "";
        String value = url.trim();
        if (value.startsWith("api.dhan.co")) value = "https://" + value;
        if (value.startsWith("http://api.dhan.co")) value = "https://" + value.substring("http://".length());
        if (value.startsWith("https://api.dhan.co/v2/v2")) {
            value = "https://api.dhan.co/v2" + value.substring("https://api.dhan.co/v2/v2".length());
        }
        if (value.startsWith("https://api.dhan.co/") && !value.startsWith("https://api.dhan.co/v2/")) {
            value = "https://api.dhan.co/v2/" + value.substring("https://api.dhan.co/".length());
        }
        if ("https://api.dhan.co".equals(value)) value = "https://api.dhan.co/v2";
        return value;
    }

    private void logRequest(String method, String url, Map<String, String> headers, String body) {
        if (!isDhanUrl(url)) {
            log.debug("HTTP request method={} url={} headers={} body={}", method, maskUrl(url), maskHeaders(headers), body == null ? "" : body);
            return;
        }
        if (isBulkDhanCall(url)) {
            log.debug("Dhan API request method={} url={} body={}", method, maskUrl(url), body == null ? "" : body);
        } else {
            log.info("Dhan API request method={} url={} headers={} body={}", method, maskUrl(url), maskHeaders(headers), body == null ? "" : body);
        }
    }

    private boolean isBulkDhanCall(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.contains("/charts/") || lower.contains("/historical") || lower.contains("/intraday");
    }

    private boolean isDhanUrl(String url) {
        String value = url == null ? "" : url.toLowerCase();
        return value.contains("dhan.co") || value.contains("api.dhan");
    }

    /** Redact secrets passed in the query string. maskHeaders() only covers headers,
     *  and Dhan puts pin and totp on generateAccessToken as query parameters - those
     *  were being written to logs/traderakshak.log in the clear. */
    private String maskUrl(String url) {
        if (url == null) return "";
        int q = url.indexOf('?');
        if (q < 0) return url;
        StringBuilder sb = new StringBuilder(url.substring(0, q + 1));
        String[] pairs = url.substring(q + 1).split("&");
        for (int i = 0; i < pairs.length; i++) {
            if (i > 0) sb.append('&');
            String pair = pairs[i];
            int eq = pair.indexOf('=');
            if (eq <= 0) { sb.append(pair); continue; }
            String name = pair.substring(0, eq);
            String n = name.toLowerCase();
            if (n.contains("pin") || n.contains("totp") || n.contains("token") || n.contains("secret")
                    || n.contains("password") || n.contains("apikey") || n.contains("api_key")) {
                sb.append(name).append('=').append(mask(pair.substring(eq + 1)));
            } else {
                sb.append(pair);
            }
        }
        return sb.toString();
    }

    private Map<String, String> maskHeaders(Map<String, String> headers) {
        Map<String, String> safe = new LinkedHashMap<>();
        if (headers == null) return safe;
        headers.forEach((key, value) -> {
            String k = key == null ? "" : key.toLowerCase();
            if (k.contains("token") || k.contains("authorization") || k.contains("secret") || k.contains("password")) {
                safe.put(key, mask(value));
            } else {
                safe.put(key, value);
            }
        });
        return safe;
    }

    private String mask(String value) {
        if (value == null || value.isBlank()) return "";
        if (value.length() <= 8) return "****";
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }
}
