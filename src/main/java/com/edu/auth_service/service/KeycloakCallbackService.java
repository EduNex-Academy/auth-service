package com.edu.auth_service.service;

import com.edu.auth_service.dto.AuthCallbackRequest;
import com.edu.auth_service.dto.AuthResponse;
import com.edu.auth_service.dto.UserProfileResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

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

    @Value("${KEYCLOAK_REDIRECT_URI}")
    private String redirectUri;

    public AuthResponse handleAuthCallback(AuthCallbackRequest request) {
        try {
            log.info("Processing OAuth callback for authorization code: {}",
                request.getCode().substring(0, 10) + "...");

            // Exchange authorization code for tokens
            Map<String, Object> tokenResponse = exchangeCodeForTokens(request.getCode());

            String accessToken = (String) tokenResponse.get("access_token");
            String refreshToken = (String) tokenResponse.get("refresh_token");
            Long expiresIn = ((Number) tokenResponse.get("expires_in")).longValue();

            // Get user info from access token
            Map<String, Object> userInfo = getUserInfoFromToken(accessToken);

            // Create user profile response from Keycloak data
            UserProfileResponse userProfile = createUserProfileFromKeycloakInfo(userInfo);

            log.info("OAuth callback processed successfully for user: {}", userProfile.getUsername());

            return new AuthResponse(
                accessToken,
                refreshToken,
                "Bearer",
                expiresIn,
                userProfile
            );

        } catch (Exception e) {
            log.error("OAuth callback handling failed: ", e);
            throw new RuntimeException("OAuth callback handling failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> exchangeCodeForTokens(String code) {
        try {
            log.debug("Exchanging authorization code for tokens using redirect_uri: {}", redirectUri);

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
            log.error("Token exchange failed for redirect_uri: {}. Error: {}", redirectUri, e.getMessage());
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
        UserProfileResponse profile = new UserProfileResponse();
        
        profile.setId((String) userInfo.get("sub"));
        profile.setUsername((String) userInfo.get("preferred_username"));
        profile.setEmail((String) userInfo.get("email"));
        profile.setFirstName((String) userInfo.get("given_name"));
        profile.setLastName((String) userInfo.get("family_name"));
        
        // Set default values
        profile.setIsActive(true);
        profile.setRole("USER"); // Default role, can be enhanced to read from Keycloak roles
        
        return profile;
    }
}
