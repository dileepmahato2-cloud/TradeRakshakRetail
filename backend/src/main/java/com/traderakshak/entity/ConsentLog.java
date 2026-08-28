package com.traderakshak.entity;
import jakarta.persistence.*;
import lombok.*;
/** R3: Consent log — append-only, never deletable */
@Entity @Table(name="consent_log") @Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ConsentLog {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="user_id") private Long userId;
    private String action;
    @Column(name="consent_text") private String consentText;
    @Builder.Default private Integer accepted=0;
    private String ip;
    @Column(name="created_at") private String createdAt;
}
