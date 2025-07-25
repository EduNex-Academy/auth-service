package com.edu.auth_service.service;

import com.edu.auth_service.dto.*;
import com.edu.auth_service.entity.User;
import com.edu.auth_service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import jakarta.ws.rs.core.Response;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

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

    public AuthResponse registerUser(UserRegistrationRequest request) {
        try {
            // Create user in Keycloak
            String keycloakUserId = createKeycloakUser(request);

            // Create user in local database
            User user = createLocalUser(keycloakUserId, request);
            userRepository.save(user);

            // Assign role to user in Keycloak
            assignRoleToUser(keycloakUserId, request.getRole());

            // Authenticate user and return tokens
            return authenticateUser(request.getUsername(), request.getPassword());

        } catch (Exception e) {
            log.error("Error registering user: ", e);
            throw new RuntimeException("Failed to register user: " + e.getMessage());
        }
    }

    public AuthResponse authenticateUser(String username, String password) {
        try {
            MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
            formData.add("grant_type", "password");
            formData.add("client_id", clientId);
            formData.add("client_secret", clientSecret);
            formData.add("username", username);
            formData.add("password", password);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            HttpEntity<MultiValueMap<String, String>> request =
                new HttpEntity<>(formData, headers);

            String tokenUrl = serverUrl + "/realms/" + realm + "/protocol/openid-connect/token";

            Map<String, Object> response = restTemplate.postForObject(
                tokenUrl, request, Map.class);

            if (response != null && response.containsKey("access_token")) {
                // Update last login time
                User user = userRepository.findByUsername(username)
                    .orElseThrow(() -> new RuntimeException("User not found"));
                user.setLastLoginAt(LocalDateTime.now());
                userRepository.save(user);

                return new AuthResponse(
                    (String) response.get("access_token"),
                    (String) response.get("refresh_token"),
                    "Bearer",
                    ((Number) response.get("expires_in")).longValue(),
                    mapToUserProfileResponse(user)
                );
            } else {
                throw new RuntimeException("Authentication failed");
            }

        } catch (Exception e) {
            log.error("Authentication error: ", e);
            throw new RuntimeException("Authentication failed: " + e.getMessage());
        }
    }

    public AuthResponse refreshToken(String refreshToken) {
        try {
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

            Map<String, Object> response = restTemplate.postForObject(
                tokenUrl, request, Map.class);

            if (response != null && response.containsKey("access_token")) {
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
            log.error("Token refresh error: ", e);
            throw new RuntimeException("Token refresh failed: " + e.getMessage());
        }
    }

    private String createKeycloakUser(UserRegistrationRequest request) {
        RealmResource realmResource = keycloak.realm(realm);
        UsersResource usersResource = realmResource.users();

        UserRepresentation user = new UserRepresentation();
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());
        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setEnabled(true);
        user.setEmailVerified(true);

        // Set password
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(request.getPassword());
        credential.setTemporary(false);
        user.setCredentials(List.of(credential));

        Response response = usersResource.create(user);

        if (response.getStatus() == 201) {
            String location = response.getHeaderString("Location");
            return location.substring(location.lastIndexOf("/") + 1);
        } else {
            throw new RuntimeException("Failed to create user in Keycloak: " +
                response.getStatusInfo().getReasonPhrase());
        }
    }

    private User createLocalUser(String keycloakUserId, UserRegistrationRequest request) {
        User user = new User();
        user.setId(keycloakUserId);
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());
        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setPhoneNumber(request.getPhoneNumber());
        user.setRole(User.UserRole.valueOf(request.getRole()));
        user.setIsActive(true);
        user.setSubscriptionStatus(User.SubscriptionStatus.FREE);
        return user;
    }

    private void assignRoleToUser(String userId, String roleName) {
        RealmResource realmResource = keycloak.realm(realm);
        UserResource userResource = realmResource.users().get(userId);

        RoleRepresentation role = realmResource.roles().get(roleName).toRepresentation();
        userResource.roles().realmLevel().add(List.of(role));
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

    public UserProfileResponse getUserProfile(String userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));
        return mapToUserProfileResponse(user);
    }

    public void updateUserProfile(String userId, UserProfileResponse updateRequest) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));

        if (updateRequest.getFirstName() != null) {
            user.setFirstName(updateRequest.getFirstName());
        }
        if (updateRequest.getLastName() != null) {
            user.setLastName(updateRequest.getLastName());
        }
        if (updateRequest.getPhoneNumber() != null) {
            user.setPhoneNumber(updateRequest.getPhoneNumber());
        }
        if (updateRequest.getProfilePictureUrl() != null) {
            user.setProfilePictureUrl(updateRequest.getProfilePictureUrl());
        }

        userRepository.save(user);
    }
}
