package com.traderakshak.entity;
import jakarta.persistence.*;
import lombok.*;
@Entity @Table(name="support_tickets") @Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SupportTicket {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="ticket_id",unique=true) private String ticketId;
    @Column(name="user_id") private Long userId;
    @Column(name="institution_id") @Builder.Default private Long institutionId=1L;
    @Column(name="assigned_to") private Long assignedTo;
    private String category;
    private String subject;
    private String description;
    @Builder.Default private String priority="Medium";
    @Builder.Default private String status="Open";
    private String resolution;
    @Column(name="resolved_at") private String resolvedAt;
    @Column(name="first_response_at") private String firstResponseAt;
    @Column(name="satisfaction_rating") private Integer satisfactionRating;
    @Column(name="created_at") private String createdAt;
    @Column(name="updated_at") private String updatedAt;
}
