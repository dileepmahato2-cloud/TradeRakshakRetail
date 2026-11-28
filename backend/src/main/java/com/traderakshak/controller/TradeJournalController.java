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
    private final com.traderakshak.service.TradeJournalService tradeJournal;

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
    // ======================================================================
    //  CONSOLIDATED TRADE JOURNAL  (BRD sections 8, 9, 10)
    //
    //  One journal for every trade: paper, real, manual, automated, stop-loss
    //  and trailing-stop exits. trading_journal is the system of record; the
    //  older trade_journal is read for history and never written to, so no
    //  past entry is lost and nothing new widens the split.
    //
    //  The legacy routes above are left exactly as they are. They still serve
    //  anything that calls them, and removing them would break a caller to
    //  tidy a URL.
    // ======================================================================

    @GetMapping("/v2")
    @Operation(summary = "The whole journal, filterable, with totals that match the rows shown")
    public ResponseEntity<ApiResponse<Map<String, Object>>> journalV2(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String strategy,
            @RequestParam(required = false) String mode,
            // ALL, SYSTEM or MANUAL. Section 9 requires manual trades to stay distinguishable
            // from system-generated ones.
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "500") int limit,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(tradeJournal.list(
            user.getId(), from, to, symbol, strategy, mode, source, status, limit)));
    }

    @PostMapping("/v2/manual")
    @Operation(summary = "Record a trade by hand. Charges are computed, not accepted from the "
                       + "caller, and no broker order is placed.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> saveManualV2(
            @RequestBody Map<String, Object> req, @AuthenticationPrincipal User user) {
        Map<String, Object> result = tradeJournal.saveManual(
            user.getId(), user.getInstitutionId(), user.getName(), req);
        return Boolean.FALSE.equals(result.get("success"))
            ? ResponseEntity.badRequest().body(
                ApiResponse.error("JOURNAL-INVALID", String.valueOf(result.get("message"))))
            : ResponseEntity.ok(ApiResponse.ok(result));
    }

    @PostMapping("/v2/{id}/close")
    @Operation(summary = "Close an open manual position, computing exit charges and net P&L")
    public ResponseEntity<ApiResponse<Map<String, Object>>> closeManualV2(
            @PathVariable Long id, @RequestBody Map<String, Object> req,
            @AuthenticationPrincipal User user) {
        double exit = req.get("exitPrice") instanceof Number n ? n.doubleValue()
            : parseDouble(String.valueOf(req.get("exitPrice")));
        Map<String, Object> result = tradeJournal.closeManual(user.getId(), id, exit,
            str(req.get("exitDate")), str(req.get("closingRemark")));
        return Boolean.FALSE.equals(result.get("success"))
            ? ResponseEntity.badRequest().body(
                ApiResponse.error("JOURNAL-CLOSE", String.valueOf(result.get("message"))))
            : ResponseEntity.ok(ApiResponse.ok(result));
    }

    @GetMapping("/v2/symbols")
    @Operation(summary = "Symbol suggestions for the manual-entry box, prefix matches first")
    public ResponseEntity<ApiResponse<java.util.List<Map<String, Object>>>> symbolSearch(
            @RequestParam(name = "q", defaultValue = "") String q,
            @RequestParam(defaultValue = "12") int limit) {
        return ResponseEntity.ok(ApiResponse.ok(tradeJournal.searchSymbols(q, limit)));
    }

    @GetMapping("/v2/open")
    @Operation(summary = "Open manual positions, marked to market, net of the exit charges still to pay")
    public ResponseEntity<ApiResponse<Map<String, Object>>> openManualV2(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(tradeJournal.openManualPositions(user.getId())));
    }

    @DeleteMapping("/v2/{id}")
    @Operation(summary = "Delete a MANUAL entry. A system-generated trade cannot be deleted here.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteManualV2(
            @PathVariable Long id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(tradeJournal.deleteManual(user.getId(), id)));
    }

    @GetMapping("/v2/export")
    @Operation(summary = "The journal as CSV, with the filters the screen is showing")
    public ResponseEntity<byte[]> exportV2(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String strategy,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String status,
            @AuthenticationPrincipal User user) {
        byte[] csv = tradeJournal.exportCsv(user.getId(), from, to, symbol, strategy, mode, source, status);
        org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
        h.setContentType(org.springframework.http.MediaType.parseMediaType("text/csv"));
        h.setContentDisposition(org.springframework.http.ContentDisposition.attachment()
            .filename("TradeRakshak_Trade_Journal_" + DateUtil.today() + ".csv").build());
        return ResponseEntity.ok().headers(h).body(csv);
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }
    private static double parseDouble(String s) {
        try { return s == null || s.isBlank() ? 0 : Double.parseDouble(s); }
        catch (Exception e) { return 0; }
    }

}
