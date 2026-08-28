package com.traderakshak.controller;
import com.traderakshak.dto.response.ApiResponse;
import com.traderakshak.entity.*;
import com.traderakshak.repository.*;
import com.traderakshak.util.DateUtil;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/strategies") @RequiredArgsConstructor
@Tag(name="Strategy Builder", description="Strategy CRUD + screener run")
public class StrategyController {
    private final StrategyRepository repo;
    @PersistenceContext private EntityManager em;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Strategy>>> list(@AuthenticationPrincipal User u) {
        return ResponseEntity.ok(ApiResponse.ok(repo.findByStatusOrderByIdDesc("active")));
    }
    @PostMapping
    public ResponseEntity<ApiResponse<Strategy>> save(@RequestBody Strategy req, @AuthenticationPrincipal User u) {
        if (req.getName() == null || req.getName().isBlank())
            return ResponseEntity.badRequest().body(ApiResponse.error("STR-001","Strategy name is required."));
        // Fix: enforce target >= stop_loss * rr_min (port of Python validation)
        if (req.getTargetPct() != null && req.getStopLossPct() != null && req.getRrMin() != null
                && req.getTargetPct() < req.getStopLossPct() * req.getRrMin()) {
            req.setTargetPct(Math.round(req.getStopLossPct() * req.getRrMin() * 100.0) / 100.0);
        }
        req.setUserId(u.getId()); req.setInstitutionId(1L);
        req.setStatus("active"); req.setCreatedAt(DateUtil.now());
        return ResponseEntity.ok(ApiResponse.ok("Strategy saved.", repo.save(req)));
    }
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Strategy>> update(@PathVariable Long id, @RequestBody Strategy req, @AuthenticationPrincipal User u) {
        Strategy existing = repo.findById(id).orElseThrow();
        existing.setName(req.getName()); existing.setDescription(req.getDescription());
        existing.setLogic(req.getLogic()); existing.setEntryLogic(req.getEntryLogic());
        existing.setExitLogic(req.getExitLogic()); existing.setStopLossPct(req.getStopLossPct());
        existing.setTargetPct(req.getTargetPct()); existing.setHoldingDays(req.getHoldingDays());
        existing.setRrMin(req.getRrMin()); existing.setMaxPositions(req.getMaxPositions());
        existing.setRiskPerTradePct(req.getRiskPerTradePct());
        return ResponseEntity.ok(ApiResponse.ok("Strategy updated.", repo.save(existing)));
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id, @AuthenticationPrincipal User u) {
        // R2: soft-delete — disable not delete
        repo.findById(id).ifPresent(s -> { s.setStatus("disabled"); s.setDisabledReason("User deleted"); repo.save(s); });
        return ResponseEntity.ok(ApiResponse.ok("Strategy disabled.", null));
    }
}
