package com.traderakshak.service;

import com.traderakshak.util.DateUtil;
import com.traderakshak.util.InrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * Port of Python _parse_bhav(), _parse_fo_bhav(), data_upload()
 * Handles Equity + F&O bhavcopy CSV parsing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DataImportService {

    @PersistenceContext
    private EntityManager em;

    private static final int BATCH_FLUSH_SIZE = 100;

    /** Decode ZIP or raw CSV bytes — port of Python _decode() */
    public String decode(byte[] content) throws IOException {
        if (content.length >= 2 && content[0] == 0x50 && content[1] == 0x4B) {
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(content))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String name = entry.getName().toLowerCase();
                    if (name.endsWith(".csv") || name.endsWith(".dat") || name.endsWith(".txt")) {
                        return new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                    }
                }
            }
        }
        return new String(content, StandardCharsets.UTF_8);
    }

    public String decode(byte[] content, String filename) throws IOException {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
            return spreadsheetToCsv(content);
        }
        return decode(content);
    }

    /** Parse equity bhavcopy — port of Python _parse_bhav() */
    @Transactional
    public int parseEquityBhav(String csvText, String tradeDate, String source) {
        int count = 0;
        try (CSVParser parser = CSVParser.parse(csvText,
                CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setTrim(true).build())) {
            for (CSVRecord row : parser) {
                try {
                    String sym = InrUtil.normaliseSymbol(get(row, "SYMBOL","TCKRSYMB"));
                    if (sym.isEmpty()) continue;
                    String series = get(row, "SERIES").toUpperCase();
                    if (!series.isEmpty() && !Set.of("EQ","BE","BZ","BL","SM","A","B","T","").contains(series)) continue;

                    double op = sf(get(row,"OPEN_PRICE","OPEN","OPNPRIC"));
                    double hi = sf(get(row,"HIGH_PRICE","HIGH","HGHPRIC"));
                    double lo = sf(get(row,"LOW_PRICE","LOW","LWPRIC"));
                    double cl = sf(get(row,"CLOSE_PRICE","CLOSE","CLSPRIC"));
                    double prev = sf(get(row,"PREV_CLOSE","PREV_CL_PR"));
                    if (prev == 0) prev = cl;
                    double vol = sf(get(row,"TTL_TRD_QNTY","TOTTRDQTY","VOLUME"));
                    double dv  = sf(get(row,"DELIV_QTY","DELIVERABLE_QTY"));
                    double dp  = sf(get(row,"DELIV_PER","DELIVERY %"));
                    if (dv == 0 && dp > 0 && vol > 0) dv = vol * dp / 100;
                    if (dp == 0 && vol > 0 && dv > 0) dp = dv * 100 / vol;
                    double vwap = (hi + lo + cl) / 3;
                    String company = get(row,"SECURITY_NAME","SECURITY");
                    if (company.isEmpty()) company = sym;

                    String actionType = "MANUAL".equalsIgnoreCase(source) ? "uploaded" : "fetched";
                    String nowTs = com.traderakshak.util.DateUtil.now();
                    em.createNativeQuery(
                        "INSERT INTO stock_master(exchange,symbol,company_name,sector,industry,status,source,as_on_date,series,segment,action_type,created_at,fetched_at)" +
                        " VALUES(?,?,?,?,?,'active',?,?,?,?,?,?,?) ON CONFLICT (exchange, symbol) DO UPDATE SET " +
                        "source=EXCLUDED.source,as_on_date=EXCLUDED.as_on_date,action_type=EXCLUDED.action_type,updated_at=EXCLUDED.created_at")
                        .setParameter(1,"NSE").setParameter(2,sym).setParameter(3,company)
                        .setParameter(4,"Unknown").setParameter(5,"Unknown")
                        .setParameter(6,source).setParameter(7,tradeDate)
                        .setParameter(8,series.isEmpty()?"EQ":series).setParameter(9,"Equity")
                        .setParameter(10,actionType).setParameter(11,nowTs).setParameter(12,nowTs).executeUpdate();

                    // Bhavcopy rows land in broker_market_candles, the single candle store, keyed
                    // exactly as the Dhan backfill keys a daily candle (interval_key 0, candle_time
                    // = trade_date at midnight). The security id is resolved from the instrument
                    // master so an imported row UPSERTs onto the broker's row for that symbol and
                    // date rather than becoming a second copy of it; symbols the master does not
                    // know fall back to the symbol itself, which still dedupes across re-imports.
                    em.createNativeQuery(
                        "INSERT INTO broker_market_candles(broker_name,broker_security_id,data_type,exchange,exchange_segment,segment," +
                        "symbol,candle_mode,interval_key,candle_time,trade_date,open,high,low,close,prev_close,vwap," +
                        "volume,total_volume,delivery_volume,delivery_pct,source,created_at,updated_at) " +
                        "VALUES('dhan'," +
                        "COALESCE((SELECT security_id FROM dhan_instruments WHERE UPPER(TRIM(COALESCE(underlying_symbol,'')))=UPPER(TRIM(?)) " +
                        "AND exchange_segment='NSE_EQ' LIMIT 1), UPPER(TRIM(?)))," +
                        "'Daily Bhavcopy (OHLCV)','NSE','NSE_EQ','E',?,'DAILY',0,?||'T00:00:00',?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) " +
                        "ON CONFLICT (broker_name, broker_security_id, exchange_segment, candle_mode, interval_key, candle_time) DO UPDATE SET " +
                        "open=EXCLUDED.open, high=EXCLUDED.high, low=EXCLUDED.low, close=EXCLUDED.close, " +
                        "prev_close=EXCLUDED.prev_close, vwap=EXCLUDED.vwap, volume=EXCLUDED.volume, " +
                        "total_volume=EXCLUDED.total_volume, delivery_volume=EXCLUDED.delivery_volume, " +
                        "delivery_pct=EXCLUDED.delivery_pct, source=EXCLUDED.source, updated_at=EXCLUDED.updated_at")
                        .setParameter(1,sym).setParameter(2,sym).setParameter(3,sym)
                        .setParameter(4,tradeDate).setParameter(5,tradeDate)
                        .setParameter(6,op).setParameter(7,hi).setParameter(8,lo).setParameter(9,cl)
                        .setParameter(10,prev).setParameter(11,Math.round(vwap*100.0)/100.0)
                        .setParameter(12,vol).setParameter(13,vol).setParameter(14,dv).setParameter(15,dp)
                        .setParameter(16,source).executeUpdate();
                    count++;
                    if (count % BATCH_FLUSH_SIZE == 0) { em.flush(); em.clear(); }
                } catch (Exception e) { log.debug("Row parse error: {}", e.getMessage()); }
            }
        } catch (IOException e) { log.error("CSV parse error: {}", e.getMessage()); }
        if (count > 0) { em.flush(); em.clear(); }
        return count;
    }

    @Transactional
    public Map<String, Object> importWarehouseText(String dataType, String csvText, String fallbackDate, String source) {
        String type = dataType == null ? "" : dataType.toLowerCase(Locale.ROOT);
        if (type.contains("f&o") || type.contains("derivative")) return importFoBhavcopy(csvText, fallbackDate, source);
        if (type.contains("daily") || type.contains("bhavcopy") || type.contains("ohlcv")) {
            int imported = parseEquityBhav(csvText, fallbackDate, source);
            return result("broker_market_candles", imported);
        }
        if (type.contains("fii") || type.contains("dii")) return importFiiDii(csvText, source);
        if (type.contains("option")) return importOptionChain(csvText, source);
        if (type.contains("fundamental")) return importFundamentals(csvText, source);
        if (type.contains("52w") || type.contains("ath") || type.contains("snapshot")) return importStockSnapshot(csvText, source);
        if (type.contains("delivery")) return importDeliveryVolume(csvText, fallbackDate, source);
        if (type.contains("corporate")) return importCorporateActions(csvText, source);
        return result("broker_data_snapshots", 0);
    }

    private Map<String, Object> importFoBhavcopy(String csvText, String fallbackDate, String source) {
        int count = 0;
        for (CSVRecord row : records(csvText)) {
            try {
                String symbol = InrUtil.normaliseSymbol(first(row, "TckrSymb", "SYMBOL", "symbol"));
                if (symbol.isBlank()) continue;
                String tradeDate = normalizeDate(firstOr(row, "TradDt", fallbackDate));
                String expiry = normalizeDate(first(row, "XpryDt", "EXPIRY_DT", "expiry_date"));
                String instrument = firstOr(row, "FinInstrmTp", firstOr(row, "INSTRUMENT", "FNO"));
                String optionType = first(row, "OptnTp", "OPTION_TYP", "option_type");
                double strike = firstNumber(row, "StrkPric", "STRIKE_PR", "strike");
                String brokerSecurityId = String.join("|", "NSE_FO", instrument, symbol, expiry, String.valueOf(strike), optionType);
                double open = firstNumber(row, "OpnPric", "OPEN", "open");
                double high = firstNumber(row, "HghPric", "HIGH", "high");
                double low = firstNumber(row, "LwPric", "LOW", "low");
                double close = firstNumber(row, "ClsPric", "CLOSE", "close");
                double prev = firstNumber(row, "PrvsClsgPric", "PREV_CLOSE", "prev_close");
                double volume = firstNumber(row, "TtlTradgVol", "CONTRACTS", "volume");
                double turnover = firstNumber(row, "TtlTrfVal", "VAL_INLAKH", "turnover");
                double oi = firstNumber(row, "OpnIntrst", "OPEN_INT", "open_interest");
                em.createNativeQuery(
                    "INSERT INTO broker_market_candles(source_job_type,broker_name,data_type,exchange,exchange_segment,segment,instrument_type,broker_security_id,symbol,trading_symbol,candle_mode,interval_key,candle_time,trade_date,open,high,low,close,prev_close,volume,total_volume,turnover,open_interest,source,raw_json,created_at,updated_at) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                    "ON CONFLICT (broker_name, broker_security_id, exchange_segment, candle_mode, interval_key, candle_time) DO UPDATE SET " +
                    "open=EXCLUDED.open,high=EXCLUDED.high,low=EXCLUDED.low,close=EXCLUDED.close,prev_close=EXCLUDED.prev_close,volume=EXCLUDED.volume,total_volume=EXCLUDED.total_volume,turnover=EXCLUDED.turnover,open_interest=EXCLUDED.open_interest,raw_json=EXCLUDED.raw_json,updated_at=EXCLUDED.updated_at")
                    .setParameter(1, "exchange-report").setParameter(2, "nse").setParameter(3, "F&O Bhavcopy")
                    .setParameter(4, "NSE").setParameter(5, "NSE_FO").setParameter(6, "Derivatives").setParameter(7, instrument)
                    .setParameter(8, brokerSecurityId).setParameter(9, symbol).setParameter(10, symbol)
                    .setParameter(11, "DAILY").setParameter(12, 0).setParameter(13, tradeDate).setParameter(14, tradeDate)
                    .setParameter(15, open).setParameter(16, high).setParameter(17, low).setParameter(18, close).setParameter(19, prev)
                    .setParameter(20, volume).setParameter(21, volume).setParameter(22, turnover).setParameter(23, oi)
                    .setParameter(24, source).setParameter(25, row.toMap().toString())
                    // created_at/updated_at are native timestamps (V38).
                    .setParameter(26, Timestamp.valueOf(LocalDateTime.now()))
                    .setParameter(27, Timestamp.valueOf(LocalDateTime.now()))
                    .executeUpdate();
                count++;
                if (count % BATCH_FLUSH_SIZE == 0) { em.flush(); em.clear(); }
            } catch (Exception e) { log.debug("F&O row skipped: {}", e.getMessage()); }
        }
        if (count > 0) { em.flush(); em.clear(); }
        return result("broker_market_candles", count);
    }

    @Transactional
    public int importCsvIntoTable(String tableName, String csvText) {
        if (!safeIdentifier(tableName)) return 0;
        List<String> columns = tableColumns(tableName);
        if (columns.isEmpty()) return 0;
        String clean = stripMetadata(csvText);
        int count = 0;
        try (CSVParser parser = CSVParser.parse(clean,
                CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setTrim(true).build())) {
            List<String> headers = parser.getHeaderNames().stream()
                .map(this::normalizeColumn)
                .filter(columns::contains)
                .toList();
            if (headers.isEmpty()) return 0;
            String placeholders = String.join(",", Collections.nCopies(headers.size(), "?"));
            String joinedColumns = headers.stream().map(this::quote).reduce((a, b) -> a + "," + b).orElse("");
            String sql = "INSERT INTO " + quote(tableName) + "(" + joinedColumns + ") VALUES(" + placeholders + ")";
            for (CSVRecord row : parser) {
                try {
                    var query = em.createNativeQuery(sql);
                    for (int i = 0; i < headers.size(); i++) {
                        query.setParameter(i + 1, blankToNull(get(row, headers.get(i))));
                    }
                    query.executeUpdate();
                    count++;
                } catch (Exception e) {
                    log.debug("Generic table import row skipped: {}", e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("Generic table CSV import error: {}", e.getMessage());
        }
        return count;
    }

    private Map<String, Object> importFiiDii(String csvText, String source) {
        int count = 0;
        for (CSVRecord row : records(csvText)) {
            try {
                String tradeDate = normalizeDate(first(row, "trade_date", "date", "DATE"));
                if (tradeDate.isBlank()) continue;
                double fiiBuy = firstNumber(row, "fii_buy", "fii_buy_value", "buy_value");
                double fiiSell = firstNumber(row, "fii_sell", "fii_sell_value", "sell_value");
                double fiiNet = firstNumber(row, "fii_net", "net_value", "net");
                double diiBuy = firstNumber(row, "dii_buy");
                double diiSell = firstNumber(row, "dii_sell");
                double diiNet = firstNumber(row, "dii_net");
                em.createNativeQuery(
                    "INSERT INTO fii_dii_data(trade_date,fii_buy,fii_sell,fii_net,dii_buy,dii_sell,dii_net,source,created_at) " +
                    "VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT (trade_date) DO UPDATE SET " +
                    "fii_buy=EXCLUDED.fii_buy,fii_sell=EXCLUDED.fii_sell,fii_net=EXCLUDED.fii_net,dii_buy=EXCLUDED.dii_buy," +
                    "dii_sell=EXCLUDED.dii_sell,dii_net=EXCLUDED.dii_net,source=EXCLUDED.source,created_at=EXCLUDED.created_at")
                    .setParameter(1, tradeDate).setParameter(2, fiiBuy).setParameter(3, fiiSell).setParameter(4, fiiNet)
                    .setParameter(5, diiBuy).setParameter(6, diiSell).setParameter(7, diiNet)
                    .setParameter(8, source).setParameter(9, DateUtil.now()).executeUpdate();
                em.createNativeQuery(
                    "INSERT INTO broker_fii_dii_flows(broker_name,trade_date,market_segment,fii_buy,fii_sell,fii_net,dii_buy,dii_sell,dii_net,raw_json,created_at) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (broker_name, trade_date, market_segment) DO UPDATE SET " +
                    "fii_buy=EXCLUDED.fii_buy,fii_sell=EXCLUDED.fii_sell,fii_net=EXCLUDED.fii_net,dii_buy=EXCLUDED.dii_buy,dii_sell=EXCLUDED.dii_sell,dii_net=EXCLUDED.dii_net,raw_json=EXCLUDED.raw_json")
                    .setParameter(1, source.toLowerCase(Locale.ROOT)).setParameter(2, tradeDate).setParameter(3, firstOr(row, "market_segment", "EQUITY"))
                    .setParameter(4, fiiBuy).setParameter(5, fiiSell).setParameter(6, fiiNet).setParameter(7, diiBuy).setParameter(8, diiSell).setParameter(9, diiNet)
                    .setParameter(10, row.toMap().toString()).setParameter(11, DateUtil.now()).executeUpdate();
                count++;
            } catch (Exception e) { log.debug("FII/DII row skipped: {}", e.getMessage()); }
        }
        return result("fii_dii_data", count);
    }

    private Map<String, Object> importOptionChain(String csvText, String source) {
        int count = 0;
        for (CSVRecord row : records(csvText)) {
            try {
                String underlying = first(row, "underlying", "symbol", "SYMBOL");
                String tradeDate = normalizeDate(first(row, "trade_date", "date"));
                String expiry = first(row, "expiry", "expiry_date");
                String optionType = first(row, "option_type", "type");
                if (underlying.isBlank() || tradeDate.isBlank()) continue;
                double strike = firstNumber(row, "strike", "strike_price");
                em.createNativeQuery(
                    "INSERT INTO option_chain(underlying,symbol,exchange,trade_date,expiry_date,strike,option_type,ltp,open,high,low,close,volume,oi,change_oi,iv,source,created_at) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (underlying,exchange,trade_date,expiry_date,strike,option_type) DO UPDATE SET " +
                    "symbol=EXCLUDED.symbol,ltp=EXCLUDED.ltp,open=EXCLUDED.open,high=EXCLUDED.high,low=EXCLUDED.low,close=EXCLUDED.close,volume=EXCLUDED.volume,oi=EXCLUDED.oi,change_oi=EXCLUDED.change_oi,iv=EXCLUDED.iv,source=EXCLUDED.source")
                    .setParameter(1, underlying).setParameter(2, InrUtil.normaliseSymbol(underlying)).setParameter(3, firstOr(row, "exchange", "NSE")).setParameter(4, tradeDate).setParameter(5, expiry)
                    .setParameter(6, strike).setParameter(7, optionType).setParameter(8, firstNumber(row, "ltp", "last_price"))
                    .setParameter(9, firstNumber(row, "open")).setParameter(10, firstNumber(row, "high")).setParameter(11, firstNumber(row, "low"))
                    .setParameter(12, firstNumber(row, "close")).setParameter(13, (int) firstNumber(row, "volume"))
                    .setParameter(14, (int) firstNumber(row, "oi", "open_interest")).setParameter(15, (int) firstNumber(row, "change_oi"))
                    .setParameter(16, firstNumber(row, "iv")).setParameter(17, source).setParameter(18, DateUtil.now()).executeUpdate();
                count++;
            } catch (Exception e) { log.debug("Option row skipped: {}", e.getMessage()); }
        }
        return result("option_chain", count);
    }

    private Map<String, Object> importFundamentals(String csvText, String source) {
        int count = 0;
        for (CSVRecord row : records(csvText)) {
            try {
                String symbol = InrUtil.normaliseSymbol(first(row, "symbol", "SYMBOL"));
                String period = first(row, "period", "quarter");
                if (symbol.isBlank()) continue;
                em.createNativeQuery(
                    "INSERT INTO fundamentals(symbol,exchange,period_type,period,revenue,net_profit,eps,pe,pb,debt_equity,source,created_at) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (symbol, exchange, period_type, period) DO UPDATE SET " +
                    "revenue=EXCLUDED.revenue,net_profit=EXCLUDED.net_profit,eps=EXCLUDED.eps,pe=EXCLUDED.pe,pb=EXCLUDED.pb,debt_equity=EXCLUDED.debt_equity,source=EXCLUDED.source")
                    .setParameter(1, symbol).setParameter(2, firstOr(row, "exchange", "NSE")).setParameter(3, firstOr(row, "period_type", "Quarterly"))
                    .setParameter(4, period).setParameter(5, firstNumber(row, "revenue")).setParameter(6, firstNumber(row, "net_profit"))
                    .setParameter(7, firstNumber(row, "eps")).setParameter(8, firstNumber(row, "pe")).setParameter(9, firstNumber(row, "pb"))
                    .setParameter(10, firstNumber(row, "debt_equity")).setParameter(11, source).setParameter(12, DateUtil.now()).executeUpdate();
                count++;
            } catch (Exception e) { log.debug("Fundamentals row skipped: {}", e.getMessage()); }
        }
        return result("fundamentals", count);
    }

    private Map<String, Object> importStockSnapshot(String csvText, String source) {
        int count = 0;
        for (CSVRecord row : records(csvText)) {
            try {
                String symbol = InrUtil.normaliseSymbol(first(row, "symbol", "SYMBOL"));
                if (symbol.isBlank()) continue;
                em.createNativeQuery(
                    "INSERT INTO stock_snapshot(symbol,exchange,week52_high,week52_low,ath,last_updated) VALUES(?,?,?,?,?,?) " +
                    "ON CONFLICT (symbol) DO UPDATE SET exchange=EXCLUDED.exchange,week52_high=EXCLUDED.week52_high,week52_low=EXCLUDED.week52_low,ath=EXCLUDED.ath,last_updated=EXCLUDED.last_updated")
                    .setParameter(1, symbol).setParameter(2, firstOr(row, "exchange", "NSE"))
                    .setParameter(3, firstNumber(row, "week_52_high", "52w_high")).setParameter(4, firstNumber(row, "week_52_low", "52w_low"))
                    .setParameter(5, firstNumber(row, "ath")).setParameter(6, firstOr(row, "snapshot_date", DateUtil.today())).executeUpdate();
                count++;
            } catch (Exception e) { log.debug("Snapshot row skipped: {}", e.getMessage()); }
        }
        return result("stock_snapshot", count);
    }

    private Map<String, Object> importDeliveryVolume(String csvText, String fallbackDate, String source) {
        int count = 0;
        for (CSVRecord row : records(csvText)) {
            try {
                // NSE MTO .DAT: only record type "20" is data; skip headers ("10") and footers ("30")
                String recordType = first(row, "Record Type", "RecordType", "record_type");
                if (!recordType.isBlank() && !"20".equals(recordType.trim())) continue;

                String symbol = InrUtil.normaliseSymbol(first(row, "symbol", "SYMBOL", "Name of Security", "security"));
                String tradeDate = normalizeDate(first(row, "trade_date", "date", "Date"));
                if (tradeDate.isBlank() && fallbackDate != null && !fallbackDate.isBlank()) {
                    tradeDate = fallbackDate;
                }
                if (symbol.isBlank() || tradeDate.isBlank()) continue;
                double traded = firstNumber(row, "traded_qty", "traded_quantity", "volume", "Quantity Traded");
                double delivered = firstNumber(row, "deliverable_qty", "delivery_quantity", "delivery_volume", "Deliverable Quantity(gross across client level)");
                double pct = firstNumber(row, "delivery_pct", "delivery_percent", "% of Deliverable Quantity to Traded Quantity");
                // Delivery figures go onto the candle itself and into broker_delivery_volumes below.
                em.createNativeQuery(
                    "UPDATE broker_market_candles SET delivery_volume=?, delivery_pct=? " +
                    "WHERE UPPER(TRIM(COALESCE(symbol,'')))=UPPER(TRIM(?)) AND trade_date=? " +
                    "AND UPPER(COALESCE(candle_mode,''))='DAILY'")
                    .setParameter(1, delivered).setParameter(2, pct).setParameter(3, symbol).setParameter(4, tradeDate).executeUpdate();
                em.createNativeQuery(
                    "INSERT INTO broker_delivery_volumes(broker_name,exchange,symbol,trade_date,traded_quantity,delivery_quantity,delivery_pct,raw_json,created_at) " +
                    "VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT (broker_name, exchange, symbol, trade_date) DO UPDATE SET " +
                    "traded_quantity=EXCLUDED.traded_quantity,delivery_quantity=EXCLUDED.delivery_quantity,delivery_pct=EXCLUDED.delivery_pct,raw_json=EXCLUDED.raw_json")
                    .setParameter(1, source.toLowerCase(Locale.ROOT)).setParameter(2, firstOr(row, "exchange", "NSE")).setParameter(3, symbol)
                    .setParameter(4, tradeDate).setParameter(5, traded).setParameter(6, delivered).setParameter(7, pct)
                    .setParameter(8, row.toMap().toString()).setParameter(9, DateUtil.now()).executeUpdate();
                count++;
                if (count % BATCH_FLUSH_SIZE == 0) { em.flush(); em.clear(); }
            } catch (Exception e) { log.debug("Delivery row skipped: {}", e.getMessage()); }
        }
        if (count > 0) { em.flush(); em.clear(); }
        return result("broker_delivery_volumes", count);
    }

    private Map<String, Object> importCorporateActions(String csvText, String source) {
        int count = 0;
        for (CSVRecord row : records(csvText)) {
            try {
                String symbol = InrUtil.normaliseSymbol(first(row, "symbol", "SYMBOL"));
                String action = first(row, "action_type", "action");
                if (symbol.isBlank() || action.isBlank()) continue;
                em.createNativeQuery(
                    "INSERT INTO broker_corporate_actions(broker_name,exchange,symbol,action_type,ex_date,record_date,ratio_text,description,raw_json,created_at) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT (broker_name, symbol, action_type, ex_date, record_date) DO UPDATE SET ratio_text=EXCLUDED.ratio_text,description=EXCLUDED.description,raw_json=EXCLUDED.raw_json")
                    .setParameter(1, source.toLowerCase(Locale.ROOT)).setParameter(2, firstOr(row, "exchange", "NSE")).setParameter(3, symbol)
                    .setParameter(4, action).setParameter(5, first(row, "ex_date")).setParameter(6, first(row, "record_date"))
                    .setParameter(7, first(row, "ratio", "ratio_text")).setParameter(8, first(row, "remarks", "description"))
                    .setParameter(9, row.toMap().toString()).setParameter(10, DateUtil.now()).executeUpdate();
                count++;
            } catch (Exception e) { log.debug("Corporate action row skipped: {}", e.getMessage()); }
        }
        return result("broker_corporate_actions", count);
    }

    private String get(CSVRecord row, String... keys) {
        for (String k : keys) {
            try { String v = row.get(k); if (v != null && !v.isBlank()) return v.trim(); } catch (Exception ignored) {}
            try { String v = row.get(k.toUpperCase(Locale.ROOT)); if (v != null && !v.isBlank()) return v.trim(); } catch (Exception ignored) {}
            try { String v = row.get(k.toLowerCase(Locale.ROOT)); if (v != null && !v.isBlank()) return v.trim(); } catch (Exception ignored) {}
            for (Map.Entry<String, String> entry : row.toMap().entrySet()) {
                if (sameColumn(entry.getKey(), k) && entry.getValue() != null && !entry.getValue().isBlank()) {
                    return entry.getValue().trim();
                }
            }
        }
        return "";
    }
    private String first(CSVRecord row, String... keys) { return get(row, keys); }
    private String firstOr(CSVRecord row, String key, String fallback) {
        String value = get(row, key);
        return value.isBlank() ? fallback : value;
    }
    private double firstNumber(CSVRecord row, String... keys) {
        return sf(get(row, keys));
    }
    private double sf(String v) { return InrUtil.safeFloat(v, 0.0); }

    private List<CSVRecord> records(String csvText) {
        try (CSVParser parser = CSVParser.parse(stripMetadata(csvText),
                CSVFormat.DEFAULT.builder().setDelimiter(delimiterFor(csvText)).setHeader().setSkipHeaderRecord(true).setTrim(true).build())) {
            return parser.getRecords();
        } catch (IOException e) {
            log.error("Warehouse CSV parse error: {}", e.getMessage());
            return List.of();
        }
    }

    private Map<String, Object> result(String tableName, int imported) {
        return Map.of("targetTable", tableName, "rowsImported", imported);
    }

    private String spreadsheetToCsv(byte[] content) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            DataFormatter formatter = new DataFormatter();
            Sheet sheet = workbook.getSheetAt(0);
            StringBuilder out = new StringBuilder();
            for (Row row : sheet) {
                int last = Math.max(row.getLastCellNum(), 0);
                for (int i = 0; i < last; i++) {
                    if (i > 0) out.append(',');
                    Cell cell = row.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    out.append(csvCell(cell == null ? "" : formatter.formatCellValue(cell)));
                }
                out.append('\n');
            }
            return out.toString();
        } catch (Exception e) {
            throw new IOException("Spreadsheet could not be read: " + e.getMessage(), e);
        }
    }

    private String stripMetadata(String text) {
        String clean = text == null ? "" : text.replace("\uFEFF", "");
        List<String> allLines = clean.lines().toList();
        List<String> filtered = new ArrayList<>();
        boolean headerFound = false;
        for (String line : allLines) {
            String trimmed = line.trim();
            if (trimmed.isBlank() || trimmed.startsWith("#")) continue;
            if (!headerFound) {
                // NSE MTO .DAT files: skip preamble lines until we find the header row
                // Header row contains column names like "Name of Security" or "Symbol"
                String lower = trimmed.toLowerCase(Locale.ROOT);
                if (lower.contains("symbol") || lower.contains("name of security") || lower.contains("tckrsymb")
                    || lower.contains("trade_date") || lower.contains("open") || lower.contains("record type")) {
                    headerFound = true;
                    filtered.add(trimmed);
                } else if (trimmed.contains(",") || trimmed.contains("|") || trimmed.contains("\t")) {
                    // Looks like a delimited data line \u2014 could be the header or first data row
                    headerFound = true;
                    filtered.add(trimmed);
                }
                // else skip the preamble line
            } else {
                filtered.add(trimmed);
            }
        }
        return String.join("\n", filtered);
    }

    private List<String> tableColumns(String tableName) {
        try {
            @SuppressWarnings("unchecked")
            List<Object> rows = em.createNativeQuery(
                "SELECT column_name FROM information_schema.columns WHERE table_schema=current_schema() AND table_name=? ORDER BY ordinal_position")
                .setParameter(1, tableName).getResultList();
            List<String> columns = new ArrayList<>();
            for (Object row : rows) columns.add(String.valueOf(row));
            return columns;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String normalizeColumn(String column) {
        return column == null ? "" : column.replace("\uFEFF", "").trim().toLowerCase(Locale.ROOT);
    }

    private boolean sameColumn(String actual, String expected) {
        return normalizeKey(actual).equals(normalizeKey(expected));
    }

    private String normalizeKey(String value) {
        return value == null ? "" : value.replace("\uFEFF", "").replaceAll("[^A-Za-z0-9]+", "").toLowerCase(Locale.ROOT);
    }

    private char delimiterFor(String text) {
        String first = stripMetadata(text).lines().findFirst().orElse("");
        long pipes = first.chars().filter(ch -> ch == '|').count();
        long commas = first.chars().filter(ch -> ch == ',').count();
        return pipes > commas ? '|' : ',';
    }

    private String normalizeDate(String value) {
        if (value == null || value.isBlank()) return "";
        String text = value.trim();
        try { return java.time.LocalDate.parse(text).toString(); } catch (Exception ignored) {}
        List<java.time.format.DateTimeFormatter> formats = List.of(
            java.time.format.DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH),
            java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH),
            java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH),
            java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ENGLISH)
        );
        for (java.time.format.DateTimeFormatter format : formats) {
            try { return java.time.LocalDate.parse(text, format).toString(); } catch (Exception ignored) {}
        }
        return text;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String quote(String identifier) {
        if (!safeIdentifier(identifier)) throw new IllegalArgumentException("Unsafe identifier");
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private boolean safeIdentifier(String identifier) {
        return identifier != null && identifier.matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    private String csvCell(String text) {
        String value = text == null ? "" : text;
        return value.contains(",") || value.contains("\"") || value.contains("\n")
            ? "\"" + value.replace("\"", "\"\"") + "\""
            : value;
    }
}
