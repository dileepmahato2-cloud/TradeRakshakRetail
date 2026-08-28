package com.traderakshak.controller;

import com.traderakshak.dto.response.ApiResponse;
import com.traderakshak.entity.User;
import com.traderakshak.service.SectorTrendService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** Replaces Python /sector-intelligence + /api/v1/sector-signals routes */
@RestController
@RequestMapping("/sector-intelligence")
@RequiredArgsConstructor
@Tag(name = "Sector Intelligence", description = "AI-powered sector trend analysis")
public class SectorIntelligenceController {

    private final SectorTrendService sectorService;

    @GetMapping
    @Operation(summary = "All sectors — BRD §11 Sahi App methodology")
    public ResponseEntity<ApiResponse<Map<String, Object>>> allSectors(
            @AuthenticationPrincipal User user) {

        List<Map<String, Object>> results = sectorService.allSectors();
        Map<String, Object> ms = sectorService.marketScore(results);

        // Admins see score_breakdown; users see summary only
        if (!user.isAdmin()) {
            results.forEach(s -> {
                s.remove("score_breakdown");
                s.remove("conditions");
            });
        }

        results.sort(Comparator.comparingLong(s ->
            -(Long) s.getOrDefault("trend_score", 0L)));

        return ResponseEntity.ok(ApiResponse.ok(
            Map.of("sectors", results, "market_score", ms,
                   "computed_at", com.traderakshak.util.DateUtil.now())));
    }

    @GetMapping("/{sector}")
    @Operation(summary = "Single sector deep analysis")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sectorDetail(
            @PathVariable String sector,
            @AuthenticationPrincipal User user) {

        Map<String, Object> result = sectorService.sectorTrend(sector);
        if (!user.isAdmin()) {
            result.remove("score_breakdown");
            result.remove("conditions");
        }
        return ResponseEntity.ok(ApiResponse.ok(result));
    }
}
