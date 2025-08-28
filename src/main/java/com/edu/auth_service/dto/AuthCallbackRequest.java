package com.edu.auth_service.dto;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;

@Data
public class AuthCallbackRequest {

    @NotBlank(message = "Authorization code is required")
    private String code;
    private String userRole;
    private String state;
}
