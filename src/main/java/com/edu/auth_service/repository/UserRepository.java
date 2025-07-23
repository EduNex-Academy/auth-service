package com.edu.auth_service.repository;

import com.edu.auth_service.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;

@Repository
public interface UserRepository extends JpaRepository<User, String> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    List<User> findByRole(User.UserRole role);

    List<User> findByIsActiveTrue();

    List<User> findBySubscriptionStatus(User.SubscriptionStatus subscriptionStatus);

    @Query("SELECT u FROM User u WHERE u.subscriptionExpiresAt < CURRENT_TIMESTAMP AND u.subscriptionStatus != 'FREE'")
    List<User> findUsersWithExpiredSubscriptions();

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);
}
