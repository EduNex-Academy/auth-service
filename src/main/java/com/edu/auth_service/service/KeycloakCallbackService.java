package com.edu.auth_service.service;

import com.edu.auth_service.dto.AuthCallbackRequest;
import com.edu.auth_service.dto.AuthResponse;
import com.edu.auth_service.dto.UserProfileResponse;
import com.edu.auth_service.entity.User;
import com.edu.auth_service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class KeycloakCallbackService {

    private final Keycloak keycloak;
    private final UserRepository userRepository;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${keycloak.realm}")
    private String realm;

    @Value("${keycloak.client-id}")
    private String clientId;

    @Value("${keycloak.client-secret}")
    private String clientSecret;

    @Value("${keycloak.server-url}")
    private String serverUrl;

    public AuthResponse handleAuthCallback(AuthCallbackRequest request) {
        try {
            // Exchange authorization code for tokens
            Map<String, Object> tokenResponse = exchangeCodeForTokens(request.getCode());

            String accessToken = (String) tokenResponse.get("access_token");
            String refreshToken = (String) tokenResponse.get("refresh_token");
            Long expiresIn = ((Number) tokenResponse.get("expires_in")).longValue();

            // Get user info from access token
            Map<String, Object> userInfo = getUserInfoFromToken(accessToken);
            String keycloakUserId = (String) userInfo.get("sub");

            // Sync user with local database
            User user = syncUserWithDatabase(keycloakUserId, userInfo);

            return new AuthResponse(
                accessToken,
                refreshToken,
                "Bearer",
                expiresIn,
                mapToUserProfileResponse(user)
            );

        } catch (Exception e) {
            log.error("OAuth callback handling failed: ", e);
            throw new RuntimeException("OAuth callback handling failed: " + e.getMessage());
        }
    }

    private Map<String, Object> exchangeCodeForTokens(String code) {
        try {
            MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
            formData.add("grant_type", "authorization_code");
            formData.add("client_id", clientId);
            formData.add("client_secret", clientSecret);
            formData.add("code", code);
            formData.add("redirect_uri", redirectUri);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            HttpEntity<MultiValueMap<String, String>> request =
                new HttpEntity<>(formData, headers);

            String tokenUrl = serverUrl + "/realms/" + realm + "/protocol/openid-connect/token";

            Map<String, Object> response = restTemplate.postForObject(
                tokenUrl, request, Map.class);

            if (response != null && response.containsKey("access_token")) {
                return response;
            } else {
                throw new RuntimeException("Failed to exchange authorization code for tokens");
            }

        } catch (Exception e) {
            log.error("Token exchange error: ", e);
            throw new RuntimeException("Token exchange failed: " + e.getMessage());
        }
    }

    private Map<String, Object> getUserInfoFromToken(String accessToken) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);

            HttpEntity<Void> request = new HttpEntity<>(headers);

            String userInfoUrl = serverUrl + "/realms/" + realm + "/protocol/openid-connect/userinfo";

            Map<String, Object> userInfo = restTemplate.postForObject(
                userInfoUrl, request, Map.class);

            if (userInfo != null) {
                return userInfo;
            } else {
                throw new RuntimeException("Failed to get user info from token");
            }

        } catch (Exception e) {
            log.error("Get user info error: ", e);
            throw new RuntimeException("Failed to get user info: " + e.getMessage());
        }
    }

    private User syncUserWithDatabase(String keycloakUserId, Map<String, Object> userInfo) {
        try {
            User user = userRepository.findById(keycloakUserId).orElse(null);

            if (user == null) {
                // Create new user from Keycloak user info
                user = createUserFromKeycloakInfo(keycloakUserId, userInfo);
            } else {
                // Update existing user's last login
                user.setLastLoginAt(LocalDateTime.now());
                userRepository.save(user);
            }

            return user;

        } catch (Exception e) {
            log.error("User sync error: ", e);
            throw new RuntimeException("Failed to sync user: " + e.getMessage());
        }
    }

    private User createUserFromKeycloakInfo(String keycloakUserId, Map<String, Object> userInfo) {
        try {
            // Get additional user details from Keycloak Admin API
            UserRepresentation keycloakUser = getKeycloakUserDetails(keycloakUserId);

            User user = new User();
            user.setId(keycloakUserId);
            user.setUsername((String) userInfo.get("preferred_username"));
            user.setEmail((String) userInfo.get("email"));
            user.setFirstName((String) userInfo.get("given_name"));
            user.setLastName((String) userInfo.get("family_name"));
            user.setIsActive(true);
            user.setCreatedAt(LocalDateTime.now());
            user.setLastLoginAt(LocalDateTime.now());

            // Determine if user came from Google OAuth or regular registration
            if (keycloakUser.getAttributes() != null &&
                keycloakUser.getAttributes().containsKey("authProvider")) {
                List<String> authProviders = keycloakUser.getAttributes().get("authProvider");
                if (authProviders.contains("GOOGLE")) {
                    // User came from Google OAuth, default to STUDENT role
                    user.setRole(User.UserRole.STUDENT);
                }
            } else {
                // Regular registration, check user's assigned roles
                user.setRole(getUserRoleFromKeycloak(keycloakUserId));
            }

            user.setSubscriptionStatus(User.SubscriptionStatus.FREE);

            return userRepository.save(user);

        } catch (Exception e) {
            log.error("Failed to create user from Keycloak info: ", e);
            throw new RuntimeException("Failed to create user: " + e.getMessage());
        }
    }

    private UserRepresentation getKeycloakUserDetails(String userId) {
        RealmResource realmResource = keycloak.realm(realm);
        UserResource userResource = realmResource.users().get(userId);
        return userResource.toRepresentation();
    }

    private User.UserRole getUserRoleFromKeycloak(String userId) {
        try {
            RealmResource realmResource = keycloak.realm(realm);
            UserResource userResource = realmResource.users().get(userId);

            // Get user's realm roles
            var roles = userResource.roles().realmLevel().listAll();

            // Priority: ADMIN > INSTRUCTOR > STUDENT
            if (roles.stream().anyMatch(role -> role.getName().equals("ADMIN"))) {
                return User.UserRole.ADMIN;
            } else if (roles.stream().anyMatch(role -> role.getName().equals("INSTRUCTOR"))) {
                return User.UserRole.INSTRUCTOR;
            } else {
                return User.UserRole.STUDENT; // Default role
            }

        } catch (Exception e) {
            log.warn("Failed to get user role from Keycloak, defaulting to STUDENT: ", e);
            return User.UserRole.STUDENT;
        }
    }

    private UserProfileResponse mapToUserProfileResponse(User user) {
        UserProfileResponse response = new UserProfileResponse();
        response.setId(user.getId());
        response.setUsername(user.getUsername());
        response.setEmail(user.getEmail());
        response.setFirstName(user.getFirstName());
        response.setLastName(user.getLastName());
        response.setPhoneNumber(user.getPhoneNumber());
        response.setProfilePictureUrl(user.getProfilePictureUrl());
        response.setRole(user.getRole().name());
        response.setIsActive(user.getIsActive());
        response.setSubscriptionStatus(user.getSubscriptionStatus().name());
        response.setSubscriptionExpiresAt(user.getSubscriptionExpiresAt());
        response.setCreatedAt(user.getCreatedAt());
        response.setLastLoginAt(user.getLastLoginAt());
        return response;
    }
}
