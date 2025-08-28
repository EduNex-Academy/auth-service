package com.edu.auth_service.service;

import com.edu.auth_service.dto.ChangePasswordWithEmailRequest;
import com.edu.auth_service.dto.ChangePasswordWithOldPasswordRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class KeycloakPasswordService {

    private final Keycloak keycloak;

    @Value("${keycloak.realm}")
    private String realm;
    @Value("${keycloak.server-url}")
    private String keycloakServerUrl;
    @Value("${keycloak.client-id}")
    private String keycloakClientId;
    @Value("${keycloak.client-secret}")
    private String keycloakClientSecret;
    @Value("${app.keycloak.student-password-reset-redirect-uri}")
    private String studentPasswordResetRedirectUri;
    @Value("${app.keycloak.admin-password-reset-redirect-uri}")
    private String adminPasswordResetRedirectUri;
    @Value("${app.keycloak.instructor-password-reset-redirect-uri}")
    private String instructorPasswordResetRedirectUri;
    @Value("${app.keycloak.student-email-verification-redirect-uri}")
    private String studentEmailVerificationRedirectUri;
    @Value("${app.keycloak.admin-email-verification-redirect-uri}")
    private String adminEmailVerificationRedirectUri;
    @Value("${app.keycloak.instructor-email-verification-redirect-uri}")
    private String instructorEmailVerificationRedirectUri;

    /**
     * Trigger Keycloak's built-in forgot password email
     * This sends a reset link to the user's email
     */
    public void sendPasswordResetEmail(ChangePasswordWithEmailRequest request) {
        try {
            // Validate email
            if (request.getEmail() == null || request.getEmail().isEmpty()) {
                throw new RuntimeException("Email cannot be null or empty");
            }

            // Get user email from request
            String email = request.getEmail();
            log.info("Attempting to send password reset email to: {}", email);

            UsersResource usersResource = keycloak.realm(realm).users();

            // Get user from Keycloak
            List<UserRepresentation> users = usersResource.searchByEmail(email, true);
            if (users.isEmpty()) {
                throw new RuntimeException("User not found with email: " + email);
            }

            UserRepresentation keycloakUser = users.get(0);
            UserResource userResource = usersResource.get(keycloakUser.getId());

            // Validate user data
            if (keycloakUser.getId() == null) {
                throw new RuntimeException("Keycloak user ID is null");
            }

            // Construct redirect URI
            String redirectUri = switch (request.getUserRole()) {
                case "STUDENT" -> studentPasswordResetRedirectUri;
                case "ADMIN" -> adminPasswordResetRedirectUri;
                case "INSTRUCTOR" -> instructorPasswordResetRedirectUri;
                default -> throw new RuntimeException("Unsupported user type: " + keycloakUser.getAttributes().get("userType").get(0));
            };
            if (!redirectUri.endsWith("/")) {
                redirectUri += "/";
            }
            redirectUri += keycloakUser.getId();

            // Trigger forgot password email - Keycloak will send email with a reset link
            userResource.executeActionsEmail(
                    keycloakClientId, // clientId
                    redirectUri, // redirectUri
                    300, // lifespan in seconds (5 minutes)
                    List.of("UPDATE_PASSWORD") // actions
            );
            log.info("Password reset email sent successfully to: {}", email);
        } catch (Exception e) {
            log.error("Failed to send password reset email to {}: ", request.getEmail(), e);
            throw new RuntimeException("Failed to send password reset email: " + e.getMessage());
        }
    }

    /**
     * Change password with old password verification using Keycloak
     */
    public void changePasswordWithOldPassword(String userId, ChangePasswordWithOldPasswordRequest request) {
        try {
            // Validate password confirmation
            if (!request.getNewPassword().equals(request.getConfirmPassword())) {
                throw new RuntimeException("Password confirmation does not match");
            }

            log.info("Starting password change process for user ID: {}", userId);

            // Get user from Keycloak
            UserResource userResource = keycloak.realm(realm).users().get(userId);
            UserRepresentation user = userResource.toRepresentation();

            // Verify old password by attempting to get a token with old credentials
            if (!verifyCurrentPasswordWithKeycloak(user.getUsername(), request.getOldPassword())) {
                log.warn("Password verification failed for user: {}", user.getEmail());
                throw new RuntimeException("Current password is incorrect");
            }
            log.info("Current password verification successful for user: {}", user.getEmail());

            // Update password in Keycloak
            updatePasswordInKeycloak(userId, request.getNewPassword());
            log.info("Password updated in Keycloak for user: {}", userId);

            log.info("Password changed successfully for user: {}", userId);
        } catch (Exception e) {
            log.error("Password change failed for user {}: ", userId, e);
            throw new RuntimeException("Password change failed: " + e.getMessage());
        }
    }

    /**
     * Update password directly in Keycloak (for admin operations)
     */
    public void updatePasswordInKeycloak(String userId, String newPassword) {
        try {
            UserResource userResource = keycloak.realm(realm).users().get(userId);

            CredentialRepresentation credential = new CredentialRepresentation();
            credential.setType(CredentialRepresentation.PASSWORD);
            credential.setValue(newPassword);
            credential.setTemporary(false);

            userResource.resetPassword(credential);
            log.info("Password updated in Keycloak for user: {}", userId);
        } catch (Exception e) {
            log.error("Failed to update password in Keycloak for user {}: ", userId, e);
            throw new RuntimeException("Failed to update password in Keycloak: " + e.getMessage());
        }
    }

    /**
     * Verify the current password with Keycloak by attempting authentication
     */
    private boolean verifyCurrentPasswordWithKeycloak(String username, String currentPassword) {
        try {
            String tokenUrl = keycloakServerUrl + "/realms/" + realm + "/protocol/openid-connect/token";
            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
            map.add("grant_type", "password");
            map.add("client_id", keycloakClientId);
            map.add("client_secret", keycloakClientSecret);
            map.add("username", username);
            map.add("password", currentPassword);

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(map, headers);
            log.debug("Attempting password verification for user: {}", username);

            ResponseEntity<String> response = restTemplate.postForEntity(tokenUrl, request, String.class);
            boolean isSuccess = response.getStatusCode().is2xxSuccessful();

            if (isSuccess) {
                log.info("Password verification successful for user: {}", username);
                return true;
            } else {
                log.warn("Password verification failed for user: {} - HTTP Status: {}", username, response.getStatusCode());
                return false;
            }
        } catch (Exception e) {
            log.error("Password verification failed for user {}: {}", username, e.getMessage());
            return false;
        }
    }

    /**
     * Enable/Disable user account
     */
    public void setUserEnabled(String userId, boolean enabled) {
        try {
            UserResource userResource = keycloak.realm(realm).users().get(userId);
            UserRepresentation user = userResource.toRepresentation();
            user.setEnabled(enabled);
            userResource.update(user);

            log.info("User {} status changed to: {}", userId, enabled ? "enabled" : "disabled");
        } catch (Exception e) {
            log.error("Failed to update user status for {}: ", userId, e);
            throw new RuntimeException("Failed to update user status: " + e.getMessage());
        }
    }

    /**
     * Send email verification to user
     * Uses Keycloak's built-in email verification functionality
     */
    public void sendEmailVerification(String userId) {
        try {
            log.info("Attempting to send email verification for user ID: {}", userId);

            UserResource userResource = keycloak.realm(realm).users().get(userId);
            UserRepresentation user = userResource.toRepresentation();

            if (user == null) {
                throw new RuntimeException("User not found with ID: " + userId);
            }

            if (user.getEmail() == null || user.getEmail().isEmpty()) {
                throw new RuntimeException("User does not have an email address");
            }

            // Check if email is already verified
            if (Boolean.TRUE.equals(user.isEmailVerified())) {
                log.info("Email already verified for user: {}", userId);
                throw new RuntimeException("Email is already verified");
            }

            // Get user role to determine redirect URI
            String redirectUri = getEmailVerificationRedirectUri(user);

            // Send verification email using Keycloak's executeActionsEmail
            userResource.executeActionsEmail(
                    keycloakClientId, // clientId
                    redirectUri, // redirectUri
                    300, // lifespan in seconds (5 minutes)
                    List.of("VERIFY_EMAIL") // actions
            );

            log.info("Email verification sent successfully for user: {}", userId);
        } catch (Exception e) {
            log.error("Failed to send email verification for user {}: ", userId, e);
            throw new RuntimeException("Failed to send email verification: " + e.getMessage());
        }
    }

    /**
     * Get email verification redirect URI based on user role
     */
    private String getEmailVerificationRedirectUri(UserRepresentation user) {
        // Check user attributes for role
        if (user.getAttributes() != null && user.getAttributes().get("userType") != null) {
            String userType = user.getAttributes().get("userType").get(0);
            return switch (userType) {
                case "STUDENT" -> studentEmailVerificationRedirectUri;
                case "ADMIN" -> adminEmailVerificationRedirectUri;
                case "INSTRUCTOR" -> instructorEmailVerificationRedirectUri;
                default -> studentEmailVerificationRedirectUri; // default fallback
            };
        }
        
        // Fallback to student redirect URI if no role found
        return studentEmailVerificationRedirectUri;
    }
}
