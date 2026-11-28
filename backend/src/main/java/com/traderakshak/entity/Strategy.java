package com.traderakshak.entity;
import jakarta.persistence.*;
import lombok.*;
@Entity @Table(name="strategies") @Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Strategy {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="user_id") private Long userId;
    @Column(name="institution_id") @Builder.Default private Long institutionId=1L;
    private String name;
    private String description;
    private String mode;
    @Builder.Default private String segment="EQ-SWG";
    @Column(name="strategy_type") @Builder.Default private String strategyType="technical";
    private String logic;
    @Column(name="entry_logic") private String entryLogic;
    @Column(name="exit_logic") private String exitLogic;
    @Column(name="fundamental_conditions") private String fundamentalConditions;
    @Column(name="technical_conditions") private String technicalConditions;
    @Column(name="stop_loss_pct") @Builder.Default private Double stopLossPct=5.0;
    @Column(name="target_pct") @Builder.Default private Double targetPct=10.0;
    @Column(name="trailing_sl_ph1") @Builder.Default private Double trailingSlPh1=5.0;
    @Column(name="trailing_sl_ph2") @Builder.Default private Double trailingSlPh2=15.0;
    @Column(name="holding_days") @Builder.Default private Integer holdingDays=22;

    /**
     * When the base price is taken from. Null means "use the price uploaded with the stock",
     * which is what every strategy did before this existed.
     *
     * <p>base_price_date is a yyyy-MM-dd session; base_price_time an optional HH:mm within it.
     * With a time the base is the last 5-minute candle at or before it, without one the
     * session's close.
     */
    @Column(name="base_price_date") private String basePriceDate;
    @Column(name="base_price_time") private String basePriceTime;
    /**
     * The rule tree, as the JSON {@code AstParser} reads.
     *
     * <p>The column has existed since V51 and nothing wrote it, so a strategy's grouping lived
     * only in the builder's own memory and was lost the moment it was saved. Stored here it
     * round-trips, and it is the one representation the validator, the screener and the
     * natural-language interpreter can all be pointed at without drifting apart.
     *
     * <p>Null for every strategy built before the grouped canvas, which keeps working from its
     * flat conditions.
     */
    @Column(name="rule_ast")    private String ruleAst;
    @Column(name="ast_version") private Integer astVersion;

    @Column(name="trade_window_start") @Builder.Default private String tradeWindowStart="15:10";
    @Column(name="trade_window_end") @Builder.Default private String tradeWindowEnd="15:20";
    @Builder.Default private String universe="ALL_CASH";
    @Column(name="rr_min") @Builder.Default private Double rrMin=2.0;
    @Column(name="max_positions") @Builder.Default private Integer maxPositions=5;
    @Column(name="max_per_sector") @Builder.Default private Integer maxPerSector=2;
    @Column(name="risk_per_trade_pct") @Builder.Default private Double riskPerTradePct=2.0;
    @Column(name="use_ai_filter") @Builder.Default private Integer useAiFilter=0;
    @Column(name="ai_min_score") @Builder.Default private Double aiMinScore=60.0;
    @Builder.Default private String status="active";
    @Column(name="created_at") private String createdAt;
    @Column(name="disabled_reason") private String disabledReason;

    @Column(name="builder_level") @Builder.Default private Integer builderLevel=1;
    @Column(name="conditions_json",columnDefinition="TEXT") private String conditionsJson;
    @Column(name="visual_json",columnDefinition="TEXT") private String visualJson;
    @Column(name="ai_generated") @Builder.Default private Integer aiGenerated=0;
    @Column(name="ai_prompt",columnDefinition="TEXT") private String aiPrompt;
    @Column(name="strategy_category") @Builder.Default private String strategyCategory="swing";
    private String timeframe;
    @Column(name="multi_timeframe_json",columnDefinition="TEXT") private String multiTimeframeJson;
    @Column(name="entry_conditions_json",columnDefinition="TEXT") private String entryConditionsJson;
    @Column(name="exit_conditions_json",columnDefinition="TEXT") private String exitConditionsJson;
    @Column(name="risk_management_json",columnDefinition="TEXT") private String riskManagementJson;
    @Column(name="position_sizing_json",columnDefinition="TEXT") private String positionSizingJson;
    @Builder.Default private Integer backtested=0;
    @Column(name="paper_tested") @Builder.Default private Integer paperTested=0;
    @Column(name="live_approved") @Builder.Default private Integer liveApproved=0;
    @Column(name="workflow_status") @Builder.Default private String workflowStatus="draft";
    @Builder.Default private Integer version=1;
    @Column(name="parent_id") private Long parentId;
    @Column(name="is_public") @Builder.Default private Integer isPublic=0;
    @Column(name="is_marketplace") @Builder.Default private Integer isMarketplace=0;
    @Column(name="marketplace_price") @Builder.Default private Double marketplacePrice=0.0;
    private String tags;
    @Column(name="win_rate") private Double winRate;
    @Column(name="best_backtest_id") private Long bestBacktestId;
    @Column(name="total_copies") @Builder.Default private Integer totalCopies=0;
    private Double rating;
    @Column(name="rating_count") @Builder.Default private Integer ratingCount=0;
    @Column(name="updated_at") private String updatedAt;
}
