package com.nagorikseba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.repo.ComplaintRepository;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.identity.repo.UserRepository;
import com.nagorikseba.shared.security.PrincipalContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class SubmissionIdempotencyTests {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ComplaintRepository complaintRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PrincipalContext principalContext;

    private MockMvc mockMvc;
    private String citizenToken;

    @Test
    void doubleSubmitWithSameIdempotencyKeyCreatesOnlyOneComplaint() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();

        // Create citizen user
        User citizen = userRepository.save(User.builder()
                .fullName("Idempotency Test Citizen")
                .email("idempotency@test.com")
                .phone("01700000300")
                .passwordHash(passwordEncoder.encode("password"))
                .role(com.nagorikseba.enums.UserRole.CITIZEN)
                .active(true)
                .build());

        // Login
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("""
                                {"identifier": "idempotency@test.com", "password": "password"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        citizenToken = objectMapper.readTree(loginResult.getResponse().getContentAsString()).path("accessToken").asText();

        String idempotencyKey = "idem-key-" + System.currentTimeMillis();
        byte[] pngBytes = new byte[]{(byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

        // First submission
        MvcResult result1 = mockMvc.perform(post("/api/complaints")
                        .header("Authorization", "Bearer " + citizenToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .param("title", "Idempotency test")
                        .param("description", "Testing idempotency")
                        .param("category", "ROADS")
                        .param("latitude", "23.7925")
                        .param("longitude", "90.4120")
                        .param("photos", pngBytes))
                .andExpect(status().isCreated())
                .andReturn();

        String refCode1 = objectMapper.readTree(result1.getResponse().getContentAsString()).path("referenceCode").asText();
        Long id1 = objectMapper.readTree(result1.getResponse().getContentAsString()).path("id").asLong();

        // Second submission with same idempotency key
        MvcResult result2 = mockMvc.perform(post("/api/complaints")
                        .header("Authorization", "Bearer " + citizenToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .param("title", "Idempotency test - second attempt")
                        .param("description", "Testing idempotency again")
                        .param("category", "ELECTRICITY")
                        .param("latitude", "23.7800")
                        .param("longitude", "90.4200")
                        .param("photos", pngBytes))
                .andExpect(status().isCreated())
                .andReturn();

        String refCode2 = objectMapper.readTree(result2.getResponse().getContentAsString()).path("referenceCode").asText();
        Long id2 = objectMapper.readTree(result2.getResponse().getContentAsString()).path("id").asLong();

        // Should return the same complaint
        assertThat(refCode2).isEqualTo(refCode1);
        assertThat(id2).isEqualTo(id1);

        // Only one complaint should exist in DB
        long count = complaintRepository.count();
        assertThat(count).isEqualTo(1);
    }

    void doubleSubmitWithoutIdempotencyKeyCreatesTwoComplaints() throws Exception {
        String idempotencyKey = "idem-key-" + System.currentTimeMillis() + "-2";
        byte[] pngBytes = new byte[]{(byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

        // First submission without idempotency key
        MvcResult result1 = mockMvc.perform(post("/api/complaints")
                        .header("Authorization", "Bearer " + citizenToken)
                        .param("title", "No idempotency test 1")
                        .param("description", "Testing without idempotency")
                        .param("category", "ROADS")
                        .param("latitude", "23.7925")
                        .param("longitude", "90.4120")
                        .param("photos", pngBytes))
                .andExpect(status().isCreated())
                .andReturn();

        // Second submission without idempotency key
        MvcResult result2 = mockMvc.perform(post("/api/complaints")
                        .header("Authorization", "Bearer " + citizenToken)
                        .param("title", "No idempotency test 2")
                        .param("description", "Testing without idempotency again")
                        .param("category", "ELECTRICITY")
                        .param("latitude", "23.7800")
                        .param("longitude", "90.4200")
                        .param("photos", pngBytes))
                .andExpect(status().isCreated())
                .andReturn();

        String refCode1 = objectMapper.readTree(result1.getResponse().getContentAsString()).path("referenceCode").asText();
        String refCode2 = objectMapper.readTree(result2.getResponse().getContentAsString()).path("referenceCode").asText();

        // Should be different
        assertThat(refCode2).isNotEqualTo(refCode1);

        // Two complaints should exist
        long count = complaintRepository.count();
        assertThat(count).isEqualTo(2);
    }
}