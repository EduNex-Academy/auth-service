package com.edu.auth_service.dto;

import lombok.Data;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

// User Profile Response DTO for Auth Service
@Data
public class UserProfileResponse {
    private String id;
    
    @NotBlank(message = "Username is required")
    @Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
    private String username;
    
    @NotBlank(message = "Email is required")
    @Email(message = "Email should be valid")
    private String email;
    
    @NotBlank(message = "First name is required")
    @Size(max = 50, message = "First name must not exceed 50 characters")
    private String firstName;
    
    @NotBlank(message = "Last name is required")
    @Size(max = 50, message = "Last name must not exceed 50 characters")
    private String lastName;
    
    @Size(max = 20, message = "Phone number must not exceed 20 characters")
    private String phoneNumber;
    
    private String profilePictureUrl;
    
    @Size(max = 500, message = "Bio must not exceed 500 characters")
    private String bio;
    
    @Size(max = 100, message = "Location must not exceed 100 characters")
    private String location;
    
    private String dateOfBirth; // Stored as string for flexibility
    
    @NotBlank(message = "Role is required")
    private String role;
    
    private Boolean isActive = true;
    
    private LocalDateTime createdAt;
    private LocalDateTime lastLoginAt;
    
    // Email verification status from Keycloak
    private Boolean emailVerified;
    
    // Two-factor authentication enabled
    private Boolean twoFactorEnabled;
}
