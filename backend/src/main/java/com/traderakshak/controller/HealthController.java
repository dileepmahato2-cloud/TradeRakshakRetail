package com.traderakshak.controller;
import com.traderakshak.dto.response.ApiResponse;
import com.traderakshak.entity.User;
import com.traderakshak.util.DateUtil;
import com.traderakshak.util.UserMessage;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/admin/health") @RequiredArgsConstructor
@Tag(name="Admin Health", description="System health check")
public class HealthController {
    @PersistenceContext private EntityManager em;

    @GetMapping @PreAuthorize("hasAnyRole('ADMIN','PLATFORM_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String,Object>>> health() {
        double score = 100.0; List<String> issues = new ArrayList<>();
        String dbStatus="ok", dataStatus="ok", mlStatus="ok", backupStatus="ok";
        try { em.createNativeQuery("SELECT COUNT(*) FROM users").getSingleResult(); }
        catch (Exception e) { dbStatus=UserMessage.of(e, "not reachable"); score-=30; issues.add("DB:"+UserMessage.of(e)); }
        try {
            Object ld = em.createNativeQuery("SELECT MAX(trade_date) FROM market_data").getSingleResult();
            if (ld == null) { dataStatus="no_data"; score-=10; issues.add("No market data"); }
            else {
                long days = DateUtil.daysBetween(ld.toString(), DateUtil.today());
                if (days > 7) { dataStatus="stale"; score-=10; issues.add("Data "+days+" days old"); }
            }
        } catch (Exception e) { dataStatus="error"; score-=10; }
        try { Object ml=em.createNativeQuery("SELECT COUNT(*) FROM ml_models WHERE status='active'").getSingleResult(); if(((Number)ml).longValue()==0){mlStatus="no_models";score-=5;} }
        catch (Exception e) { mlStatus="error"; }
        try { Object bk=em.createNativeQuery("SELECT MAX(created_at) FROM backup_registry").getSingleResult(); if(bk==null){backupStatus="never";score-=5;issues.add("No backup on record");} }
        catch (Exception e) { backupStatus="unknown"; }
        score = Math.max(0, Math.min(100, score));
        String level = score>=90 ? "Healthy" : score>=70 ? "Warning" : "Critical";
        String color = score>=90 ? "#22c55e" : score>=70 ? "#f59e0b" : "#ef4444";
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("score",score); result.put("level",level); result.put("color",color);
        result.put("db_status",dbStatus); result.put("data_status",dataStatus);
        result.put("ml_status",mlStatus); result.put("backup_status",backupStatus);
        result.put("issues",issues); result.put("checked_at",DateUtil.now());
        return ResponseEntity.ok(ApiResponse.ok(result));
    }
}
