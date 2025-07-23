package com.edu.auth_service.controller;

import com.edu.auth_service.dto.UserProfileResponse;
import com.edu.auth_service.entity.User;
import com.edu.auth_service.service.UserManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class UserManagementController {

    private final UserManagementService userManagementService;

    @GetMapping
    public ResponseEntity<List<UserProfileResponse>> getAllUsers() {
        List<UserProfileResponse> users = userManagementService.getAllUsers();
        return ResponseEntity.ok(users);
    }

    @GetMapping("/role/{role}")
    public ResponseEntity<List<UserProfileResponse>> getUsersByRole(@PathVariable String role) {
        User.UserRole userRole = User.UserRole.valueOf(role.toUpperCase());
        List<UserProfileResponse> users = userManagementService.getUsersByRole(userRole);
        return ResponseEntity.ok(users);
    }

    @PostMapping("/{userId}/deactivate")
    public ResponseEntity<Void> deactivateUser(@PathVariable String userId) {
        userManagementService.deactivateUser(userId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{userId}/activate")
    public ResponseEntity<Void> activateUser(@PathVariable String userId) {
        userManagementService.activateUser(userId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{userId}/subscription")
    public ResponseEntity<Void> updateSubscription(
            @PathVariable String userId,
            @RequestParam String status,
            @RequestParam(required = false) String expiresAt) {

        User.SubscriptionStatus subscriptionStatus = User.SubscriptionStatus.valueOf(status.toUpperCase());
        LocalDateTime expiration = expiresAt != null ? LocalDateTime.parse(expiresAt) : null;

        userManagementService.updateUserSubscription(userId, subscriptionStatus, expiration);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/expired-subscriptions")
    public ResponseEntity<List<UserProfileResponse>> getExpiredSubscriptions() {
        List<UserProfileResponse> users = userManagementService.getExpiredSubscriptions();
        return ResponseEntity.ok(users);
    }
}
