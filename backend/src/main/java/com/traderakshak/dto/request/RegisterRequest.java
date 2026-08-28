package com.traderakshak.dto.request;
import jakarta.validation.constraints.*;
import lombok.Data;
@Data
public class RegisterRequest {
    @NotBlank private String name;
    @Email @NotBlank private String email;
    @NotBlank @Size(min = 8) private String password;
    private String mobile;
    @NotBlank private String pan;
    private boolean consent;
}
