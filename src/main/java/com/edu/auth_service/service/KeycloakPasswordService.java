package com.edu.auth_service.service;

import com.edu.auth_service.dto.ChangePasswordWithEmailRequest;
import com.edu.auth_service.dto.ChangePasswordWithOldPasswordRequest;
import com.edu.auth_service.entity.User;
import com.edu.auth_service.repository.UserRepository;
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
import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class KeycloakPasswordService {

    private final Keycloak keycloak;
    private final UserRepository userRepository;

    @Value("${keycloak.realm}")
    private String realm;
    @Value("${keycloak.server-url}")
    private String keycloakServerUrl;
    @Value("${keycloak.client-id}")
    private String keycloakClientId;
    @Value("${keycloak.client-secret}")
    private String keycloakClientSecret;
    @Value("${app.keycloak.password-reset-redirect-uri}")
    private String passwordResetRedirectUri;

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
            // Check if user exists in local database
            if (!userRepository.existsByEmail(request.getEmail())) {
                throw new RuntimeException("User not found with email: " + request.getEmail());
            }

            // Get user email from request
            String email = request.getEmail();
            log.info("Attempting to send password reset email to: {}", email);

            UsersResource usersResource = keycloak.realm(realm).users();

            // Get user from Keycloak
            List<UserRepresentation> users = usersResource.searchByEmail(email, true);
            if (users.isEmpty()) {
                throw new RuntimeException("User not found in Keycloak");
            }

            UserRepresentation keycloakUser = users.get(0);
            UserResource userResource = usersResource.get(keycloakUser.getId());

            // Validate user data
            if (keycloakUser.getId() == null) {
                throw new RuntimeException("Keycloak user ID is null");
            }

            // Construct redirect URI
            String redirectUri = passwordResetRedirectUri;
            if (!redirectUri.endsWith("/")) {
                redirectUri += "/";
            }
            redirectUri += keycloakUser.getId();

            // Trigger forgot password email - Keycloak will send email with reset link
            userResource.executeActionsEmail(
                    keycloakClientId, // clientId
                    redirectUri, // redirectUri
                    300, // lifespan in seconds (5 minutes)
                    Arrays.asList("UPDATE_PASSWORD") // actions
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

            // Get user
            User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
            log.info("Starting password change process for user: {}", user.getEmail());

            // Verify old password by attempting to get a token with old credentials
            if (!verifyCurrentPasswordWithKeycloak(user.getUsername(), request.getOldPassword())) {
                log.warn("Password verification failed: {} | {}", user.getEmail(), request.getOldPassword());
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
     * Verify current password with Keycloak by attempting authentication
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

                // Try with email if username failed and if username is not already an email
                if (!username.contains("@")) {
                    log.debug("Retrying password verification with email for user: {}", username);
                    User user = userRepository.findByUsername(username).orElse(null);
                    if (user != null && user.getEmail() != null) {
                        return verifyPasswordWithEmail(user.getEmail(), currentPassword);
                    }
                }
                return false;
            }
        } catch (Exception e) {
            log.error("Password verification failed for user {}: {}", username, e.getMessage());

            // Try with email if username failed and if username is not already an email
            if (!username.contains("@")) {
                log.debug("Retrying password verification with email after exception for user: {}", username);
                try {
                    User user = userRepository.findByUsername(username).orElse(null);
                    if (user != null && user.getEmail() != null) {
                        return verifyPasswordWithEmail(user.getEmail(), currentPassword);
                    }
                } catch (Exception emailException) {
                    log.error("Email-based password verification also failed: {}", emailException.getMessage());
                }
            }
            return false;
        }
    }

    /**
     * Helper method to verify password using email
     */
    private boolean verifyPasswordWithEmail(String email, String password) {
        try {
            String tokenUrl = keycloakServerUrl + "/realms/" + realm + "/protocol/openid-connect/token";
            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
            map.add("grant_type", "password");
            map.add("client_id", keycloakClientId);
            map.add("client_secret", keycloakClientSecret);
            map.add("username", email);
            map.add("password", password);

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(map, headers);
            log.debug("Attempting password verification with email: {}", email);

            ResponseEntity<String> response = restTemplate.postForEntity(tokenUrl, request, String.class);
            boolean isSuccess = response.getStatusCode().is2xxSuccessful();

            if (isSuccess) {
                log.info("Password verification successful with email: {}", email);
            } else {
                log.warn("Password verification failed with email: {} - HTTP Status: {}", email, response.getStatusCode());
            }
            return isSuccess;
        } catch (Exception e) {
            log.error("Email-based password verification failed for {}: {}", email, e.getMessage());
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
}
