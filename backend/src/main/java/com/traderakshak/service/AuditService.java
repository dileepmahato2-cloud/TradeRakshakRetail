package com.traderakshak.service;

import com.traderakshak.entity.ActivityLog;
import com.traderakshak.entity.ConsentLog;
import com.traderakshak.repository.ActivityLogRepository;
import com.traderakshak.util.DateUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Immutable audit trail — append-only.
 * R6: activity_logs + consent_log are protected by DB triggers in Flyway migration.
 */
@Service
public class AuditService {

    private final ActivityLogRepository activityLogRepository;
    private static final org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(AuditService.class);

    public AuditService(ActivityLogRepository activityLogRepository) {
        this.activityLogRepository = activityLogRepository;
    }

    @PersistenceContext
    private EntityManager em;

    /**
     * Write to activity_logs (append-only — never deletable by users).
     * BRD §17, R6.
     */
    @Transactional
    public void logActivity(Long userId, String module, String action,
                            String result, String detail, String ip, String userAgent) {
        try {
            ActivityLog log = ActivityLog.builder()
                .userId(userId)
                .institutionId(1L)
                .module(module)
                .action(action)
                .result(result)
                .detail(detail != null ? detail.substring(0, Math.min(detail.length(), 500)) : "")
                .ip(ip)
                .userAgent(userAgent != null ? userAgent.substring(0, Math.min(userAgent.length(), 200)) : "")
                .createdAt(DateUtil.now())
                .build();
            activityLogRepository.save(log);
        } catch (Exception e) {
            // Audit log failures must never crash the main request
            log.error("Failed to write activity log: {}", e.getMessage());
        }
    }

    /**
     * R3: Log explicit user consent (SEBI disclaimer, payment, trade, etc.)
     */
    @Transactional
    public void logConsent(Long userId, String action, String consentText, String ip, boolean accepted) {
        try {
            ConsentLog cl = ConsentLog.builder()
                .userId(userId)
                .action(action)
                .consentText(consentText != null
                    ? consentText.substring(0, Math.min(consentText.length(), 500)) : "")
                .accepted(accepted ? 1 : 0)
                .ip(ip)
                .createdAt(DateUtil.now())
                .build();
            em.persist(cl);
        } catch (Exception e) {
            log.error("Failed to write consent log: {}", e.getMessage());
        }
    }
}
