package com.edu.auth_service.controller;

import com.edu.auth_service.dto.*;
import com.edu.auth_service.service.AuthService;
import com.edu.auth_service.service.KeycloakCallbackService;
import com.edu.auth_service.service.KeycloakPasswordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Validated
@Tag(name = "Authentication", description = "Authentication and user management endpoints with Keycloak identity brokering")
public class AuthController {

    private final AuthService authService;
    private final KeycloakCallbackService keycloakCallbackService;
    private final KeycloakPasswordService keycloakPasswordService;

    @Value("${app.keycloak.google-login-url}")
    private String googleLoginUrl;

    @Value("${app.keycloak.login-url}")
    private String loginUrl;

    @PostMapping("/register")
    @Operation(summary = "Register a new user", description = "Creates a new user account in both Keycloak and local database")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "User registered successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid input data"),
        @ApiResponse(responseCode = "409", description = "User already exists")
    })
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody UserRegistrationRequest request) {
        AuthResponse response = authService.registerUser(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/login")
    @Operation(summary = "User login", description = "Authenticates user and returns JWT tokens")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Login successful"),
        @ApiResponse(responseCode = "401", description = "Invalid credentials")
    })
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.authenticateUser(request.getUsername(), request.getPassword());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Refresh JWT token", description = "Generates new access token using refresh token")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Token refreshed successfully"),
        @ApiResponse(responseCode = "401", description = "Invalid refresh token")
    })
    public ResponseEntity<AuthResponse> refreshToken(
            @Parameter(description = "Refresh token") @RequestParam String refreshToken) {
        AuthResponse response = authService.refreshToken(refreshToken);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/profile")
    @Operation(summary = "Get user profile", description = "Retrieves current user's profile information")
    @SecurityRequirement(name = "Bearer Authentication")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Profile retrieved successfully"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public ResponseEntity<UserProfileResponse> getProfile(Authentication authentication) {
        String userId = extractUserIdFromToken(authentication);
        UserProfileResponse profile = authService.getUserProfile(userId);
        return ResponseEntity.ok(profile);
    }

    @PutMapping("/profile")
    @Operation(summary = "Update user profile", description = "Updates current user's profile information")
    @SecurityRequirement(name = "Bearer Authentication")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Profile updated successfully"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "400", description = "Invalid input data")
    })
    public ResponseEntity<Void> updateProfile(
            Authentication authentication,
            @RequestBody UserProfileResponse updateRequest) {
        String userId = extractUserIdFromToken(authentication);
        authService.updateUserProfile(userId, updateRequest);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/logout")
    @Operation(summary = "User logout", description = "Logs out the current user")
    @SecurityRequirement(name = "Bearer Authentication")
    @ApiResponse(responseCode = "200", description = "Logout successful")
    public ResponseEntity<Void> logout(Authentication authentication) {
        // Keycloak handles logout through its own endpoints
        // This endpoint can be used for additional cleanup if needed
        return ResponseEntity.ok().build();
    }

    @GetMapping("/login-urls")
    @Operation(summary = "Get authentication URLs", description = "Returns Keycloak authentication URLs for frontend")
    @ApiResponse(responseCode = "200", description = "Authentication URLs retrieved successfully")
    public ResponseEntity<Map<String, String>> getLoginUrls() {
        Map<String, String> urls = Map.of(
            "regularLogin", loginUrl,
            "googleLogin", googleLoginUrl
        );
        return ResponseEntity.ok(urls);
    }

    @PostMapping("/callback")
    @Operation(summary = "OAuth callback handler", description = "Handles OAuth callback from Keycloak (including Google OAuth)")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "OAuth callback processed successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid authorization code"),
        @ApiResponse(responseCode = "401", description = "Authentication failed")
    })
    public ResponseEntity<AuthResponse> handleOAuthCallback(@Valid @RequestBody AuthCallbackRequest request) {
        AuthResponse response = keycloakCallbackService.handleAuthCallback(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/callback")
    @Operation(summary = "OAuth callback redirect", description = "Handles OAuth callback redirect from Keycloak")
    public ResponseEntity<Map<String, String>> handleOAuthCallbackRedirect(
            @RequestParam String code,
            @RequestParam(required = false) String state) {

        // Return the authorization code to the frontend
        // Frontend should then call POST /callback with this code
        Map<String, String> response = Map.of(
            "code", code,
            "message", "Authorization code received. Use POST /api/auth/callback to complete authentication."
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/change-password")
    @Operation(summary = "Change user password", description = "Changes the current user's password using Keycloak")
    @SecurityRequirement(name = "Bearer Authentication")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Password changed successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid password or validation failed"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public ResponseEntity<String> changePassword(
            Authentication authentication,
            @Valid @RequestBody ChangePasswordWithOldPasswordRequest request) {
        String userId = extractUserIdFromToken(authentication);
        keycloakPasswordService.changePasswordWithOldPassword(userId, request);
        return ResponseEntity.ok("Password changed successfully");
    }

    @PostMapping("/send-password-reset")
    @Operation(summary = "Send password reset email", description = "Sends password reset email via Keycloak")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Password reset email sent successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid email"),
        @ApiResponse(responseCode = "404", description = "User not found")
    })
    public ResponseEntity<String> sendPasswordReset(@RequestBody ChangePasswordWithEmailRequest request) {
        keycloakPasswordService.sendPasswordResetEmail(request);
        return ResponseEntity.ok("Password reset email sent successfully");
    }

    private String extractUserIdFromToken(Authentication authentication) {
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            return jwt.getClaimAsString("sub");
        }
        throw new RuntimeException("Invalid token format");
    }
}
