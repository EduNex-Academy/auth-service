package com.edu.auth_service.util;

import com.edu.auth_service.dto.UserProfileResponse;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class KeycloakUserMapper {

    /**
     * Maps Keycloak UserRepresentation to UserProfileResponse
     * This method handles all common mapping logic including roles, attributes, and timestamps
     */
    public static UserProfileResponse mapKeycloakUserToProfile(UserRepresentation user, Keycloak keycloakClient, String realm) {
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

        // Get actual realm roles from Keycloak
        String userRole = getUserRole(user.getId(), keycloakClient, realm);
        response.setRole(userRole);

        response.setIsActive(user.isEnabled());
        response.setCreatedAt(
                user.getCreatedTimestamp() != null ?
                        Instant.ofEpochMilli(user.getCreatedTimestamp()).atZone(ZoneId.systemDefault()).toLocalDateTime()
                        : null
        );
        response.setLastLoginAt(LocalDateTime.now());
        response.setEmailVerified(user.isEmailVerified());
        response.setTwoFactorEnabled(
                user.getRequiredActions() != null && user.getRequiredActions().contains("CONFIGURE_TOTP")
        );

        return response;
    }

    /**
     * Gets the meaningful user role from Keycloak, filtering out default system roles
     */
    private static String getUserRole(String userId, Keycloak keycloakClient, String realm) {
        try {
            RealmResource realmResource = keycloakClient.realm(realm);
            UserResource userResource = realmResource.users().get(userId);
            List<RoleRepresentation> userRoles = userResource.roles().realmLevel().listEffective();

            if (userRoles != null && !userRoles.isEmpty()) {
                // Filter out default Keycloak roles and get meaningful roles
                return userRoles.stream()
                        .map(RoleRepresentation::getName)
                        .filter(role -> !role.equals("default-roles-" + realm) &&
                                !role.equals("offline_access") &&
                                !role.equals("uma_authorization"))
                        .findFirst()
                        .orElse("USER");
            }
            return "USER";
        } catch (Exception e) {
            log.error("Error getting user roles for user {}: {}", userId, e.getMessage());
            return "USER"; // fallback to default role
        }
    }
}
