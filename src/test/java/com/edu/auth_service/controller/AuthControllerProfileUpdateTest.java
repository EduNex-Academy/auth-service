package com.edu.auth_service.controller;

import com.edu.auth_service.dto.ProfileUpdateRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureTestMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureTestMvc
public class AuthControllerProfileUpdateTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @WithMockUser(username = "testuser", authorities = {"USER"})
    public void testUpdateProfile_Success() throws Exception {
        ProfileUpdateRequest request = new ProfileUpdateRequest();
        request.setFirstName("John");
        request.setLastName("Doe");
        request.setPhoneNumber("+1234567890");
        request.setBio("Software engineer passionate about building great products");
        request.setLocation("San Francisco, CA");
        request.setDateOfBirth(LocalDate.of(1990, 1, 15));
        request.setProfilePictureUrl("https://example.com/avatar.jpg");

        mockMvc.perform(post("/api/auth/update-profile")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.message").value("Profile updated successfully"));
    }

    @Test
    @WithMockUser(username = "testuser", authorities = {"USER"})
    public void testUpdateProfile_PartialUpdate() throws Exception {
        ProfileUpdateRequest request = new ProfileUpdateRequest();
        request.setFirstName("Jane");
        request.setBio("Updated bio");

        mockMvc.perform(post("/api/auth/update-profile")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));
    }

    @Test
    @WithMockUser(username = "testuser", authorities = {"USER"})
    public void testUpdateProfile_InvalidPhoneNumber() throws Exception {
        ProfileUpdateRequest request = new ProfileUpdateRequest();
        request.setPhoneNumber("invalid-phone");

        mockMvc.perform(post("/api/auth/update-profile")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void testUpdateProfile_Unauthorized() throws Exception {
        ProfileUpdateRequest request = new ProfileUpdateRequest();
        request.setFirstName("John");

        mockMvc.perform(post("/api/auth/update-profile")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }
}
