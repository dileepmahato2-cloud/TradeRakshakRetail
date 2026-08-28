package com.traderakshak.entity;
import jakarta.persistence.*;
import lombok.*;
@Entity @Table(name="sector_data") @Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SectorData {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    private String sector;
    @Column(name="trade_date") private String tradeDate;
    @Column(name="weekly_rsi") private Double weeklyRsi;
    @Column(name="change_pct") private Double changePct;
    @Column(name="above_ema20") private Integer aboveEma20;
    @Column(name="above_ema50") private Integer aboveEma50;
    @Column(name="volume_ratio") private Double volumeRatio;
    @Column(name="advance_count") private Integer advanceCount;
    @Column(name="decline_count") private Integer declineCount;
    @Column(name="advance_decline_ratio") private Double advanceDeclineRatio;
    @Column(name="trend_score") private Integer trendScore;
    @Column(name="trend_phase") private String trendPhase;
    private String signal;
    private String source;
    @Column(name="created_at") private String createdAt;
}
