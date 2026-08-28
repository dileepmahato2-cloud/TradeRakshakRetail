package com.traderakshak.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traderakshak.entity.User;
import com.traderakshak.util.DateUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DhanBrokerService {

    private static final String DHAN_API = "https://api.dhan.co/v2";

    private final BlockingHttpService http;
    private final ObjectMapper mapper;

    @PersistenceContext
    private EntityManager em;

    @Transactional
    public Map<String, Object> fetchForWarehouse(User user, String dataType, String fromDate, String toDate) throws Exception {
        return fetchForWarehouse(user, dataType, fromDate, toDate, null);
    }

    @Transactional
    public Map<String, Object> fetchForWarehouse(User user, String dataType, String fromDate, String toDate, Long brokerCredentialId) throws Exception {
        Credential credential = brokerCredentialId == null ? activeDhanCredential(user) : activeDhanCredential(user, brokerCredentialId);
        Map<String, String> headers = Map.of(
            "Accept", "application/json",
            "Content-Type", "application/json",
            "access-token", credential.accessToken(),
            "client-id", credential.clientId()
        );

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("profile", mapper.readTree(http.get(DHAN_API + "/profile", headers, 15000)));

        String type = dataType == null ? "" : dataType.toLowerCase();
        if (type.contains("option")) {
            payload.put("message", "Dhan option chain requires underlying security ID and expiry. Broker connection verified; configure instrument mapping to fetch option-chain rows.");
        } else if (type.contains("daily") || type.contains("bhavcopy") || type.contains("52w") || type.contains("snapshot")) {
            payload.put("holdings", mapper.readTree(http.get(DHAN_API + "/holdings", headers, 15000)));
            payload.put("positions", mapper.readTree(http.get(DHAN_API + "/positions", headers, 15000)));
            payload.put("message", "Dhan holdings and positions fetched. Historical candle fetch needs Dhan security IDs for each symbol.");
        } else {
            payload.put("holdings", mapper.readTree(http.get(DHAN_API + "/holdings", headers, 15000)));
            payload.put("positions", mapper.readTree(http.get(DHAN_API + "/positions", headers, 15000)));
        }

        Long jobId = createCompletedJob(user, credential, dataType, fromDate, toDate, targetTableFor(dataType), mapper.writeValueAsString(Map.of(
            "dataType", dataType,
            "fromDate", fromDate,
            "toDate", toDate,
            "brokerCredentialId", credential.id()
        )));
        int normalizedRows = savePortfolioRows(user, credential, jobId, payload);
        String json = mapper.writeValueAsString(payload);
        em.createNativeQuery(
            "INSERT INTO broker_data_snapshots(user_id,institution_id,broker_credential_id,broker_name,data_type,source,from_date,to_date," +
            "request_json,normalized_table,rows_imported,payload_json,status,message,created_at) " +
            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
            .setParameter(1, user.getId())
            .setParameter(2, user.getInstitutionId())
            .setParameter(3, credential.id())
            .setParameter(4, "dhan")
            .setParameter(5, dataType)
            .setParameter(6, "BROKER")
            .setParameter(7, fromDate)
            .setParameter(8, toDate)
            .setParameter(9, mapper.writeValueAsString(Map.of("fromDate", fromDate, "toDate", toDate)))
            .setParameter(10, targetTableFor(dataType))
            .setParameter(11, normalizedRows)
            .setParameter(12, json)
            .setParameter(13, "completed")
            .setParameter(14, "Fetched from Dhan broker API.")
            .setParameter(15, DateUtil.now())
            .executeUpdate();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("broker", "Dhan");
        result.put("jobId", jobId);
        result.put("dataType", dataType);
        result.put("fromDate", fromDate);
        result.put("toDate", toDate);
        result.put("snapshotSaved", true);
        result.put("normalizedTable", targetTableFor(dataType));
        result.put("normalizedRows", normalizedRows);
        result.put("fetchedSections", payload.keySet());
        return result;
    }

    public Credential activeDhanCredential(User user) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
            "SELECT id, client_id, access_token_enc FROM broker_credentials " +
            "WHERE user_id=? AND broker_name='dhan' AND is_active=1 AND is_verified=1 AND access_token_enc IS NOT NULL AND access_token_enc<>'' " +
            "ORDER BY is_verified DESC, id DESC LIMIT 1")
            .setParameter(1, user.getId())
            .getResultList();
        if (rows.isEmpty()) {
            throw new IllegalStateException("Dhan authentication is not verified. Open Broker Connect and re-authenticate the Dhan token.");
        }
        Object[] row = rows.get(0);
        return new Credential(((Number) row[0]).longValue(), String.valueOf(row[1]), decode(String.valueOf(row[2])));
    }

    public Credential activeDhanCredential(User user, Long brokerCredentialId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
            "SELECT id, client_id, access_token_enc FROM broker_credentials " +
            "WHERE id=? AND user_id=? AND broker_name='dhan' AND is_active=1 AND is_verified=1 AND access_token_enc IS NOT NULL AND access_token_enc<>'' " +
            "LIMIT 1")
            .setParameter(1, brokerCredentialId)
            .setParameter(2, user.getId())
            .getResultList();
        if (rows.isEmpty()) {
            throw new IllegalStateException("Selected Dhan broker authentication is not verified. Open Broker Connect and re-authenticate the token.");
        }
        Object[] row = rows.get(0);
        return new Credential(((Number) row[0]).longValue(), String.valueOf(row[1]), decode(String.valueOf(row[2])));
    }

    public String decode(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return value;
        }
    }

    public JsonNode parseJson(String json) throws Exception {
        return mapper.readTree(json == null || json.isBlank() ? "{}" : json);
    }

    private Long createCompletedJob(User user, Credential credential, String dataType, String fromDate, String toDate,
                                    String targetTable, String requestJson) {
        return ((Number) em.createNativeQuery(
            "INSERT INTO broker_api_fetch_jobs(user_id,institution_id,broker_credential_id,broker_name,broker_label,data_type,source," +
            "from_date,to_date,request_json,status,message,target_table,started_at,completed_at,created_at,updated_at) " +
            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id")
            .setParameter(1, user.getId())
            .setParameter(2, user.getInstitutionId())
            .setParameter(3, credential.id())
            .setParameter(4, "dhan")
            .setParameter(5, "Dhan")
            .setParameter(6, dataType)
            .setParameter(7, "BROKER")
            .setParameter(8, fromDate)
            .setParameter(9, toDate)
            .setParameter(10, requestJson)
            .setParameter(11, "completed")
            .setParameter(12, "Fetched from Dhan broker API.")
            .setParameter(13, targetTable)
            .setParameter(14, DateUtil.now())
            .setParameter(15, DateUtil.now())
            .setParameter(16, DateUtil.now())
            .setParameter(17, DateUtil.now())
            .getSingleResult()).longValue();
    }

    private int savePortfolioRows(User user, Credential credential, Long jobId, Map<String, Object> payload) {
        int count = 0;
        count += savePortfolioSection(user, credential, jobId, "holdings", payload.get("holdings"));
        count += savePortfolioSection(user, credential, jobId, "positions", payload.get("positions"));
        em.createNativeQuery("UPDATE broker_api_fetch_jobs SET rows_imported=?, updated_at=? WHERE id=?")
            .setParameter(1, count)
            .setParameter(2, DateUtil.now())
            .setParameter(3, jobId)
            .executeUpdate();
        return count;
    }

    private int savePortfolioSection(User user, Credential credential, Long jobId, String snapshotType, Object value) {
        if (!(value instanceof JsonNode node)) return 0;
        JsonNode rows = node.isArray() ? node : node.path("data");
        if (!rows.isArray()) return 0;
        int count = 0;
        for (JsonNode row : rows) {
            String symbol = resolveUnderlyingSymbol(row);
            em.createNativeQuery(
                "INSERT INTO broker_portfolio_snapshots(source_job_id,user_id,institution_id,broker_name,broker_credential_id,snapshot_type," +
                "symbol,exchange,product_type,quantity,average_price,last_price,pnl,payload_json,created_at) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
                .setParameter(1, jobId)
                .setParameter(2, user.getId())
                .setParameter(3, user.getInstitutionId())
                .setParameter(4, "dhan")
                .setParameter(5, credential.id())
                .setParameter(6, snapshotType)
                .setParameter(7, symbol)
                .setParameter(8, firstText(row, "exchange", "exchangeSegment"))
                .setParameter(9, firstText(row, "productType", "product"))
                .setParameter(10, firstNumber(row, "quantity", "netQty", "availableQty"))
                .setParameter(11, firstNumber(row, "avgCostPrice", "buyAvg", "averagePrice"))
                .setParameter(12, firstNumber(row, "lastTradedPrice", "ltp", "lastPrice"))
                .setParameter(13, firstNumber(row, "unrealizedProfit", "realizedProfit", "pnl"))
                .setParameter(14, row.toString())
                .setParameter(15, DateUtil.now())
                .executeUpdate();
            count++;
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private String resolveUnderlyingSymbol(JsonNode row) {
        String securityId = firstText(row, "securityId", "securityID", "security_id", "dhanSecurityId");
        String exchangeSegment = firstText(row, "exchangeSegment", "exchange");
        if (!securityId.isBlank()) {
            try {
                StringBuilder sql = new StringBuilder(
                    "SELECT underlying_symbol FROM dhan_instruments WHERE security_id=? AND COALESCE(underlying_symbol,'')<>'' ");
                if (!exchangeSegment.isBlank()) sql.append("AND UPPER(exchange_segment)=? ");
                sql.append("ORDER BY CASE WHEN exchange_segment='NSE_EQ' THEN 0 WHEN exchange_segment='BSE_EQ' THEN 1 ELSE 2 END LIMIT 1");
                var query = em.createNativeQuery(sql.toString()).setParameter(1, securityId);
                if (!exchangeSegment.isBlank()) query.setParameter(2, exchangeSegment.toUpperCase(Locale.ROOT));
                List<Object> rows = query.getResultList();
                if (!rows.isEmpty() && rows.get(0) != null && !String.valueOf(rows.get(0)).isBlank()) {
                    return String.valueOf(rows.get(0)).trim().toUpperCase(Locale.ROOT);
                }
            } catch (Exception ignored) {}
        }

        String brokerSymbol = firstText(row, "underlyingSymbol", "underlying_symbol", "tradingSymbol", "tradingsymbol", "symbol");
        String normalized = normalizeDhanSymbol(brokerSymbol);
        if (!normalized.isBlank()) {
            try {
                List<Object> rows = em.createNativeQuery(
                    "SELECT underlying_symbol FROM dhan_instruments WHERE COALESCE(underlying_symbol,'')<>'' AND (" +
                    normalizedDhanSql("underlying_symbol") + "=? OR " +
                    normalizedDhanSql("trading_symbol") + "=? OR " +
                    normalizedDhanSql("symbol_name") + "=? OR " +
                    normalizedDhanSql("display_name") + "=?) " +
                    "ORDER BY CASE WHEN exchange_segment='NSE_EQ' THEN 0 WHEN exchange_segment='BSE_EQ' THEN 1 ELSE 2 END LIMIT 1")
                    .setParameter(1, normalized)
                    .setParameter(2, normalized)
                    .setParameter(3, normalized)
                    .setParameter(4, normalized)
                    .getResultList();
                if (!rows.isEmpty() && rows.get(0) != null && !String.valueOf(rows.get(0)).isBlank()) {
                    return String.valueOf(rows.get(0)).trim().toUpperCase(Locale.ROOT);
                }
            } catch (Exception ignored) {}
        }
        return brokerSymbol.isBlank() ? securityId : brokerSymbol.trim().toUpperCase(Locale.ROOT);
    }

    private String targetTableFor(String dataType) {
        String type = dataType == null ? "" : dataType.toLowerCase();
        if (type.contains("option")) return "broker_option_chain_snapshots";
        if (type.contains("fii") || type.contains("dii")) return "broker_fii_dii_flows";
        if (type.contains("corporate")) return "broker_corporate_actions";
        if (type.contains("fundamental")) return "broker_fundamentals";
        if (type.contains("delivery")) return "broker_delivery_volumes";
        if (type.contains("daily") || type.contains("bhavcopy") || type.contains("ohlcv")) return "broker_market_candles";
        return "broker_portfolio_snapshots";
    }

    private String firstText(JsonNode row, String... fields) {
        for (String field : fields) {
            String value = row.path(field).asText("");
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private Double firstNumber(JsonNode row, String... fields) {
        for (String field : fields) {
            JsonNode value = row.path(field);
            if (value.isNumber()) return value.asDouble();
            if (value.isTextual() && !value.asText().isBlank()) {
                try { return Double.parseDouble(value.asText()); }
                catch (Exception ignored) {}
            }
        }
        return null;
    }

    private String normalizeDhanSymbol(String symbol) {
        String s = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT)
            .replace(".NS", "")
            .replace(".BO", "")
            .replace(" ", "");
        for (String suffix : List.of("-EQ", "-BE", "-BZ", "-SM", "-ST")) {
            if (s.endsWith(suffix)) return s.substring(0, s.length() - suffix.length());
        }
        return s;
    }

    private String normalizedDhanSql(String column) {
        return "UPPER(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(TRIM(COALESCE(" + column + ",'')),'.NS',''),'.BO',''),'-EQ',''),'-BE',''),'-BZ',''),'-SM',''),'-ST',''),' ',''))";
    }

    public record Credential(Long id, String clientId, String accessToken) {}
}
