package com.traderakshak.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

/** Maps to trade_journal table — BRD §10 */
@Entity
@Table(name = "trade_journal")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TradeJournal {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trade_id")
    private String tradeId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "institution_id")
    @Builder.Default
    private Long institutionId = 1L;

    @Column(name = "trade_source")
    private String tradeSource;

    private String exchange;
    private String symbol;
    private String sector;

    @Builder.Default
    private String segment = "EQ-SWG";

    @Column(name = "trade_date")
    private String tradeDate;

    @Column(name = "purchase_price")
    private Double purchasePrice;

    @Column(name = "qty_buy")
    private Integer qtyBuy;

    @Column(name = "total_invested")
    private Double totalInvested;

    @Column(name = "sale_date")
    private String saleDate;

    @Column(name = "sell_price")
    private Double sellPrice;

    @Column(name = "qty_sell")
    private Integer qtySell;

    @Column(name = "total_received")
    private Double totalReceived;

    @Column(name = "brokerage_est")
    @Builder.Default
    private Double brokerageEst = 0.0;

    @Column(name = "gross_pnl")
    private Double grossPnl;

    @Column(name = "net_pnl")
    private Double netPnl;

    @Column(name = "return_pct")
    private Double returnPct;

    @Column(name = "holding_days")
    @Builder.Default
    private Integer holdingDays = 0;

    @Column(name = "result_status")
    private String resultStatus;  // Win / Loss / Scratch

    // R11: entry_remark is MANDATORY — mapped from Python's NOT NULL DEFAULT ''
    @Column(name = "entry_remark", nullable = false)
    @NotBlank(message = "Entry remark is mandatory (BRD §10)")
    @Builder.Default
    private String entryRemark = "";

    @Column(name = "closing_remark")
    private String closingRemark;

    @Column(name = "checklist_complete")
    @Builder.Default
    private Integer checklistComplete = 0;

    @Column(name = "ai_score_at_entry")
    private Double aiScoreAtEntry;

    @Column(name = "sector_signal_at_entry")
    private String sectorSignalAtEntry;

    @Column(name = "trade_score_at_entry")
    private Double tradeScoreAtEntry;

    @Column(name = "created_at")
    private String createdAt;
}
