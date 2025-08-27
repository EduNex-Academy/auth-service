package com.edu.auth_service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "app.security.cookies")
public class CookieConfig {

    /**
     * Name of the refresh token cookie
     */
    private String refreshTokenName = "refreshToken";

    /**
     * Maximum age of refresh token cookie in days
     */
    private int refreshTokenMaxAgeDays = 1;

    /**
     * Whether cookies should be secure (HTTPS only)
     * Should be true in production
     */
    private boolean secure = true;

    /**
     * SameSite policy for cookies
     * Options: Strict, Lax, None
     * Use "None" for cross-origin HTTPS requests with credentials
     */
    private String sameSite = "None";

    /**
     * Path for the cookies
     */
    private String path = "/";

    /**
     * Whether to use HttpOnly cookies
     */
    private boolean httpOnly = true;
}
