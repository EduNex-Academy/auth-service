package com.edu.auth_service.service;

import com.edu.auth_service.dto.AuthCallbackRequest;
import com.edu.auth_service.dto.AuthResponse;
import com.edu.auth_service.dto.UserProfileResponse;
import com.edu.auth_service.util.KeycloakUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserResource;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class KeycloakCallbackService {

    private final Keycloak keycloak;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${keycloak.realm}")
    private String realm;

    @Value("${keycloak.client-id}")
    private String clientId;

    @Value("${keycloak.client-secret}")
    private String clientSecret;

    @Value("${keycloak.server-url}")
    private String serverUrl;

    @Value("${KEYCLOAK_STUDENT_REDIRECT_URI}")
    private String studentRedirectUri;

    @Value("${KEYCLOAK_ADMIN_REDIRECT_URI}")
    private String adminRedirectUri;

    @Value("${KEYCLOAK_INSTRUCTOR_REDIRECT_URI}")
    private String instructorRedirectUri;

    public Map<String, Object> handleAuthCallback(AuthCallbackRequest request) {
        try {
            log.info("Processing OAuth callback for authorization code: {}",
                request.getCode().substring(0, 10) + "...");

            // Exchange authorization code for tokens
            Map<String, Object> tokenResponse = exchangeCodeForTokens(request.getCode(), request.getUserRole());

            String accessToken = (String) tokenResponse.get("access_token");
            String refreshToken = (String) tokenResponse.get("refresh_token");
            long expiresIn = ((Number) tokenResponse.get("expires_in")).longValue();

            // Get user info from access token
            Map<String, Object> userInfo = getUserInfoFromToken(accessToken);

            // Assign a role to user
            assignRoleToUser((String) userInfo.get("sub"), request.getUserRole());

            // Create user profile response from Keycloak data
            UserProfileResponse userProfile = createUserProfileFromKeycloakInfo(userInfo);

            log.info("OAuth callback processed successfully for user: {}", userProfile.getUsername());

            // Create AuthResponse without a refresh token for response body
            AuthResponse authResponse = new AuthResponse(
                accessToken,
                "Bearer",
                expiresIn,
                userProfile
            );

            // Return both refresh token and auth response
            Map<String, Object> result = new HashMap<>();
            result.put("refreshToken", refreshToken);
            result.put("authResponse", authResponse);

            return result;

        } catch (Exception e) {
            log.error("OAuth callback handling failed: ", e);
            throw new RuntimeException("OAuth callback handling failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> exchangeCodeForTokens(String code, String userRole) {
        String effectiveRedirectUri = "ADMIN".equalsIgnoreCase(userRole) ? adminRedirectUri :
                "INSTRUCTOR".equalsIgnoreCase(userRole) ? instructorRedirectUri : studentRedirectUri;
        try {
            log.debug("Exchanging authorization code for tokens using redirect_uri: {}", effectiveRedirectUri);

            MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
            formData.add("grant_type", "authorization_code");
            formData.add("client_id", clientId);
            formData.add("client_secret", clientSecret);
            formData.add("code", code);
            formData.add("redirect_uri", effectiveRedirectUri);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            HttpEntity<MultiValueMap<String, String>> request =
                new HttpEntity<>(formData, headers);

            String tokenUrl = serverUrl + "/realms/" + realm + "/protocol/openid-connect/token";
            log.debug("Token exchange URL: {}", tokenUrl);

            Map<String, Object> response = restTemplate.postForObject(
                tokenUrl, request, Map.class);

            if (response != null && response.containsKey("access_token")) {
                log.debug("Token exchange successful");
                return response;
            } else {
                throw new RuntimeException("Failed to exchange authorization code for tokens");
            }

        } catch (Exception e) {
            log.error("Token exchange failed for redirect_uri: {}. Error: {}", effectiveRedirectUri, e.getMessage());
            log.error("Please ensure the redirect_uri matches exactly what's configured in Keycloak client settings");
            throw new RuntimeException("Token exchange failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
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
            log.error("Failed to get user info: ", e);
            throw new RuntimeException("Failed to get user info: " + e.getMessage());
        }
    }

    private UserProfileResponse createUserProfileFromKeycloakInfo(Map<String, Object> userInfo) {
        try {
            // Get the user ID from userInfo
            String userId = (String) userInfo.get("sub");

            // Fetch complete user representation from Keycloak Admin API
            RealmResource realmResource = keycloak.realm(realm);
            UserResource userResource = realmResource.users().get(userId);
            UserRepresentation user = userResource.toRepresentation();

            // Use the shared mapper utility
            return KeycloakUserMapper.mapKeycloakUserToProfile(user, keycloak, realm);

        } catch (Exception e) {
            log.error("Error creating user profile from Keycloak info: {}", e.getMessage());
            throw new RuntimeException("Failed to get complete user profile: " + e.getMessage());
        }
    }

    private void assignRoleToUser(String userId, String userRole) {
        try {
            RealmResource realmResource = keycloak.realm(realm);
            UserResource userResource = realmResource.users().get(userId);

            // Check if a role already assigned
            List<RoleRepresentation> existingRoles = userResource.roles().realmLevel().listEffective();
            boolean hasUserRole = existingRoles.stream()
                    .anyMatch(role -> role.getName().equals(userRole));

            if (!hasUserRole) {
                RoleRepresentation userRoleRep = realmResource.roles().get(userRole).toRepresentation();
                userResource.roles().realmLevel().add(Collections.singletonList(userRoleRep));
                log.info("Assigned role '{}' to user: {}", userRole, userId);
            } else {
                log.info("User {} already has '{}' role", userId, userRole);
            }
        } catch (Exception e) {
            log.error("Failed to assign role to user {}: {}", userId, e.getMessage());
            throw new RuntimeException("Role assignment failed: " + e.getMessage());
        }
    }

}
