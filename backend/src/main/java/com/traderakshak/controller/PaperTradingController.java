package com.traderakshak.controller;
import com.traderakshak.dto.response.ApiResponse;
import com.traderakshak.entity.*;
import com.traderakshak.repository.*;
import com.traderakshak.service.AuditService;
import com.traderakshak.util.DateUtil;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/paper-trades") @RequiredArgsConstructor @Slf4j
@Tag(name="Paper Trading", description="Paper trade entry, exit, trailing SL")
public class PaperTradingController {
    private final PaperTradeRepository repo;
    private final StrategyRepository stratRepo;
    private final AuditService auditService;
    @PersistenceContext private EntityManager em;

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String,Object>>> list(
            @RequestParam(defaultValue="Default") String portfolio,
            @AuthenticationPrincipal User u) {
        List<PaperTrade> open   = repo.findByUserIdAndStatusAndPortfolioNameOrderByIdDesc(u.getId(), "open", portfolio);
        List<PaperTrade> closed = repo.findByUserIdAndStatusAndPortfolioNameOrderByIdDesc(u.getId(), "closed", portfolio);
        Map<String,Object> summary = new LinkedHashMap<>();
        summary.put("open_count",     open.size());
        summary.put("total_invested", open.stream().mapToDouble(t -> (t.getEntryPrice()!=null?t.getEntryPrice():0)*(t.getQty()!=null?t.getQty():0)).sum());
        summary.put("unrealised_pnl", open.stream().mapToDouble(t -> t.getPnl()!=null?t.getPnl():0).sum());
        summary.put("realised_pnl",   closed.stream().mapToDouble(t -> t.getPnl()!=null?t.getPnl():0).sum());
        summary.put("total_closed",   closed.size());
        summary.put("wins",           closed.stream().filter(t -> t.getPnl()!=null && t.getPnl()>0).count());
        return ResponseEntity.ok(ApiResponse.ok(Map.of("open_trades",open,"closed_trades",closed,"summary",summary)));
    }

    @PostMapping("/buy")
    public ResponseEntity<ApiResponse<PaperTrade>> buy(@RequestBody BuyRequest req, @AuthenticationPrincipal User u) {
        if (!req.isConsentTrade())
            return ResponseEntity.badRequest().body(ApiResponse.error("CONSENT-001","Please confirm trade consent."));

        Strategy st = stratRepo.findById(req.getStrategyId()).orElse(null);
        int maxPos = st != null && st.getMaxPositions() != null ? st.getMaxPositions() : 5;
        int maxSec = st != null && st.getMaxPerSector() != null ? st.getMaxPerSector() : 2;

        if (repo.countOpenByUserAndPortfolio(u.getId(), req.getPortfolioName()) >= maxPos)
            return ResponseEntity.badRequest().body(ApiResponse.error("PT-001","Max "+maxPos+" positions reached."));
        if (req.getSector() != null && !req.getSector().isBlank()
                && repo.countOpenByUserAndSectorAndPortfolio(u.getId(), req.getSector(), req.getPortfolioName()) >= maxSec)
            return ResponseEntity.badRequest().body(ApiResponse.error("PT-002","Max "+maxSec+" positions in this sector."));

        double slp  = st != null && st.getStopLossPct() != null ? st.getStopLossPct() : 5.0;
        double tgtp = st != null && st.getTargetPct()   != null ? st.getTargetPct()   : 10.0;
        double sl   = Math.round(req.getPrice() * (1 - slp/100) * 100) / 100.0;
        double tgt  = Math.round(req.getPrice() * (1 + tgtp/100) * 100) / 100.0;
        double rr   = req.getPrice() > sl ? Math.round((tgt-req.getPrice())/(req.getPrice()-sl)*100)/100.0 : 0;

        long seq = repo.count() + 1;
        String tradeId = String.format("TR-U%04d-%s-%s-%03d",
            u.getId(), req.getSymbol().substring(0,Math.min(req.getSymbol().length(),8)),
            DateUtil.today().replace("-",""), seq);

        PaperTrade t = PaperTrade.builder()
            .tradeId(tradeId).strategyId(req.getStrategyId()).userId(u.getId())
            .tradeType("BUY").exchange(req.getExchange()).symbol(req.getSymbol())
            .sector(req.getSector()).segment(req.getSegment()).qty(req.getQty())
            .entryPrice(req.getPrice()).entryDate(DateUtil.today())
            .stopLoss(sl).stopLossPct(slp).target(tgt).targetPct(tgtp).rrRatio(rr)
            .trailingSlLevel(sl).portfolioName(req.getPortfolioName())
            .brokerUsed(req.getBrokerUsed()).status("open").source(req.getSource())
            .build();
        repo.save(t);
        auditService.logActivity(u.getId(),"Paper Trading","BUY","success",req.getSymbol()+" TID="+tradeId,"","");
        return ResponseEntity.ok(ApiResponse.ok("Trade entered.",t));
    }

    @PostMapping("/{id}/exit")
    public ResponseEntity<ApiResponse<PaperTrade>> exit(@PathVariable Long id,
            @RequestBody ExitRequest req, @AuthenticationPrincipal User u) {
        if (!req.isConsentExit())
            return ResponseEntity.badRequest().body(ApiResponse.error("CONSENT-001","Please confirm exit consent."));
        PaperTrade t = repo.findById(id).filter(x -> Objects.equals(x.getUserId(), u.getId())).orElseThrow();
        double pnl    = Math.round((req.getExitPrice()-t.getEntryPrice())*t.getQty()*100)/100.0;
        double pnlPct = Math.round((req.getExitPrice()-t.getEntryPrice())/t.getEntryPrice()*10000)/100.0;
        long holding  = DateUtil.daysBetween(t.getEntryDate(), DateUtil.today());
        t.setExitPrice(req.getExitPrice()); t.setExitDate(DateUtil.today());
        t.setExitType(req.getExitType()); t.setPnl(pnl); t.setPnlPct(pnlPct);
        t.setHoldingDays((int)holding); t.setStatus("closed");
        repo.save(t);
        auditService.logActivity(u.getId(),"Paper Trading","EXIT","success",t.getSymbol()+" PnL="+pnl,"","");
        return ResponseEntity.ok(ApiResponse.ok("Trade exited. PnL: ₹"+pnl, t));
    }

    @Data static class BuyRequest {
        private Long strategyId; private String exchange; private String symbol;
        private String sector; private String segment; private Double price;
        private Integer qty; private String portfolioName="Default";
        private String brokerUsed="Dhan"; private String source="manual";
        private Long competitionId; private boolean consentTrade;
    }
    @Data static class ExitRequest {
        private Double exitPrice; private String exitType="Manual";
        private String remark; private boolean consentExit;
    }
}
