package com.traderakshak.entity;
import jakarta.persistence.*;
import lombok.*;
@Entity @Table(name="coupons") @Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Coupon {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="institution_id") @Builder.Default private Long institutionId=1L;
    @Column(unique=true,nullable=false) private String code;
    @Column(name="coupon_type") @Builder.Default private String couponType="percentage";
    @Column(name="discount_value") private Double discountValue;
    @Column(name="free_days") @Builder.Default private int freeDays=0;
    @Column(name="applicable_plans") @Builder.Default private String applicablePlans="ALL";
    @Column(name="min_order_amount") @Builder.Default private double minOrderAmount=0;
    @Column(name="max_uses") @Builder.Default private int maxUses=0;
    @Column(name="uses_per_user") @Builder.Default private int usesPerUser=1;
    @Column(name="current_uses") @Builder.Default private int currentUses=0;
    @Column(name="valid_from") private String validFrom;
    @Column(name="valid_to") private String validTo;
    @Column(name="created_by") private Long createdBy;
    @Builder.Default private String status="active";
    @Column(name="created_at") private String createdAt;
}
