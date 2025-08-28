package com.edu.auth_service.dto;

import lombok.Data;

@Data
public class ChangePasswordWithEmailRequest {
    private String email;
    private String userRole;
}
