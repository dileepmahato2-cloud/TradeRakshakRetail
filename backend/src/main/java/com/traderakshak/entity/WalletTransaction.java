package com.traderakshak.entity;
import jakarta.persistence.*;
import lombok.*;
@Entity @Table(name="wallet_transactions") @Data @Builder @NoArgsConstructor @AllArgsConstructor
public class WalletTransaction {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="user_id") private Long userId;
    @Column(name="transaction_type") private String transactionType;
    private Double amount;
    @Column(name="balance_after") private Double balanceAfter;
    private String description;
    @Column(name="reference_id") private String referenceId;
    @Column(name="created_by") private Long createdBy;
    @Column(name="created_at") private String createdAt;
}
