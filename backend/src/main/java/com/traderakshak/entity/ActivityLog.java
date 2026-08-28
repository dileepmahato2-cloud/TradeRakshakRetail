package com.traderakshak.entity;
import jakarta.persistence.*;
import lombok.*;
/** Append-only audit log — BRD §17 / R6. DELETE trigger prevents removal. */
@Entity @Table(name="activity_logs") @Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ActivityLog {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="user_id") private Long userId;
    @Column(name="institution_id") @Builder.Default private Long institutionId=1L;
    private String module;
    private String action;
    private String result;
    private String detail;
    private String ip;
    @Column(name="user_agent") private String userAgent;
    @Column(name="created_at") private String createdAt;
}
