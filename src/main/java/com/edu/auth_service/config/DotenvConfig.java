package com.edu.auth_service.config;

import me.paulschwarz.springdotenv.DotenvPropertySource;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

public class DotenvConfig implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        DotenvPropertySource.addToEnvironment(applicationContext.getEnvironment());
    }
}
