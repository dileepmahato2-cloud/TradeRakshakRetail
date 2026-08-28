package com.traderakshak.entity;

import jakarta.persistence.*;
import lombok.*;

/** Maps to market_data table — OHLCV + delivery data */
@Entity
@Table(name = "market_data",
       uniqueConstraints = @UniqueConstraint(columnNames = {"exchange", "symbol", "trade_date"}))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class MarketData {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String exchange;
    private String symbol;

    @Column(name = "trade_date")
    private String tradeDate;

    private Double open;
    private Double high;
    private Double low;
    private Double close;

    @Column(name = "prev_close")
    private Double prevClose;

    private Double vwap;
    private Double volume;

    @Column(name = "total_volume")
    private Double totalVolume;

    private Double turnover;

    @Column(name = "delivery_volume")
    private Double deliveryVolume;

    @Column(name = "delivery_pct")
    private Double deliveryPct;

    @Column(name = "num_trades")
    @Builder.Default
    private Integer numTrades = 0;

    private String sector;

    @Builder.Default
    private String segment = "Equity";

    private String source;
    private String series;
}
