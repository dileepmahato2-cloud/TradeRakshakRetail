package com.traderakshak.controller;

import com.traderakshak.dto.response.ApiResponse;
import com.traderakshak.entity.TradeJournal;
import com.traderakshak.entity.User;
import com.traderakshak.repository.TradeJournalRepository;
import com.traderakshak.service.AuditService;
import com.traderakshak.util.DateUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** Replaces Python /journal routes */
@RestController
@RequestMapping("/journal")
@RequiredArgsConstructor
@Tag(name = "Trade Journal", description = "Trade journal — BRD §10")
public class TradeJournalController {

    private final TradeJournalRepository journalRepo;
    private final AuditService auditService;

    @GetMapping
    @Operation(summary = "List journal entries. Admin sees all; user sees own.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(
            @AuthenticationPrincipal User user) {

        List<TradeJournal> rows = user.isAdmin()
            ? journalRepo.findAllByOrderByIdDesc()
            : journalRepo.findByUserIdOrderByIdDesc(user.getId());

        int total = rows.size();
        long wins    = rows.stream().filter(r -> "Win".equals(r.getResultStatus())).count();
        long losses  = rows.stream().filter(r -> "Loss".equals(r.getResultStatus())).count();
        double netPnl = rows.stream().mapToDouble(r -> r.getNetPnl() != null ? r.getNetPnl() : 0).sum();

        Map<String, Object> summary = Map.of(
            "total",    total, "wins", wins, "losses", losses,
            "scratches", total - wins - losses,
            "net_pnl",  Math.round(netPnl * 100.0) / 100.0,
            "win_rate", total > 0 ? Math.round((double) wins / total * 10000.0) / 100.0 : 0);

        return ResponseEntity.ok(ApiResponse.ok(Map.of("trades", rows, "summary", summary)));
    }

    @PostMapping
    @Operation(summary = "Save journal entry — entry_remark is mandatory (BRD §10)")
    public ResponseEntity<ApiResponse<TradeJournal>> save(
            @Valid @RequestBody TradeJournal req,
            @AuthenticationPrincipal User user) {

        // Bug fix from original: entry_remark validated here too (not just DB constraint)
        if (req.getEntryRemark() == null || req.getEntryRemark().isBlank()) {
            return ResponseEntity.badRequest().body(
                ApiResponse.error("JRN-001", "Entry remark is MANDATORY. (BRD §10)"));
        }

        // Generate trade ID
        long seq = journalRepo.countByUserId(user.getId()) + 1;
        String tradeId = String.format("TR-U%04d-%s-%s-%03d",
            user.getId(), req.getSymbol().substring(0, Math.min(req.getSymbol().length(), 8)),
            DateUtil.today().replace("-", ""), seq);

        // Compute derived fields
        double inv  = (req.getPurchasePrice() != null ? req.getPurchasePrice() : 0) *
                      (req.getQtyBuy() != null ? req.getQtyBuy() : 0);
        double recv = (req.getSellPrice() != null ? req.getSellPrice() : 0) *
                      (req.getQtySell() != null ? req.getQtySell() : req.getQtyBuy() != null ? req.getQtyBuy() : 0);
        double gp   = recv - inv;
        double brok = (inv + recv) * 0.0003;
        double net  = gp - brok;
        double ret  = req.getPurchasePrice() != null && req.getPurchasePrice() > 0 && req.getSellPrice() != null
                      ? (req.getSellPrice() - req.getPurchasePrice()) / req.getPurchasePrice() * 100 : 0;
        long holding = req.getSaleDate() != null && req.getTradeDate() != null
                       ? DateUtil.daysBetween(req.getTradeDate(), req.getSaleDate()) : 0;
        String result = ret > 2 ? "Win" : net < 0 ? "Loss" : "Scratch";

        req.setTradeId(tradeId);
        req.setUserId(user.getId());
        req.setTotalInvested(Math.round(inv * 100.0) / 100.0);
        req.setTotalReceived(Math.round(recv * 100.0) / 100.0);
        req.setBrokerageEst(Math.round(brok * 100.0) / 100.0);
        req.setGrossPnl(Math.round(gp * 100.0) / 100.0);
        req.setNetPnl(Math.round(net * 100.0) / 100.0);
        req.setReturnPct(Math.round(ret * 100.0) / 100.0);
        req.setHoldingDays((int) holding);
        req.setResultStatus(result);
        req.setCreatedAt(DateUtil.now());

        TradeJournal saved = journalRepo.save(req);
        auditService.logActivity(user.getId(), "Trade Journal", "Save", "success",
            req.getSymbol() + " TID=" + tradeId, "", "");

        return ResponseEntity.ok(ApiResponse.ok("Trade journal entry saved.", saved));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete journal entry")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable Long id,
            @AuthenticationPrincipal User user) {
        journalRepo.deleteById(id);
        return ResponseEntity.ok(ApiResponse.ok("Deleted.", null));
    }
}
