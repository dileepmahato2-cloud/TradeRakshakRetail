package com.traderakshak.dto.response;
import lombok.*;
@Data @Builder
public class AuthResponse {
    private String token;
    private String refreshToken;
    private String trUserId;
    private String name;
    private String email;
    private String role;
    private String userType;       // NEW: full hierarchy type
    private int    hierarchyLevel; // NEW: numeric level
    private String plan;
    private String planExpiry;
    private String planStatus;
    private String sebiDisclaimer;
}
