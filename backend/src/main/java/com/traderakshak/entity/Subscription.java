package com.traderakshak.entity;
import jakarta.persistence.*;
import lombok.*;
@Entity @Table(name="subscriptions") @Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Subscription {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="user_id") private Long userId;
    @Column(name="institution_id") @Builder.Default private Long institutionId=1L;
    @Column(name="plan_id") private Long planId;
    @Column(name="plan_name") private String planName;
    @Column(name="billing_cycle") @Builder.Default private String billingCycle="monthly";
    @Builder.Default private String status="active";
    @Column(name="start_date") private String startDate;
    @Column(name="end_date") private String endDate;
    @Column(name="next_billing") private String nextBilling;
    @Column(name="auto_renew") @Builder.Default private int autoRenew=1;
    @Column(name="trial_used") @Builder.Default private int trialUsed=0;
    @Column(name="upgrade_from") private String upgradeFrom;
    @Column(name="downgrade_to") private String downgradeTo;
    @Column(name="cancel_reason") private String cancelReason;
    @Column(name="cancelled_at") private String cancelledAt;
    @Column(name="created_at") private String createdAt;
    @Column(name="updated_at") private String updatedAt;
}
