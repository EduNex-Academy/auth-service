package com.edu.auth_service.dto;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;

@Data
public class GoogleAuthRequest {

    @NotBlank(message = "Google access token is required")
    private String accessToken;

    private String role = "STUDENT"; // Default role for Google OAuth users
}
