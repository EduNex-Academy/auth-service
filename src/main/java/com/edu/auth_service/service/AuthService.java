package com.edu.auth_service.service;

import com.edu.auth_service.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import jakarta.ws.rs.core.Response;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final Keycloak keycloakAdminClient;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${keycloak.realm}")
    private String realm;

    @Value("${keycloak.client-id}")
    private String clientId;

    @Value("${keycloak.client-secret}")
    private String clientSecret;

    @Value("${keycloak.server-url}")
    private String serverUrl;

    /**
     * Register a new user in Keycloak
     * Keycloak is the single source of truth
     */
    public AuthResponse registerUser(UserRegistrationRequest request) {
        try {
            log.info("Registering user: {}", request.getUsername());
            
            // Validate Keycloak connection and permissions first
            validateKeycloakConnection();

            // Check if user already exists
            if (userExists(request.getUsername(), request.getEmail())) {
                throw new RuntimeException("User already exists");
            }

            // Create user in Keycloak
            String keycloakUserId = createKeycloakUser(request);
            log.info("User created in Keycloak with ID: {}", keycloakUserId);

            // Assign role to user
            assignRoleToUser(keycloakUserId, request.getRole());
            log.info("Role {} assigned to user {}", request.getRole(), keycloakUserId);

            // Authenticate and return tokens
            return authenticateUser(request.getUsername(), request.getPassword());

        } catch (Exception e) {
            log.error("Error registering user {}: {}", request.getUsername(), e.getMessage(), e);
            throw new RuntimeException("Failed to register user: " + e.getMessage());
        }
    }

    /**
     * Authenticate user using Keycloak
     * Uses OAuth2 Resource Owner Password Credentials Grant
     */
    @SuppressWarnings("unchecked")
    public AuthResponse authenticateUser(String username, String password) {
        try {
            log.info("Authenticating user: {}", username);

            MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
            formData.add("grant_type", "password");
            formData.add("client_id", clientId);
            formData.add("client_secret", clientSecret);
            formData.add("username", username);
            formData.add("password", password);
            formData.add("scope", "openid profile email");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            HttpEntity<MultiValueMap<String, String>> request =
                new HttpEntity<>(formData, headers);

            String tokenUrl = serverUrl + "/realms/" + realm + "/protocol/openid-connect/token";
            log.debug("Making token request to: {}", tokenUrl);

            Map<String, Object> response = restTemplate.postForObject(tokenUrl, request, Map.class);

            if (response != null && response.containsKey("access_token")) {
                log.info("User {} authenticated successfully", username);
                
                // Get user profile from Keycloak
                UserProfileResponse userProfile = getUserProfileFromKeycloak(username);

                return new AuthResponse(
                    (String) response.get("access_token"),
                    (String) response.get("refresh_token"),
                    "Bearer",
                    ((Number) response.get("expires_in")).longValue(),
                    userProfile
                );
            } else {
                throw new RuntimeException("Authentication failed - invalid response");
            }
        } catch (Exception e) {
            log.error("Authentication failed for user {}: {}", username, e.getMessage(), e);
            throw new RuntimeException("Authentication failed: " + e.getMessage());
        }
    }

    /**
     * Refresh JWT token using refresh token
     */
    @SuppressWarnings("unchecked")
    public AuthResponse refreshToken(String refreshToken) {
        try {
            log.info("Refreshing token");

            MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
            formData.add("grant_type", "refresh_token");
            formData.add("client_id", clientId);
            formData.add("client_secret", clientSecret);
            formData.add("refresh_token", refreshToken);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            HttpEntity<MultiValueMap<String, String>> request =
                new HttpEntity<>(formData, headers);

            String tokenUrl = serverUrl + "/realms/" + realm + "/protocol/openid-connect/token";

            Map<String, Object> response = restTemplate.postForObject(tokenUrl, request, Map.class);

            if (response != null && response.containsKey("access_token")) {
                log.info("Token refreshed successfully");
                
                return new AuthResponse(
                    (String) response.get("access_token"),
                    (String) response.get("refresh_token"),
                    "Bearer",
                    ((Number) response.get("expires_in")).longValue(),
                    null
                );
            } else {
                throw new RuntimeException("Token refresh failed");
            }

        } catch (Exception e) {
            log.error("Token refresh failed: {}", e.getMessage());
            throw new RuntimeException("Token refresh failed: " + e.getMessage());
        }
    }

    /**
     * Get user profile from Keycloak (single source of truth)
     */
    public UserProfileResponse getUserProfile(String userId) {
        try {
            log.info("Getting user profile for ID: {}", userId);
            
            RealmResource realmResource = keycloakAdminClient.realm(realm);
            UserResource userResource = realmResource.users().get(userId);
            UserRepresentation user = userResource.toRepresentation();

            return mapKeycloakUserToProfile(user);

        } catch (Exception e) {
            log.error("Error getting user profile for ID {}: {}", userId, e.getMessage());
            throw new RuntimeException("User not found: " + e.getMessage());
        }
    }

    /**
     * Update user profile in Keycloak
     */
    public void updateUserProfile(String userId, UserProfileResponse updateRequest) {
        try {
            log.info("Updating user profile for ID: {}", userId);

            RealmResource realmResource = keycloakAdminClient.realm(realm);
            UserResource userResource = realmResource.users().get(userId);
            UserRepresentation user = userResource.toRepresentation();

            // Update allowed fields
            if (updateRequest.getFirstName() != null) {
                user.setFirstName(updateRequest.getFirstName());
            }
            if (updateRequest.getLastName() != null) {
                user.setLastName(updateRequest.getLastName());
            }
            if (updateRequest.getEmail() != null) {
                user.setEmail(updateRequest.getEmail());
            }

            // Update custom attributes
            Map<String, List<String>> attributes = user.getAttributes();
            if (attributes == null) {
                attributes = new HashMap<>();
            }
            
            if (updateRequest.getPhoneNumber() != null) {
                attributes.put("phoneNumber", List.of(updateRequest.getPhoneNumber()));
            }
            if (updateRequest.getProfilePictureUrl() != null) {
                attributes.put("profilePictureUrl", List.of(updateRequest.getProfilePictureUrl()));
            }

            user.setAttributes(attributes);
            userResource.update(user);

            log.info("User profile updated successfully for ID: {}", userId);

        } catch (Exception e) {
            log.error("Error updating user profile for ID {}: {}", userId, e.getMessage());
            throw new RuntimeException("Failed to update user profile: " + e.getMessage());
        }
    }

    // Private helper methods

    /**
     * Validate Keycloak connection and permissions
     */
    private void validateKeycloakConnection() {
        try {
            log.info("Validating Keycloak connection and permissions for realm: {}", realm);

            RealmResource realmResource = keycloakAdminClient.realm(realm);
            UsersResource usersResource = realmResource.users();

            // Try to list users with a small count to test permissions
            usersResource.list(0, 1);
            log.info("Keycloak connection validated successfully. Can access users resource.");

        } catch (Exception e) {
            log.error("Failed to validate Keycloak connection: {}", e.getMessage());
            if (e.getMessage().contains("403") || e.getMessage().contains("Forbidden")) {
                log.error("Permission denied. Please ensure the service account has proper roles assigned.");
                log.error("Required roles in realm-management client: manage-users, view-users, query-users");
            }
            throw new RuntimeException("Keycloak connection validation failed: " + e.getMessage());
        }
    }

    private boolean userExists(String username, String email) {
        try {
            RealmResource realmResource = keycloakAdminClient.realm(realm);
            UsersResource usersResource = realmResource.users();

            // Check by username
            List<UserRepresentation> usersByUsername = usersResource.search(username, true);
            if (!usersByUsername.isEmpty()) {
                return true;
            }

            // Check by email
            List<UserRepresentation> usersByEmail = usersResource.search(null, email, null, null, 0, 1);
            return !usersByEmail.isEmpty();

        } catch (Exception e) {
            log.error("Error checking if user exists: {}", e.getMessage());
            return false;
        }
    }

    private String createKeycloakUser(UserRegistrationRequest request) {
        try {
            log.info("Creating user in Keycloak: {}", request.getUsername());

            RealmResource realmResource = keycloakAdminClient.realm(realm);
            UsersResource usersResource = realmResource.users();

            UserRepresentation user = new UserRepresentation();
            user.setUsername(request.getUsername());
            user.setEmail(request.getEmail());
            user.setFirstName(request.getFirstName());
            user.setLastName(request.getLastName());
            user.setEnabled(true);
            user.setEmailVerified(true);

            // Set custom attributes
            Map<String, List<String>> attributes = new HashMap<>();
            if (request.getPhoneNumber() != null) {
                attributes.put("phoneNumber", List.of(request.getPhoneNumber()));
            }
            user.setAttributes(attributes);

            // Set password
            CredentialRepresentation credential = new CredentialRepresentation();
            credential.setType(CredentialRepresentation.PASSWORD);
            credential.setValue(request.getPassword());
            credential.setTemporary(false);
            user.setCredentials(List.of(credential));

            log.debug("Attempting to create user with username: {} and email: {}",
                     request.getUsername(), request.getEmail());

            Response response = usersResource.create(user);
            int statusCode = response.getStatus();

            log.info("Keycloak user creation response - Status: {}, Reason: {}",
                    statusCode, response.getStatusInfo().getReasonPhrase());

            if (statusCode == 201) {
                String location = response.getHeaderString("Location");
                String userId = location.substring(location.lastIndexOf("/") + 1);
                response.close();
                log.info("User created successfully in Keycloak with ID: {}", userId);
                return userId;
            } else if (statusCode == 403) {
                response.close();
                log.error("Forbidden error creating user in Keycloak. This indicates insufficient permissions for the service account.");
                log.error("Required permissions: The client '{}' service account needs 'manage-users' and 'view-users' roles from realm-management", clientId);
                throw new RuntimeException("Forbidden: Insufficient permissions to create user. Please check Keycloak service account roles.");
            } else if (statusCode == 409) {
                response.close();
                log.error("User already exists in Keycloak: {}", request.getUsername());
                throw new RuntimeException("User already exists in Keycloak");
            } else {
                String reasonPhrase = response.getStatusInfo().getReasonPhrase();
                response.close();
                log.error("Failed to create user in Keycloak. Status: {}, Reason: {}", statusCode, reasonPhrase);
                throw new RuntimeException("Failed to create user in Keycloak: " + reasonPhrase + " (Status: " + statusCode + ")");
            }
        } catch (Exception e) {
            if (e instanceof RuntimeException) {
                throw e;
            }
            log.error("Unexpected error creating user in Keycloak: {}", e.getMessage(), e);
            throw new RuntimeException("Unexpected error creating user in Keycloak: " + e.getMessage());
        }
    }

    private void assignRoleToUser(String userId, String roleName) {
        try {
            RealmResource realmResource = keycloakAdminClient.realm(realm);
            UserResource userResource = realmResource.users().get(userId);

            RoleRepresentation role = realmResource.roles().get(roleName).toRepresentation();
            userResource.roles().realmLevel().add(List.of(role));

        } catch (Exception e) {
            log.error("Error assigning role {} to user {}: {}", roleName, userId, e.getMessage());
            throw new RuntimeException("Failed to assign role: " + e.getMessage());
        }
    }

    private UserProfileResponse getUserProfileFromKeycloak(String username) {
        try {
            RealmResource realmResource = keycloakAdminClient.realm(realm);
            UsersResource usersResource = realmResource.users();
            
            List<UserRepresentation> users = usersResource.search(username, true);
            if (users.isEmpty()) {
                throw new RuntimeException("User not found");
            }

            return mapKeycloakUserToProfile(users.get(0));

        } catch (Exception e) {
            log.error("Error getting user profile from Keycloak for username {}: {}", username, e.getMessage());
            throw new RuntimeException("Failed to get user profile: " + e.getMessage());
        }
    }

    private UserProfileResponse mapKeycloakUserToProfile(UserRepresentation user) {
        UserProfileResponse response = new UserProfileResponse();
        response.setId(user.getId());
        response.setUsername(user.getUsername());
        response.setEmail(user.getEmail());
        response.setFirstName(user.getFirstName());
        response.setLastName(user.getLastName());

        // Get custom attributes
        Map<String, List<String>> attributes = user.getAttributes();
        if (attributes != null) {
            if (attributes.containsKey("phoneNumber")) {
                response.setPhoneNumber(attributes.get("phoneNumber").get(0));
            }
            if (attributes.containsKey("profilePictureUrl")) {
                response.setProfilePictureUrl(attributes.get("profilePictureUrl").get(0));
            }
        }

        // Get user roles
        // Note: This would require additional call to get user roles
        // For simplicity, we'll set a default role here
        response.setRole("USER");
        response.setIsActive(user.isEnabled());

        return response;
    }

    /**
     * Diagnostic method to check service account permissions
     */
    public void diagnoseForbiddenError() {
        try {
            log.info("=== KEYCLOAK PERMISSION DIAGNOSIS ===");
            log.info("Realm: {}", realm);
            log.info("Client ID: {}", clientId);
            log.info("Server URL: {}", serverUrl);

            RealmResource realmResource = keycloakAdminClient.realm(realm);

            // Test 1: Access realm
            try {
                String realmName = realmResource.toRepresentation().getRealm();
                log.info("✓ Can access realm: {}", realmName);
            } catch (Exception e) {
                log.error("✗ Cannot access realm: {}", e.getMessage());
            }

            // Test 2: Access users resource
            try {
                UsersResource usersResource = realmResource.users();
                List<UserRepresentation> users = usersResource.list(0, 1);
                log.info("✓ Can list users (count: {})", users.size());
            } catch (Exception e) {
                log.error("✗ Cannot list users: {}", e.getMessage());
                if (e.getMessage().contains("403") || e.getMessage().contains("Forbidden")) {
                    log.error("This indicates missing 'view-users' or 'query-users' permission");
                }
            }

            // Test 3: Check service account roles
            log.info("=== REQUIRED ACTIONS ===");
            log.info("1. Go to Keycloak Admin Console");
            log.info("2. Navigate to: Realms > {} > Clients > {}", realm, clientId);
            log.info("3. Settings tab: Enable 'Service accounts enabled' and 'Authorization enabled'");
            log.info("4. Service Account Roles tab: Add these roles from 'realm-management':");
            log.info("   - manage-users");
            log.info("   - view-users");
            log.info("   - query-users");
            log.info("   - view-realm");
            log.info("5. Save and restart the application");

        } catch (Exception e) {
            log.error("Diagnosis failed: {}", e.getMessage());
        }
    }
}
