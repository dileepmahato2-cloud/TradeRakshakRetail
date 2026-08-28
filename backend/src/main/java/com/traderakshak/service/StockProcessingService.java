package com.traderakshak.service;

import com.traderakshak.util.DateUtil;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class StockProcessingService {

    private final StockTrackerService trackerService;
    private final PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager em;

    private final Map<String, String> jobStatusCache = new ConcurrentHashMap<>();
    private final Map<String, Boolean> pauseFlags = new ConcurrentHashMap<>();
    private final Map<String, Boolean> cancelFlags = new ConcurrentHashMap<>();

    public static final String STAGE_UPLOAD        = "UPLOAD";
    public static final String STAGE_STOCK_DETAILS  = "STOCK_DETAILS";
    public static final String STAGE_CURRENT_TOP10  = "CURRENT_TOP10";
    public static final String STAGE_PREVIOUS_TOP10 = "PREVIOUS_TOP10";
    public static final String STAGE_AVG_TREND      = "AVG_TREND";

    private static final List<String> PIPELINE_STAGES = List.of(
        STAGE_UPLOAD, STAGE_STOCK_DETAILS, STAGE_CURRENT_TOP10, STAGE_PREVIOUS_TOP10, STAGE_AVG_TREND
    );

    /** Catalogue check; never throws, and assumes "present" on error so startup is not blocked. */
    private boolean tableExists(String table) {
        try {
            Object n = em.createNativeQuery(
                "SELECT COUNT(*) FROM information_schema.tables "
              + "WHERE table_schema = current_schema() AND table_name = ?")
                .setParameter(1, table).getSingleResult();
            return n instanceof Number num && num.longValue() > 0;
        } catch (Exception e) {
            return true;
        }
    }

    @PostConstruct
    public void ensureProcessingTables() {
        try {
            new TransactionTemplate(transactionManager).execute(status -> {
                // Bound the wait. Without this the CREATE statements below queue behind whatever
                // else holds the table and block startup for as long as it takes; with it, a busy
                // moment costs two seconds and is retried on the next boot instead.
                em.createNativeQuery("SET LOCAL lock_timeout = '2s'").executeUpdate();
                em.createNativeQuery("SET LOCAL statement_timeout = '15s'").executeUpdate();
                // CREATE ... IF NOT EXISTS still takes a lock to discover it has nothing to do,
                // so on the normal path - tables already present - skip the whole block.
                if (tableExists("processing_jobs") && tableExists("processing_job_details")) {
                    return null;
                }
                em.createNativeQuery("""
                    CREATE TABLE IF NOT EXISTS processing_jobs (
                        job_id TEXT PRIMARY KEY,
                        user_id INTEGER,
                        institution_id INTEGER,
                        file_name TEXT,
                        upload_type TEXT DEFAULT 'STOCK_UPLOAD',
                        total_records INTEGER DEFAULT 0,
                        processed_records INTEGER DEFAULT 0,
                        success_records INTEGER DEFAULT 0,
                        failed_records INTEGER DEFAULT 0,
                        skipped_records INTEGER DEFAULT 0,
                        duplicate_records INTEGER DEFAULT 0,
                        current_symbol TEXT,
                        current_step TEXT,
                        progress_percentage DOUBLE PRECISION DEFAULT 0,
                        status TEXT DEFAULT 'PENDING',
                        error_message TEXT,
                        started_at TEXT,
                        last_updated_at TEXT,
                        completed_at TEXT,
                        created_at TEXT
                    )""").executeUpdate();

                em.createNativeQuery("""
                    CREATE TABLE IF NOT EXISTS processing_job_details (
                        id SERIAL PRIMARY KEY,
                        job_id TEXT NOT NULL,
                        user_id INTEGER,
                        symbol TEXT,
                        trading_symbol TEXT,
                        exchange TEXT DEFAULT 'NSE',
                        status TEXT DEFAULT 'PENDING',
                        current_step TEXT,
                        retry_count INTEGER DEFAULT 0,
                        skip_reason TEXT,
                        error_message TEXT,
                        created_at TEXT,
                        updated_at TEXT
                    )""").executeUpdate();

                em.createNativeQuery("CREATE INDEX IF NOT EXISTS idx_pj_user ON processing_jobs(user_id, created_at DESC)").executeUpdate();
                em.createNativeQuery("CREATE INDEX IF NOT EXISTS idx_pj_status ON processing_jobs(status)").executeUpdate();
                em.createNativeQuery("CREATE INDEX IF NOT EXISTS idx_pjd_job ON processing_job_details(job_id, status)").executeUpdate();
                em.createNativeQuery("CREATE INDEX IF NOT EXISTS idx_pjd_symbol ON processing_job_details(job_id, symbol)").executeUpdate();

                addColumnIfMissing("processing_jobs", "pipeline_stage TEXT DEFAULT 'UPLOAD'");
                addColumnIfMissing("processing_jobs", "pipeline_stages_json TEXT");

                return null;
            });
        } catch (Exception e) {
            log.debug("Processing tables migration: {}", e.getMessage());
        }
    }

    private void addColumnIfMissing(String table, String colDef) {
        try {
            em.createNativeQuery("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS " + colDef).executeUpdate();
        } catch (Exception ignored) {}
    }

    public String createJob(Long userId, Long institutionId, String fileName, int totalRecords) {
        String jobId = "JOB-" + System.currentTimeMillis();
        String now = DateUtil.now();
        new TransactionTemplate(transactionManager).execute(status -> {
            em.createNativeQuery(
                "INSERT INTO processing_jobs(job_id,user_id,institution_id,file_name,total_records," +
                "status,created_at,last_updated_at) VALUES(?,?,?,?,?,?,?,?)")
                .setParameter(1, jobId).setParameter(2, userId)
                .setParameter(3, institutionId).setParameter(4, fileName)
                .setParameter(5, totalRecords).setParameter(6, "PENDING")
                .setParameter(7, now).setParameter(8, now).executeUpdate();
            return null;
        });
        jobStatusCache.put(jobId, "PENDING");
        return jobId;
    }

    public void insertJobDetails(String jobId, Long userId, List<String> symbols) {
        String now = DateUtil.now();
        new TransactionTemplate(transactionManager).execute(status -> {
            int batch = 0;
            for (String symbol : symbols) {
                em.createNativeQuery(
                    "INSERT INTO processing_job_details(job_id,user_id,symbol,status,created_at,updated_at) " +
                    "VALUES(?,?,?,?,?,?)")
                    .setParameter(1, jobId).setParameter(2, userId)
                    .setParameter(3, symbol).setParameter(4, "PENDING")
                    .setParameter(5, now).setParameter(6, now).executeUpdate();
                if (++batch % 100 == 0) em.flush();
            }
            return null;
        });
    }

    @Async
    public void startBackgroundProcessing(String jobId, Long userId, Long institutionId) {
        pauseFlags.remove(jobId);
        cancelFlags.remove(jobId);

        initPipelineStages(jobId);
        updateJobStatus(jobId, "RUNNING", null, null);

        try {
            // ── STAGE 1: UPLOAD — process base prices ──
            updatePipelineStage(jobId, STAGE_UPLOAD, "RUNNING", null);

            @SuppressWarnings("unchecked")
            List<Object> pendingRows = em.createNativeQuery(
                "SELECT symbol FROM processing_job_details WHERE job_id=? AND status IN ('PENDING','FAILED') ORDER BY id")
                .setParameter(1, jobId).getResultList();

            List<String> pendingSymbols = new java.util.ArrayList<>();
            for (Object r : pendingRows) {
                if (r instanceof Object[]) pendingSymbols.add(String.valueOf(((Object[]) r)[0]));
                else pendingSymbols.add(String.valueOf(r));
            }

            if (pendingSymbols.isEmpty()) {
                updatePipelineStage(jobId, STAGE_UPLOAD, "COMPLETED", null);
                completePipelineRemaining(jobId, STAGE_STOCK_DETAILS);
                updateJobStatus(jobId, "COMPLETED", null, null);
                return;
            }

            int totalForJob = pendingSymbols.size();
            int processed = 0;
            List<String> symbolsNeedingCurrentPrice = new java.util.ArrayList<>();

            for (String symbol : pendingSymbols) {
                if (Boolean.TRUE.equals(cancelFlags.get(jobId))) {
                    updatePipelineStage(jobId, STAGE_UPLOAD, "CANCELLED", null);
                    updateJobStatus(jobId, "CANCELLED", null, null);
                    return;
                }

                while (Boolean.TRUE.equals(pauseFlags.get(jobId))) {
                    updateJobStatus(jobId, "PAUSED", symbol, "Paused by user");
                    try { Thread.sleep(2000); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (Boolean.TRUE.equals(cancelFlags.get(jobId))) {
                        updatePipelineStage(jobId, STAGE_UPLOAD, "CANCELLED", null);
                        updateJobStatus(jobId, "CANCELLED", null, null);
                        return;
                    }
                }

                updateJobProgress(jobId, symbol, "PROCESSING", processed, totalForJob);
                processStockBasePrice(jobId, userId, institutionId, symbol, symbolsNeedingCurrentPrice);
                processed++;
                updateJobProgress(jobId, symbol, "RUNNING", processed, totalForJob);
            }

            recalculateJobCounts(jobId);
            updatePipelineStage(jobId, STAGE_UPLOAD, "COMPLETED", null);
            log.info("Pipeline [{}] UPLOAD complete: {} symbols processed", jobId, totalForJob);

            if (Boolean.TRUE.equals(cancelFlags.get(jobId))) {
                updateJobStatus(jobId, "CANCELLED", null, null);
                return;
            }

            // ── STAGE 2: STOCK_DETAILS — batch refresh current prices ──
            updatePipelineStage(jobId, STAGE_STOCK_DETAILS, "RUNNING", null);
            try {
                if (!symbolsNeedingCurrentPrice.isEmpty()) {
                    int updated = trackerService.refreshBatchCurrentPrices(symbolsNeedingCurrentPrice, userId, institutionId);
                    log.info("Pipeline [{}] STOCK_DETAILS: {}/{} prices updated", jobId, updated, symbolsNeedingCurrentPrice.size());
                } else {
                    log.info("Pipeline [{}] STOCK_DETAILS: all prices already available", jobId);
                }
                updatePipelineStage(jobId, STAGE_STOCK_DETAILS, "COMPLETED", null);
            } catch (Exception e) {
                log.warn("Pipeline [{}] STOCK_DETAILS failed: {}", jobId, e.getMessage());
                updatePipelineStage(jobId, STAGE_STOCK_DETAILS, "COMPLETED", "Partial: " + e.getMessage());
            }

            if (Boolean.TRUE.equals(cancelFlags.get(jobId))) {
                updateJobStatus(jobId, "CANCELLED", null, null);
                return;
            }

            // ── STAGE 3: CURRENT_TOP10 — run ranking engine ──
            updatePipelineStage(jobId, STAGE_CURRENT_TOP10, "RUNNING", null);
            try {
                trackerService.runRankingEngine(userId, institutionId, "pipeline");
                log.info("Pipeline [{}] CURRENT_TOP10: ranking engine completed", jobId);
                updatePipelineStage(jobId, STAGE_CURRENT_TOP10, "COMPLETED", null);
            } catch (Exception e) {
                log.warn("Pipeline [{}] CURRENT_TOP10 failed: {}", jobId, e.getMessage());
                updatePipelineStage(jobId, STAGE_CURRENT_TOP10, "COMPLETED", "Error: " + e.getMessage());
            }

            if (Boolean.TRUE.equals(cancelFlags.get(jobId))) {
                updateJobStatus(jobId, "CANCELLED", null, null);
                return;
            }

            // ── STAGE 4: PREVIOUS_TOP10 — data already computed by ranking engine ──
            updatePipelineStage(jobId, STAGE_PREVIOUS_TOP10, "RUNNING", null);
            try {
                trackerService.getLatestRankingFull(userId, institutionId);
                log.info("Pipeline [{}] PREVIOUS_TOP10: data refreshed", jobId);
                updatePipelineStage(jobId, STAGE_PREVIOUS_TOP10, "COMPLETED", null);
            } catch (Exception e) {
                log.warn("Pipeline [{}] PREVIOUS_TOP10 failed: {}", jobId, e.getMessage());
                updatePipelineStage(jobId, STAGE_PREVIOUS_TOP10, "COMPLETED", "Error: " + e.getMessage());
            }

            if (Boolean.TRUE.equals(cancelFlags.get(jobId))) {
                updateJobStatus(jobId, "CANCELLED", null, null);
                return;
            }

            // ── STAGE 5: AVG_TREND — ranking history / chart data ──
            updatePipelineStage(jobId, STAGE_AVG_TREND, "RUNNING", null);
            try {
                trackerService.getRankingHistory(userId, institutionId, 90);
                log.info("Pipeline [{}] AVG_TREND: history data refreshed", jobId);
                updatePipelineStage(jobId, STAGE_AVG_TREND, "COMPLETED", null);
            } catch (Exception e) {
                log.warn("Pipeline [{}] AVG_TREND failed: {}", jobId, e.getMessage());
                updatePipelineStage(jobId, STAGE_AVG_TREND, "COMPLETED", "Error: " + e.getMessage());
            }

            updateJobStatus(jobId, "COMPLETED", null, null);
            log.info("Pipeline [{}] ALL STAGES COMPLETE", jobId);

        } catch (Exception e) {
            log.error("Background processing failed for job {}: {}", jobId, e.getMessage());
            updateJobStatus(jobId, "FAILED", null, e.getMessage());
        }
    }

    private void processStockBasePrice(String jobId, Long userId, Long institutionId,
                                       String symbol, List<String> symbolsNeedingCurrentPrice) {
        try {
            updateDetailStatus(jobId, symbol, "PROCESSING", "Base price check", null);

            boolean hasBasePrice = checkBasePriceInDb(symbol, institutionId);
            if (!hasBasePrice) {
                updateDetailStatus(jobId, symbol, "PROCESSING", "Fetching base price from Dhan", null);
                try {
                    trackerService.fetchBasePriceForSingleStock(symbol, userId, institutionId);
                } catch (Exception e) {
                    String reason = classifyError(e.getMessage());
                    if (isSkippable(reason)) {
                        markDetailSkipped(jobId, symbol, reason);
                        return;
                    }
                }
            }

            if (!checkCurrentPriceInDb(symbol, institutionId)) {
                symbolsNeedingCurrentPrice.add(symbol);
            }

            updateDetailStatus(jobId, symbol, "COMPLETED", "Done", null);

        } catch (Exception e) {
            String reason = classifyError(e.getMessage());
            updateDetailStatus(jobId, symbol, "FAILED", null, reason);
            log.debug("Processing failed for {} in job {}: {}", symbol, jobId, e.getMessage());
        }
    }

    private boolean checkBasePriceInDb(String symbol, Long institutionId) {
        try {
            Object result = em.createNativeQuery(
                "SELECT base_price FROM tracked_stocks WHERE symbol=? AND institution_id=? AND is_active=1")
                .setParameter(1, symbol).setParameter(2, institutionId).getSingleResult();
            return result != null && ((Number) result).doubleValue() > 0;
        } catch (Exception e) { return false; }
    }

    private boolean checkCurrentPriceInDb(String symbol, Long institutionId) {
        try {
            Object result = em.createNativeQuery(
                "SELECT current_price FROM tracked_stocks WHERE symbol=? AND institution_id=? AND is_active=1")
                .setParameter(1, symbol).setParameter(2, institutionId).getSingleResult();
            return result != null && ((Number) result).doubleValue() > 0;
        } catch (Exception e) { return false; }
    }

    private String classifyError(String message) {
        if (message == null) return "Unknown error";
        String lower = message.toLowerCase();
        if (lower.contains("not found") || lower.contains("not mapped")) return "Symbol not found in Dhan";
        if (lower.contains("rate_limit") || lower.contains("dh-904") || lower.contains("too many")) return "Dhan API rate limit";
        if (lower.contains("token") || lower.contains("auth")) return "Authentication error";
        if (lower.contains("network") || lower.contains("timeout") || lower.contains("connect")) return "Network error";
        if (lower.contains("historical") || lower.contains("no data")) return "Historical data missing";
        if (lower.contains("holiday")) return "Market holiday";
        if (lower.contains("closing price not available")) return "Dhan API data not available";
        return "Calculation failed: " + message.substring(0, Math.min(200, message.length()));
    }

    private boolean isSkippable(String reason) {
        return reason.contains("not found") || reason.contains("not mapped");
    }

    // ── Job status updates ───────────────────────────────────────────────────

    private void updateJobStatus(String jobId, String status, String currentSymbol, String errorMessage) {
        String now = DateUtil.now();
        new TransactionTemplate(transactionManager).execute(s -> {
            var q = em.createNativeQuery(
                "UPDATE processing_jobs SET status=?,current_symbol=?,error_message=?,last_updated_at=?" +
                (status.equals("RUNNING") ? ",started_at=COALESCE(started_at,?)" : "") +
                (status.equals("COMPLETED") || status.equals("FAILED") || status.equals("CANCELLED") ? ",completed_at=?" : "") +
                " WHERE job_id=?");
            int idx = 1;
            q.setParameter(idx++, status);
            q.setParameter(idx++, currentSymbol);
            q.setParameter(idx++, errorMessage);
            q.setParameter(idx++, now);
            if (status.equals("RUNNING")) q.setParameter(idx++, now);
            if (status.equals("COMPLETED") || status.equals("FAILED") || status.equals("CANCELLED")) q.setParameter(idx++, now);
            q.setParameter(idx, jobId);
            q.executeUpdate();
            return null;
        });
        jobStatusCache.put(jobId, status);
    }

    private void updateJobProgress(String jobId, String currentSymbol, String status, int processed, int total) {
        double pct = total > 0 ? Math.round(processed * 10000.0 / total) / 100.0 : 0;
        new TransactionTemplate(transactionManager).execute(s -> {
            em.createNativeQuery(
                "UPDATE processing_jobs SET current_symbol=?,processed_records=?,progress_percentage=?," +
                "status=?,last_updated_at=? WHERE job_id=?")
                .setParameter(1, currentSymbol).setParameter(2, processed)
                .setParameter(3, pct).setParameter(4, status)
                .setParameter(5, DateUtil.now()).setParameter(6, jobId).executeUpdate();
            return null;
        });
    }

    private void updateDetailStatus(String jobId, String symbol, String status, String step, String error) {
        new TransactionTemplate(transactionManager).execute(s -> {
            em.createNativeQuery(
                "UPDATE processing_job_details SET status=?,current_step=?,error_message=?,updated_at=? " +
                "WHERE job_id=? AND symbol=?")
                .setParameter(1, status).setParameter(2, step)
                .setParameter(3, error).setParameter(4, DateUtil.now())
                .setParameter(5, jobId).setParameter(6, symbol).executeUpdate();
            return null;
        });
    }

    private void markDetailSkipped(String jobId, String symbol, String reason) {
        new TransactionTemplate(transactionManager).execute(s -> {
            em.createNativeQuery(
                "UPDATE processing_job_details SET status='SKIPPED',skip_reason=?,updated_at=? " +
                "WHERE job_id=? AND symbol=?")
                .setParameter(1, reason).setParameter(2, DateUtil.now())
                .setParameter(3, jobId).setParameter(4, symbol).executeUpdate();
            return null;
        });
    }

    private void recalculateJobCounts(String jobId) {
        new TransactionTemplate(transactionManager).execute(s -> {
            em.createNativeQuery("""
                UPDATE processing_jobs SET
                    success_records = (SELECT COUNT(*) FROM processing_job_details WHERE job_id=? AND status='COMPLETED'),
                    failed_records = (SELECT COUNT(*) FROM processing_job_details WHERE job_id=? AND status='FAILED'),
                    skipped_records = (SELECT COUNT(*) FROM processing_job_details WHERE job_id=? AND status='SKIPPED'),
                    processed_records = (SELECT COUNT(*) FROM processing_job_details WHERE job_id=? AND status IN ('COMPLETED','FAILED','SKIPPED')),
                    progress_percentage = 100,
                    last_updated_at = ?
                WHERE job_id=?""")
                .setParameter(1, jobId).setParameter(2, jobId)
                .setParameter(3, jobId).setParameter(4, jobId)
                .setParameter(5, DateUtil.now()).setParameter(6, jobId).executeUpdate();
            return null;
        });
    }

    // ── Pipeline stage management ────────────────────────────────────────────

    private void initPipelineStages(String jobId) {
        Map<String, Map<String, String>> stages = new LinkedHashMap<>();
        for (String stage : PIPELINE_STAGES) {
            Map<String, String> info = new LinkedHashMap<>();
            info.put("status", "PENDING");
            info.put("error", "");
            info.put("started_at", "");
            info.put("completed_at", "");
            stages.put(stage, info);
        }
        savePipelineStages(jobId, stages);
    }

    private void updatePipelineStage(String jobId, String stage, String status, String error) {
        Map<String, Map<String, String>> stages = loadPipelineStages(jobId);
        Map<String, String> info = stages.getOrDefault(stage, new LinkedHashMap<>());
        info.put("status", status);
        if (error != null) info.put("error", error);
        String now = DateUtil.now();
        if ("RUNNING".equals(status)) info.put("started_at", now);
        if ("COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)) info.put("completed_at", now);
        stages.put(stage, info);
        savePipelineStages(jobId, stages);
        new TransactionTemplate(transactionManager).execute(s -> {
            em.createNativeQuery("UPDATE processing_jobs SET pipeline_stage=?, last_updated_at=? WHERE job_id=?")
                .setParameter(1, stage).setParameter(2, now).setParameter(3, jobId).executeUpdate();
            return null;
        });
    }

    private void completePipelineRemaining(String jobId, String fromStage) {
        Map<String, Map<String, String>> stages = loadPipelineStages(jobId);
        boolean found = false;
        String now = DateUtil.now();
        for (String stage : PIPELINE_STAGES) {
            if (stage.equals(fromStage)) found = true;
            if (found) {
                Map<String, String> info = stages.getOrDefault(stage, new LinkedHashMap<>());
                info.put("status", "COMPLETED");
                info.put("completed_at", now);
                info.put("error", "Skipped — no pending symbols");
                stages.put(stage, info);
            }
        }
        savePipelineStages(jobId, stages);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, String>> loadPipelineStages(String jobId) {
        try {
            Object result = em.createNativeQuery(
                "SELECT pipeline_stages_json FROM processing_jobs WHERE job_id=?")
                .setParameter(1, jobId).getSingleResult();
            if (result != null) {
                String json = result.toString();
                com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                return om.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Map<String, String>>>() {});
            }
        } catch (Exception e) {
            log.debug("loadPipelineStages fallback for {}: {}", jobId, e.getMessage());
        }
        Map<String, Map<String, String>> stages = new LinkedHashMap<>();
        for (String stage : PIPELINE_STAGES) {
            Map<String, String> info = new LinkedHashMap<>();
            info.put("status", "PENDING");
            info.put("error", "");
            stages.put(stage, info);
        }
        return stages;
    }

    private void savePipelineStages(String jobId, Map<String, Map<String, String>> stages) {
        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(stages);
            new TransactionTemplate(transactionManager).execute(s -> {
                em.createNativeQuery("UPDATE processing_jobs SET pipeline_stages_json=?, last_updated_at=? WHERE job_id=?")
                    .setParameter(1, json).setParameter(2, DateUtil.now()).setParameter(3, jobId).executeUpdate();
                return null;
            });
        } catch (Exception e) {
            log.warn("savePipelineStages failed for {}: {}", jobId, e.getMessage());
        }
    }

    public Map<String, Object> getPipelineStatus(String jobId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("job_id", jobId);

        try {
            Object[] row = (Object[]) em.createNativeQuery(
                "SELECT status, pipeline_stage, pipeline_stages_json, progress_percentage FROM processing_jobs WHERE job_id=?")
                .setParameter(1, jobId).getSingleResult();
            result.put("job_status", str(row[0]));
            result.put("current_stage", str(row[1]));
            result.put("progress_percentage", row[3] != null ? ((Number) row[3]).doubleValue() : 0);

            Map<String, Map<String, String>> stages = new LinkedHashMap<>();
            if (row[2] != null) {
                try {
                    com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                    stages = om.readValue(row[2].toString(),
                        new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Map<String, String>>>() {});
                } catch (Exception ignored) {}
            }
            if (stages.isEmpty()) {
                for (String stage : PIPELINE_STAGES) {
                    Map<String, String> info = new LinkedHashMap<>();
                    info.put("status", "PENDING");
                    stages.put(stage, info);
                }
            }

            List<Map<String, Object>> stageList = new ArrayList<>();
            int idx = 0;
            for (String stage : PIPELINE_STAGES) {
                Map<String, Object> si = new LinkedHashMap<>();
                si.put("stage", stage);
                si.put("order", idx++);
                si.put("label", stageLabel(stage));
                Map<String, String> info = stages.getOrDefault(stage, Map.of());
                si.put("status", info.getOrDefault("status", "PENDING"));
                si.put("error", info.getOrDefault("error", ""));
                si.put("started_at", info.getOrDefault("started_at", ""));
                si.put("completed_at", info.getOrDefault("completed_at", ""));
                stageList.add(si);
            }
            result.put("stages", stageList);

            int completed = (int) stageList.stream().filter(s -> "COMPLETED".equals(s.get("status"))).count();
            result.put("stages_completed", completed);
            result.put("stages_total", PIPELINE_STAGES.size());
            result.put("pipeline_progress", Math.round(completed * 10000.0 / PIPELINE_STAGES.size()) / 100.0);
        } catch (Exception e) {
            result.put("error", "Job not found: " + jobId);
        }
        return result;
    }

    private String stageLabel(String stage) {
        return switch (stage) {
            case STAGE_UPLOAD -> "Upload Stock";
            case STAGE_STOCK_DETAILS -> "Uploaded Stock Details";
            case STAGE_CURRENT_TOP10 -> "Current Top 10";
            case STAGE_PREVIOUS_TOP10 -> "Previous Top 10";
            case STAGE_AVG_TREND -> "Avg Return Trend";
            default -> stage;
        };
    }

    // ── Public API methods ───────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public Map<String, Object> getJobStatus(String jobId) {
        try {
            Object[] row = (Object[]) em.createNativeQuery(
                "SELECT job_id,user_id,institution_id,file_name,upload_type,total_records," +
                "processed_records,success_records,failed_records,skipped_records,duplicate_records," +
                "current_symbol,current_step,progress_percentage,status,error_message," +
                "started_at,last_updated_at,completed_at,created_at FROM processing_jobs WHERE job_id=?")
                .setParameter(1, jobId).getSingleResult();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("job_id", str(row[0]));
            result.put("user_id", num(row[1]));
            result.put("institution_id", num(row[2]));
            result.put("file_name", str(row[3]));
            result.put("upload_type", str(row[4]));
            result.put("total_records", num(row[5]));
            result.put("processed_records", num(row[6]));
            result.put("success_records", num(row[7]));
            result.put("failed_records", num(row[8]));
            result.put("skipped_records", num(row[9]));
            result.put("duplicate_records", num(row[10]));
            result.put("current_symbol", str(row[11]));
            result.put("current_step", str(row[12]));
            result.put("progress_percentage", row[13] != null ? ((Number) row[13]).doubleValue() : 0);
            result.put("status", str(row[14]));
            result.put("error_message", str(row[15]));
            result.put("started_at", str(row[16]));
            result.put("last_updated_at", str(row[17]));
            result.put("completed_at", str(row[18]));
            result.put("created_at", str(row[19]));

            int pending = num(row[5]) - num(row[6]);
            result.put("pending_records", Math.max(0, pending));
            return result;
        } catch (Exception e) {
            return Map.of("error", "Job not found: " + jobId);
        }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getJobs(Long userId, Long institutionId) {
        List<Object[]> rows = em.createNativeQuery(
            "SELECT job_id,file_name,total_records,processed_records,success_records," +
            "failed_records,skipped_records,progress_percentage,status,created_at,completed_at " +
            "FROM processing_jobs WHERE institution_id=?" +
            (userId != null ? " AND user_id=" + userId : "") +
            " ORDER BY created_at DESC LIMIT 5")
            .setParameter(1, institutionId).getResultList();

        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> job = new LinkedHashMap<>();
            job.put("job_id", str(r[0]));
            job.put("file_name", str(r[1]));
            job.put("total_records", num(r[2]));
            job.put("processed_records", num(r[3]));
            job.put("success_records", num(r[4]));
            job.put("failed_records", num(r[5]));
            job.put("skipped_records", num(r[6]));
            job.put("progress_percentage", r[7] != null ? ((Number) r[7]).doubleValue() : 0);
            job.put("status", str(r[8]));
            job.put("created_at", str(r[9]));
            job.put("completed_at", str(r[10]));
            result.add(job);
        }
        return result;
    }

    public void pauseJob(String jobId) {
        pauseFlags.put(jobId, true);
    }

    public void resumeJob(String jobId, Long userId, Long institutionId) {
        pauseFlags.put(jobId, false);
        cancelFlags.put(jobId, false);
        String currentStatus = jobStatusCache.getOrDefault(jobId, "");
        if ("PAUSED".equals(currentStatus) || "FAILED".equals(currentStatus)) {
            startBackgroundProcessing(jobId, userId, institutionId);
        }
    }

    public void cancelJob(String jobId) {
        cancelFlags.put(jobId, true);
        pauseFlags.remove(jobId);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getFailedRecords(String jobId) {
        return getDetailRecordsByStatus(jobId, "FAILED");
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getSkippedRecords(String jobId) {
        return getDetailRecordsByStatus(jobId, "SKIPPED");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getDetailRecordsByStatus(String jobId, String status) {
        List<Object[]> rows = em.createNativeQuery(
            "SELECT symbol,trading_symbol,exchange,status,current_step,retry_count," +
            "skip_reason,error_message,updated_at FROM processing_job_details " +
            "WHERE job_id=? AND status=? ORDER BY updated_at DESC")
            .setParameter(1, jobId).setParameter(2, status).getResultList();

        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] r : rows) {
            result.add(Map.of(
                "symbol", str(r[0]),
                "trading_symbol", str(r[1]),
                "exchange", str(r[2]),
                "status", str(r[3]),
                "current_step", str(r[4]),
                "retry_count", num(r[5]),
                "skip_reason", str(r[6]),
                "error_message", str(r[7]),
                "updated_at", str(r[8])
            ));
        }
        return result;
    }

    public byte[] exportDetailsCsv(String jobId, String status) {
        List<Map<String, Object>> records = "FAILED".equals(status)
            ? getFailedRecords(jobId) : getSkippedRecords(jobId);
        StringBuilder sb = new StringBuilder("Symbol,Status,Step,Reason,Error,Updated\n");
        for (Map<String, Object> r : records) {
            sb.append(csvField(r.get("symbol"))).append(",")
              .append(csvField(r.get("status"))).append(",")
              .append(csvField(r.get("current_step"))).append(",")
              .append(csvField(r.get("skip_reason"))).append(",")
              .append(csvField(r.get("error_message"))).append(",")
              .append(csvField(r.get("updated_at"))).append("\n");
        }
        return sb.toString().getBytes();
    }

    private String csvField(Object v) {
        String s = v == null ? "" : String.valueOf(v);
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private String str(Object v) { return v == null ? "" : String.valueOf(v); }
    private int num(Object v) { return v == null ? 0 : ((Number) v).intValue(); }
}
