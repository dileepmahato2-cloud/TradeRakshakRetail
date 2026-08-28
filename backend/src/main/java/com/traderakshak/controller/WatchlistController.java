package com.traderakshak.controller;
import com.traderakshak.dto.response.ApiResponse;
import com.traderakshak.entity.User;
import com.traderakshak.util.DateUtil;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/watchlists") @RequiredArgsConstructor
@Tag(name="Watchlist", description="Watchlists + price alerts")
public class WatchlistController {
    @PersistenceContext private EntityManager em;

    @GetMapping
    public ResponseEntity<ApiResponse<List<?>>> list(@AuthenticationPrincipal User u) {
        return ResponseEntity.ok(ApiResponse.ok(em.createNativeQuery("SELECT * FROM watchlists WHERE user_id=? ORDER BY id DESC","")
            .setParameter(1, u.getId()).getResultList()));
    }
    @PostMapping
    public ResponseEntity<ApiResponse<Map<String,Object>>> create(@RequestBody CreateReq req, @AuthenticationPrincipal User u) {
        em.createNativeQuery("INSERT INTO watchlists(user_id,institution_id,strategy_id,name,created_at) VALUES(?,?,?,?,?)")
            .setParameter(1,u.getId()).setParameter(2,u.getInstitutionId()).setParameter(3,req.getStrategyId()).setParameter(4,req.getName()).setParameter(5,DateUtil.now()).executeUpdate();
        return ResponseEntity.ok(ApiResponse.ok("Watchlist created.",null));
    }
    @PostMapping("/{id}/add")
    public ResponseEntity<ApiResponse<Void>> addStock(@PathVariable Long id, @RequestBody AddStockReq req, @AuthenticationPrincipal User u) {
        Long allowed = ((Number) em.createNativeQuery("SELECT COUNT(*) FROM watchlists WHERE id=? AND user_id=?")
            .setParameter(1, id).setParameter(2, u.getId()).getSingleResult()).longValue();
        if (allowed <= 0) return ResponseEntity.status(403).body(ApiResponse.error("WATCHLIST-403", "Watchlist not found for this user."));
        em.createNativeQuery("INSERT INTO watchlist_items(watchlist_id,exchange,symbol,company_name,sector,cmp,added_at) VALUES(?,?,?,?,?,?,?) ON CONFLICT DO NOTHING")
            .setParameter(1,id).setParameter(2,req.getExchange()).setParameter(3,req.getSymbol())
            .setParameter(4,req.getCompanyName()).setParameter(5,req.getSector()).setParameter(6,0).setParameter(7,DateUtil.now()).executeUpdate();
        return ResponseEntity.ok(ApiResponse.ok(req.getSymbol()+" added.",null));
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id, @AuthenticationPrincipal User u) {
        em.createNativeQuery("DELETE FROM watchlist_items WHERE watchlist_id IN (SELECT id FROM watchlists WHERE id=? AND user_id=?)")
            .setParameter(1,id).setParameter(2,u.getId()).executeUpdate();
        em.createNativeQuery("DELETE FROM watchlists WHERE id=? AND user_id=?").setParameter(1,id).setParameter(2,u.getId()).executeUpdate();
        return ResponseEntity.ok(ApiResponse.ok("Deleted.",null));
    }
    @Data static class CreateReq { private String name; private Long strategyId; }
    @Data static class AddStockReq { private String exchange,symbol,companyName,sector; }
}
