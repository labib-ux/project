package com.nagorikseba;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nagorikseba.complaint.repo.ComplaintRepository;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.identity.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R3 — a retried submission must not file a second complaint.
 *
 * <p>Counts are measured as deltas rather than absolutes: the seeder's demo
 * complaints share this database, and the suite reuses one container, so any
 * assertion of the form {@code count() == 1} is a false failure waiting to happen.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class SubmissionIdempotencyTests {

    /** Keeps each test's citizen unique — users are never rolled back between tests. */
    private static final AtomicInteger SEQ = new AtomicInteger();

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

    private MockMvc mockMvc;
    private String citizenToken;

    private static final byte[] PNG = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52};

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();

        int n = SEQ.incrementAndGet();
        String email = "idempotency" + n + "@test.com";

        userRepository.save(User.builder()
                .fullName("Idempotency Test Citizen " + n)
                .email(email)
                .phone("0170000030" + n)
                .passwordHash(passwordEncoder.encode("password"))
                .role(com.nagorikseba.enums.UserRole.CITIZEN)
                .active(true)
                .build());

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"identifier\": \"" + email + "\", \"password\": \"password\"}"))
                .andExpect(status().isOk())
                .andReturn();
        citizenToken = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .path("accessToken").asText();
    }

    @Test
    void doubleSubmitWithSameIdempotencyKeyCreatesOnlyOneComplaint() throws Exception {
        long before = complaintRepository.count();
        String idempotencyKey = "idem-key-" + SEQ.get();

        MvcResult result1 = submit(idempotencyKey,
                "Idempotency test", "Testing idempotency", "ROADS", "23.7925", "90.4120");

        // Deliberately different content under the same key: a replay must return the
        // complaint the key originally created, not file the new one.
        MvcResult result2 = submit(idempotencyKey,
                "Idempotency test - second attempt", "Testing idempotency again",
                "ELECTRICITY", "23.7800", "90.4200");

        assertThat(referenceCode(result2)).isEqualTo(referenceCode(result1));
        assertThat(id(result2)).isEqualTo(id(result1));
        assertThat(complaintRepository.count()).isEqualTo(before + 1);
    }

    @Test
    void doubleSubmitWithoutIdempotencyKeyCreatesTwoComplaints() throws Exception {
        long before = complaintRepository.count();

        MvcResult result1 = submit(null,
                "No idempotency test 1", "Testing without idempotency", "ROADS", "23.7925", "90.4120");
        MvcResult result2 = submit(null,
                "No idempotency test 2", "Testing without idempotency again",
                "ELECTRICITY", "23.7800", "90.4200");

        assertThat(referenceCode(result2)).isNotEqualTo(referenceCode(result1));
        assertThat(complaintRepository.count()).isEqualTo(before + 2);
    }

    private MvcResult submit(String idempotencyKey, String title, String description,
                             String category, String lat, String lng) throws Exception {
        var builder = multipart("/api/complaints")
                .file(new MockMultipartFile("photos", "issue.png", "image/png", PNG))
                .header("Authorization", "Bearer " + citizenToken)
                .param("title", title)
                .param("description", description)
                .param("category", category)
                .param("latitude", lat)
                .param("longitude", lng);

        if (idempotencyKey != null) {
            builder = builder.header("Idempotency-Key", idempotencyKey);
        }

        return mockMvc.perform(builder).andExpect(status().isCreated()).andReturn();
    }

    private String referenceCode(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("referenceCode").asText();
    }

    private Long id(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("id").asLong();
    }
}
