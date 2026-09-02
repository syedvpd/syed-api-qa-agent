package com.syed.apiqa.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syed.apiqa.persistence.UserCredentialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserCredentialRepository userCredentialRepository;

    @BeforeEach
    void clean() {
        userCredentialRepository.deleteAll();
    }

    @Test
    void shouldRegisterNewUniqueSessionWhenNoUserIdProvided() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn();

        Map resp = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        assertNotNull(resp.get("token"));
        assertNotNull(resp.get("userId"));
        assertNotNull(resp.get("userSecret"));
        assertTrue(resp.get("userId").toString().startsWith("usr_"));
    }

    @Test
    void shouldAuthenticateExistingUserWithValidSecret() throws Exception {
        // 1. Create user
        MvcResult res1 = mockMvc.perform(post("/api/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "userId", "alice-dev",
                                "userSecret", "secret123"
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        Map data1 = objectMapper.readValue(res1.getResponse().getContentAsString(), Map.class);
        assertEquals("alice-dev", data1.get("userId"));

        // 2. Authenticate again with valid secret
        MvcResult res2 = mockMvc.perform(post("/api/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "userId", "alice-dev",
                                "userSecret", "secret123"
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        Map data2 = objectMapper.readValue(res2.getResponse().getContentAsString(), Map.class);
        assertNotNull(data2.get("token"));
    }

    @Test
    void shouldRejectImpersonationOfExistingUserWithoutSecretOrWithWrongSecret() throws Exception {
        // 1. Create protected user
        mockMvc.perform(post("/api/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "userId", "bob-admin",
                                "userSecret", "super-secret"
                        ))))
                .andExpect(status().isOk());

        // 2. Attempt impersonation with no secret
        mockMvc.perform(post("/api/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "userId", "bob-admin"
                        ))))
                .andExpect(status().isUnauthorized());

        // 3. Attempt impersonation with wrong secret
        mockMvc.perform(post("/api/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "userId", "bob-admin",
                                "userSecret", "wrong-password"
                        ))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldIssueTokenForValidM2mApiKey() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "apiKey", "ci-pipeline-default-secret-key-32b"
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        Map data = objectMapper.readValue(res.getResponse().getContentAsString(), Map.class);
        assertEquals("ci-pipeline", data.get("userId"));
        assertNotNull(data.get("token"));
    }
}
