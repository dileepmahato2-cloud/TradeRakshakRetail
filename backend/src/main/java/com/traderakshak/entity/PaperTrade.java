package com.traderakshak.entity;
import jakarta.persistence.*;
import lombok.*;
@Entity @Table(name="paper_trades") @Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PaperTrade {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="trade_id",unique=true) private String tradeId;
    @Column(name="strategy_id") private Long strategyId;
    @Column(name="user_id") private Long userId;
    @Column(name="institution_id") @Builder.Default private Long institutionId=1L;
    @Column(name="competition_id") private Long competitionId;
    @Column(name="trade_type") private String tradeType;
    private String exchange;
    private String symbol;
    private String sector;
    @Builder.Default private String segment="EQ-SWG";
    private Integer qty;
    @Column(name="entry_price") private Double entryPrice;
    @Column(name="entry_date") private String entryDate;
    @Column(name="stop_loss") private Double stopLoss;
    @Column(name="stop_loss_pct") private Double stopLossPct;
    private Double target;
    @Column(name="target_pct") private Double targetPct;
    @Column(name="rr_ratio") private Double rrRatio;
    @Column(name="trailing_sl_level") private Double trailingSlLevel;
    @Column(name="trailing_sl_phase") @Builder.Default private Integer trailingSlPhase=0;
    @Column(name="trailing_sl_active") @Builder.Default private Integer trailingSlActive=0;
    @Column(name="portfolio_name") @Builder.Default private String portfolioName="Default";
    @Column(name="broker_used") @Builder.Default private String brokerUsed="Dhan";
    private String status;
    @Column(name="exit_price") private Double exitPrice;
    @Column(name="exit_date") private String exitDate;
    @Column(name="exit_type") private String exitType;
    private Double pnl;
    @Column(name="pnl_pct") private Double pnlPct;
    @Column(name="holding_days") @Builder.Default private Integer holdingDays=0;
    private String source;
}
