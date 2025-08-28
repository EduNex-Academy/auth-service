package com.edu.auth_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

@SpringBootApplication
public class AuthServiceApplication {

	public static void main(String[] args) {
		loadDotenv();
		SpringApplication.run(AuthServiceApplication.class, args);
	}

	private static void loadDotenv() {
		try {
			Path envFile = Paths.get(".env");
			if (Files.exists(envFile)) {
				Properties props = new Properties();
				Files.lines(envFile)
						.filter(line -> !line.trim().isEmpty() && !line.trim().startsWith("#"))
						.forEach(line -> {
							String[] parts = line.split("=", 2);
							if (parts.length == 2) {
								String key = parts[0].trim();
								String value = parts[1].trim();
								System.setProperty(key, value);
							}
						});
			}
		} catch (IOException e) {
			System.err.println("Warning: Could not load .env file: " + e.getMessage());
		}
	}
}
