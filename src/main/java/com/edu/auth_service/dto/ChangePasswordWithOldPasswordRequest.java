package com.edu.auth_service.dto;

import lombok.Data;

@Data
public class ChangePasswordWithOldPasswordRequest {
    private String oldPassword;
    private String newPassword;
    private String confirmPassword;
}
