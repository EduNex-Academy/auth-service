package com.edu.auth_service.controller;

import com.edu.auth_service.dto.*;
import com.edu.auth_service.service.AuthService;
import com.edu.auth_service.service.KeycloakCallbackService;
import com.edu.auth_service.service.KeycloakPasswordService;
import com.edu.auth_service.config.CookieConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Validated
@Slf4j
@Tag(name = "Authentication", description = "Keycloak-based authentication and user management")
public class AuthController {

    private final AuthService authService;
    private final KeycloakCallbackService keycloakCallbackService;
    private final KeycloakPasswordService keycloakPasswordService;
    private final CookieConfig cookieConfig;

    @Value("${app.keycloak.admin-google-login-url}")
    private String adminGoogleLoginUrl;

    @Value("${app.keycloak.student-google-login-url}")
    private String studentGoogleLoginUrl;

    @Value("${app.keycloak.instructor-google-login-url}")
    private String instructorGoogleLoginUrl;

    @PostMapping("/register")
    @Operation(summary = "Register new user", 
               description = "Creates a new user account in Keycloak with specified role")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "User registered successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid input data"),
        @ApiResponse(responseCode = "409", description = "User already exists"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody UserRegistrationRequest request) {
        log.info("Registration request for user: {}", request.getUsername());
        
        try {
            Map<String, Object> authResult = authService.registerUser(request);
            log.info("User {} registered successfully", request.getUsername());

            // Create secure HttpOnly cookie for refresh token
            ResponseCookie refreshTokenCookie = createRefreshTokenCookie((String) authResult.get("refreshToken"));

            // Return only access token in response body
            AuthResponse response = (AuthResponse) authResult.get("authResponse");

            return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookie.toString())
                .body(response);
        } catch (Exception e) {
            log.error("Registration failed for user {}: {}", request.getUsername(), e.getMessage());
            throw e;
        }
    }

    @PostMapping("/login")
    @Operation(summary = "User login", 
               description = "Authenticates user credentials against Keycloak and returns JWT tokens")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Login successful"),
        @ApiResponse(responseCode = "401", description = "Invalid credentials"),
        @ApiResponse(responseCode = "500", description = "Authentication service error")
    })
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        log.info("Login request for user: {}", request.getUsername());
        
        try {
            Map<String, Object> authResult = authService.authenticateUser(request.getUsername(), request.getPassword());
            log.info("User {} logged in successfully", request.getUsername());

            // Create secure HttpOnly cookie for refresh token
            ResponseCookie refreshTokenCookie = createRefreshTokenCookie((String) authResult.get("refreshToken"));

            // Return only access token in response body
            AuthResponse response = (AuthResponse) authResult.get("authResponse");

            return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookie.toString())
                .body(response);
        } catch (Exception e) {
            log.error("Login failed for user {}: {}", request.getUsername(), e.getMessage());
            throw e;
        }
    }

    @PostMapping("/refresh")
    @Operation(summary = "Refresh JWT token", 
               description = "Generates new access token using refresh token from HttpOnly cookie")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Token refreshed successfully"),
        @ApiResponse(responseCode = "401", description = "Invalid or expired refresh token"),
        @ApiResponse(responseCode = "500", description = "Token refresh service error")
    })
    public ResponseEntity<AuthResponse> refreshToken(HttpServletRequest request) {
        log.info("Token refresh request");

        try {
            // Extract refresh token from HttpOnly cookie
            String refreshToken = extractRefreshTokenFromCookie(request);

            if (refreshToken == null) {
                throw new RuntimeException("Refresh token not found in cookies");
            }

            Map<String, Object> authResult = authService.refreshToken(refreshToken);
            log.info("Token refreshed successfully");

            // Create new secure HttpOnly cookie with new refresh token
            ResponseCookie refreshTokenCookie = createRefreshTokenCookie((String) authResult.get("refreshToken"));

            // Return only access token in response body
            AuthResponse response = (AuthResponse) authResult.get("authResponse");

            return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookie.toString())
                .body(response);
        } catch (Exception e) {
            log.error("Token refresh failed: {}", e.getMessage());
            throw e;
        }
    }

    @PostMapping("/logout")
    @Operation(summary = "User logout",
               description = "Automatically logs out user from Keycloak by invalidating all sessions and clearing cookies")
    @SecurityRequirement(name = "Bearer Authentication")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "User logged out successfully"),
        @ApiResponse(responseCode = "401", description = "Unauthorized - invalid or missing token"),
        @ApiResponse(responseCode = "500", description = "Logout service error")
    })
    public ResponseEntity<Map<String, String>> logout(Authentication authentication) {
        String userId = extractUserIdFromToken(authentication);
        log.info("Logout request for user ID: {}", userId);

        try {
            // Automatically logout user from Keycloak (invalidate all sessions)
            authService.logoutUser(userId);

            // Clear refresh token cookie
            ResponseCookie clearRefreshTokenCookie = ResponseCookie.from(cookieConfig.getRefreshTokenName(), "")
                .httpOnly(cookieConfig.isHttpOnly())
                .secure(cookieConfig.isSecure())
                .sameSite(cookieConfig.getSameSite())
                .path(cookieConfig.getPath())
                .maxAge(0)
                .build();

            Map<String, String> response = Map.of(
                "status", "success",
                "message", "User logged out successfully from all sessions"
            );

            return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, clearRefreshTokenCookie.toString())
                .body(response);

        } catch (Exception e) {
            log.error("Logout failed for user {}: {}", userId, e.getMessage());
            throw e;
        }
    }

    @GetMapping("/profile")
    @Operation(summary = "Get user profile", 
               description = "Retrieves current user's profile from Keycloak")
    @SecurityRequirement(name = "Bearer Authentication")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Profile retrieved successfully"),
        @ApiResponse(responseCode = "401", description = "Unauthorized - invalid or missing token"),
        @ApiResponse(responseCode = "404", description = "User not found")
    })
    public ResponseEntity<UserProfileResponse> getProfile(Authentication authentication) {
        String userId = extractUserIdFromToken(authentication);
        log.info("Profile request for user ID: {}", userId);
        
        try {
            UserProfileResponse profile = authService.getUserProfile(userId);
            return ResponseEntity.ok(profile);
        } catch (Exception e) {
            log.error("Failed to get profile for user {}: {}", userId, e.getMessage());
            throw e;
        }
    }

    @PutMapping("/profile")
    @Operation(summary = "Update user profile", 
               description = "Updates current user's profile information in Keycloak")
    @SecurityRequirement(name = "Bearer Authentication")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Profile updated successfully"),
        @ApiResponse(responseCode = "401", description = "Unauthorized - invalid or missing token"),
        @ApiResponse(responseCode = "400", description = "Invalid input data"),
        @ApiResponse(responseCode = "404", description = "User not found")
    })
    public ResponseEntity<Void> updateProfile(Authentication authentication, @Valid @RequestBody UserProfileResponse updateRequest) {
        
        String userId = extractUserIdFromToken(authentication);
        log.info("Profile update request for user ID: {}", userId);
        
        try {
            authService.updateUserProfile(userId, updateRequest);
            log.info("Profile updated successfully for user ID: {}", userId);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Failed to update profile for user {}: {}", userId, e.getMessage());
            throw e;
        }
    }

    @GetMapping("/login-urls")
    @Operation(summary = "Get authentication URLs", 
               description = "Returns Keycloak authentication URLs for different login methods")
    @ApiResponse(responseCode = "200", description = "Authentication URLs retrieved successfully")
    public ResponseEntity<Map<String, String>> getLoginUrls(@RequestParam("userRole") String userRole) {

        log.info("Login URLs requested for role: {}", userRole);

        Map<String, String> urls = new HashMap<>();
        switch (userRole.toLowerCase()) {
            case "admin":
                urls.put("googleLogin", adminGoogleLoginUrl);
                break;
            case "student":
                urls.put("googleLogin", studentGoogleLoginUrl);
                break;
            case "instructor":
                urls.put("googleLogin", instructorGoogleLoginUrl);
                break;
            default:
                urls.put("error", "Unsupported user role");
                break;
        }
        return ResponseEntity.ok(urls);
    }

    @PostMapping("/callback")
    @Operation(summary = "OAuth callback handler", 
               description = "Handles OAuth authorization code callback from Keycloak")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "OAuth callback processed successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid authorization code"),
        @ApiResponse(responseCode = "401", description = "Authentication failed"),
        @ApiResponse(responseCode = "500", description = "OAuth processing error")
    })
    public ResponseEntity<AuthResponse> handleOAuthCallback(@Valid @RequestBody AuthCallbackRequest request) {
        log.info("OAuth callback request with code: {}", request.getCode().substring(0, 10) + "...");
        
        try {
            Map<String, Object> authResult = keycloakCallbackService.handleAuthCallback(request);
            log.info("OAuth callback processed successfully");
            
            // Create secure HttpOnly cookie for refresh token
            ResponseCookie refreshTokenCookie = createRefreshTokenCookie((String) authResult.get("refreshToken"));
            
            // Return only access token in response body
            AuthResponse response = (AuthResponse) authResult.get("authResponse");
            
            return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookie.toString())
                .body(response);
        } catch (Exception e) {
            log.error("OAuth callback failed: {}", e.getMessage());
            throw e;
        }
    }

    @PostMapping("/change-password")
    @Operation(summary = "Change user password", 
               description = "Changes the current user's password using Keycloak")
    @SecurityRequirement(name = "Bearer Authentication")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Password changed successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid password or validation failed"),
        @ApiResponse(responseCode = "401", description = "Unauthorized - invalid or missing token"),
        @ApiResponse(responseCode = "403", description = "Current password incorrect")
    })
    public ResponseEntity<String> changePassword(Authentication authentication, @Valid @RequestBody ChangePasswordWithOldPasswordRequest request) {
        
        String userId = extractUserIdFromToken(authentication);
        log.info("Password change request for user ID: {}", userId);
        
        try {
            keycloakPasswordService.changePasswordWithOldPassword(userId, request);
            log.info("Password changed successfully for user ID: {}", userId);
            return ResponseEntity.ok("Password changed successfully");
        } catch (Exception e) {
            log.error("Password change failed for user {}: {}", userId, e.getMessage());
            throw e;
        }
    }

    @PostMapping("/send-password-reset")
    @Operation(summary = "Send password reset email", 
               description = "Sends password reset email via Keycloak")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Password reset email sent successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid email format"),
        @ApiResponse(responseCode = "404", description = "User not found"),
        @ApiResponse(responseCode = "500", description = "Email service error")
    })
    public ResponseEntity<String> sendPasswordReset(@Valid @RequestBody ChangePasswordWithEmailRequest request) {
        log.info("Password reset request for email: {}", request.getEmail());
        
        try {
            keycloakPasswordService.sendPasswordResetEmail(request);
            log.info("Password reset email sent for: {}", request.getEmail());
            return ResponseEntity.ok("Password reset email sent successfully");
        } catch (Exception e) {
            log.error("Password reset failed for email {}: {}", request.getEmail(), e.getMessage());
            throw e;
        }
    }

    @GetMapping("/diagnose")
    @Operation(summary = "Diagnose Keycloak permissions",
               description = "Checks Keycloak service account permissions and provides setup instructions")
    @ApiResponse(responseCode = "200", description = "Diagnostic information provided")
    public ResponseEntity<Map<String, Object>> diagnoseKeycloakPermissions() {
        log.info("Keycloak permissions diagnosis request");

        try {
            // Run the diagnosis and capture the output
            authService.diagnoseForbiddenError();

            Map<String, Object> response = Map.of(
                "status", "success",
                "message", "Check the application logs for detailed diagnostic information",
                "instructions", Map.of(
                    "step1", "Go to Keycloak Admin Console",
                    "step2", "Navigate to: Realms > edunex-platform > Clients > [Your Client ID]",
                    "step3", "Settings tab: Enable 'Service accounts enabled'",
                    "step4", "Service Account Roles tab: Add roles from 'realm-management'",
                    "requiredRoles", java.util.List.of("manage-users", "view-users", "query-users"),
                    "step5", "Save and restart the application"
                )
            );

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = Map.of(
                "status", "error",
                "message", "Diagnosis failed: " + e.getMessage(),
                "recommendation", "Check Keycloak connection and configuration"
            );
            return ResponseEntity.ok(response);
        }
    }

    @GetMapping("/health")
    @Operation(summary = "Health check", 
               description = "Check if the auth service and Keycloak connection are healthy")
    @ApiResponse(responseCode = "200", description = "Service is healthy")
    public ResponseEntity<Map<String, String>> healthCheck() {
        // This could include Keycloak connectivity check
        Map<String, String> health = Map.of(
            "status", "UP",
            "service", "auth-service",
            "timestamp", java.time.Instant.now().toString()
        );
        
        return ResponseEntity.ok(health);
    }

    /**
     * Creates a secure HttpOnly cookie for the refresh token
     */
    private ResponseCookie createRefreshTokenCookie(String refreshToken) {
        return ResponseCookie.from(cookieConfig.getRefreshTokenName(), refreshToken)
            .httpOnly(cookieConfig.isHttpOnly())
            .secure(cookieConfig.isSecure())
            .sameSite(cookieConfig.getSameSite())
            .path(cookieConfig.getPath())
            .maxAge(Duration.ofDays(cookieConfig.getRefreshTokenMaxAgeDays()))
            .build();
    }

    /**
     * Extracts refresh token from HttpOnly cookie
     */
    private String extractRefreshTokenFromCookie(HttpServletRequest request) {
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (cookieConfig.getRefreshTokenName().equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }

    /**
     * Extracts user ID from JWT token
     */
    private String extractUserIdFromToken(Authentication authentication) {
        Jwt jwt = (Jwt) authentication.getPrincipal();
        return jwt.getClaimAsString("sub");
    }
}
