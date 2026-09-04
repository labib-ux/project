package com.nagorikseba;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.domain.ComplaintTransition;
import com.nagorikseba.complaint.domain.enums.ComplaintAction;
import com.nagorikseba.complaint.domain.enums.ComplaintStatus;
import com.nagorikseba.complaint.lifecycle.ComplaintLifecycleService;
import com.nagorikseba.complaint.lifecycle.TransitionCommand;
import com.nagorikseba.complaint.repo.ComplaintRepository;
import com.nagorikseba.complaint.repo.ComplaintTransitionRepository;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.identity.domain.UserMunicipalityMembership;
import com.nagorikseba.identity.repo.MembershipRepository;
import com.nagorikseba.identity.repo.UserRepository;
import com.nagorikseba.municipality.entity.Municipality;
import com.nagorikseba.municipality.repository.MunicipalityRepository;
import com.nagorikseba.shared.exception.ConflictException;
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

import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §7.1 end to end: legal transitions, illegal ones, concurrency, and replay.
 *
 * <p>Fixtures are find-or-create. The suite shares one container and one Spring
 * context, and nothing here is rolled back, so a plain {@code save} of a
 * fixed-email user succeeds on the first test and violates the unique index on
 * every one after it.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ComplaintLifecycleIntegrationTests {

    /** All test complaints land on this pin, inside seeded ward 1 (Gulshan, Dhaka North). */
    private static final String LAT = "23.7925";
    private static final String LNG = "90.4120";

    private static final byte[] PNG = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52};

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ComplaintRepository complaintRepository;

    @Autowired
    private ComplaintTransitionRepository transitionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MembershipRepository membershipRepository;

    @Autowired
    private MunicipalityRepository municipalityRepository;

    @Autowired
    private ComplaintLifecycleService lifecycleService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private Clock clock;

    private MockMvc mockMvc;
    private String officerToken;
    private String citizenToken;
    private User officer;
    private User citizen;

    @BeforeEach
    void setup() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();

        officer = findOrCreate("officer@test.com", "01700000100",
                "Test Officer", com.nagorikseba.enums.UserRole.DEPT_OFFICER);
        citizen = findOrCreate("citizen@test.com", "01700000200",
                "Test Citizen", com.nagorikseba.enums.UserRole.CITIZEN);

        // The officer must serve the municipality the complaint belongs to, not merely
        // hold an authority role — AuthorityComplaintController checks tenancy per
        // complaint. The membership has to exist before login, because the municipality
        // ids are baked into the access token at mint time.
        Municipality dhakaNorth = municipalityRepository.findBySlug("dhaka-north").orElseThrow();
        if (membershipRepository.findByUserIdAndValidUntilIsNull(officer.getId()).isEmpty()) {
            membershipRepository.save(UserMunicipalityMembership.builder()
                    .user(officer)
                    .municipality(dhakaNorth)
                    .validFrom(clock.instant())
                    .build());
        }

        officerToken = login("officer@test.com");
        citizenToken = login("citizen@test.com");
    }

    private User findOrCreate(String email, String phone, String name,
                              com.nagorikseba.enums.UserRole role) {
        return userRepository.findByEmailIgnoreCase(email).orElseGet(() ->
                userRepository.save(User.builder()
                        .fullName(name)
                        .email(email)
                        .phone(phone)
                        .passwordHash(passwordEncoder.encode("password"))
                        .role(role)
                        .active(true)
                        .build()));
    }

    private String login(String identifier) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"identifier\": \"" + identifier + "\", \"password\": \"password\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("accessToken").asText();
    }

    // ------------------------------------------------------------------ legal transitions

    @Test
    void legalTransitionVerifyFromSubmitted() throws Exception {
        String refCode = submitComplaint(citizenToken, "Test complaint for verify", "ROADS", null);

        mockMvc.perform(post("/api/authority/complaints/{ref}/verify", refCode)
                        .header("Authorization", "Bearer " + officerToken)
                        .param("note", "Verified"))
                .andExpect(status().isOk());

        Complaint complaint = complaintRepository.findByReferenceCode(refCode).orElseThrow();
        assertThat(complaint.getStatus()).isEqualTo(ComplaintStatus.VERIFIED);
        assertThat(complaint.getFirstVerifiedAt()).isNotNull();

        assertThat(transitionRepository.findByComplaintIdOrderByCreatedAtAsc(complaint.getId()))
                .extracting(ComplaintTransition::getAction)
                .containsExactly(ComplaintAction.SUBMIT, ComplaintAction.VERIFY);
    }

    @Test
    void legalTransitionRejectFromSubmitted() throws Exception {
        String refCode = submitComplaint(citizenToken, "Test complaint for reject", "ROADS", null);

        mockMvc.perform(post("/api/authority/complaints/{ref}/reject", refCode)
                        .header("Authorization", "Bearer " + officerToken)
                        .param("reason", "Invalid complaint"))
                .andExpect(status().isOk());

        Complaint complaint = complaintRepository.findByReferenceCode(refCode).orElseThrow();
        assertThat(complaint.getStatus()).isEqualTo(ComplaintStatus.REJECTED);
        assertThat(complaint.getRejectionReason()).isEqualTo("Invalid complaint");
        assertThat(complaint.isPublicVisible()).isFalse();
    }

    @Test
    void legalTransitionCancelFromSubmitted() throws Exception {
        String refCode = submitComplaint(citizenToken, "Test complaint for cancel", "ROADS", null);

        mockMvc.perform(post("/api/complaints/{ref}/cancel", refCode)
                        .header("Authorization", "Bearer " + citizenToken)
                        .param("reason", "No longer needed"))
                .andExpect(status().isOk());

        Complaint complaint = complaintRepository.findByReferenceCode(refCode).orElseThrow();
        assertThat(complaint.getStatus()).isEqualTo(ComplaintStatus.CANCELLED);
        assertThat(complaint.getCancellationReason()).isEqualTo("No longer needed");
    }

    // ---------------------------------------------------------------- illegal transitions

    @Test
    void illegalTransitionVerifyFromVerified() throws Exception {
        String refCode = submitComplaint(citizenToken, "Test illegal verify", "ROADS", null);

        mockMvc.perform(post("/api/authority/complaints/{ref}/verify", refCode)
                        .header("Authorization", "Bearer " + officerToken)
                        .param("note", "Verified"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/authority/complaints/{ref}/verify", refCode)
                        .header("Authorization", "Bearer " + officerToken)
                        .param("note", "Verified again"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void illegalTransitionRejectFromVerified() throws Exception {
        String refCode = submitComplaint(citizenToken, "Test illegal reject", "ROADS", null);

        mockMvc.perform(post("/api/authority/complaints/{ref}/verify", refCode)
                        .header("Authorization", "Bearer " + officerToken)
                        .param("note", "Verified"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/authority/complaints/{ref}/reject", refCode)
                        .header("Authorization", "Bearer " + officerToken)
                        .param("reason", "Late rejection"))
                .andExpect(status().isUnprocessableEntity());
    }

    // ------------------------------------------------------------------------ concurrency

    /**
     * R1 — two officers verify the same complaint at the same instant. The row lock
     * serialises them; the loser re-reads a complaint that is already VERIFIED and is
     * refused. Exactly one may win: if both did, the audit log would show the same
     * transition twice and the lock would be doing nothing.
     */
    @Test
    void concurrentVerifyOneSucceedsOneFails() throws Exception {
        String refCode = submitComplaint(citizenToken, "Concurrent verify test", "ROADS", null);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();

        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    int httpStatus = mockMvc.perform(post("/api/authority/complaints/{ref}/verify", refCode)
                                    .header("Authorization", "Bearer " + officerToken)
                                    .param("note", "Concurrent verify"))
                            .andReturn().getResponse().getStatus();
                    if (httpStatus == 200) {
                        ok.incrementAndGet();
                    } else if (httpStatus == 409 || httpStatus == 422) {
                        refused.incrementAndGet();
                    }
                } catch (Exception e) {
                    refused.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(ok.get()).isEqualTo(1);
        assertThat(refused.get()).isEqualTo(1);

        Complaint finalComplaint = complaintRepository.findByReferenceCode(refCode).orElseThrow();
        assertThat(finalComplaint.getStatus()).isEqualTo(ComplaintStatus.VERIFIED);

        assertThat(transitionRepository.findByComplaintIdOrderByCreatedAtAsc(finalComplaint.getId()))
                .extracting(ComplaintTransition::getAction)
                .containsExactly(ComplaintAction.SUBMIT, ComplaintAction.VERIFY);
    }

    /**
     * A caller holding a stale {@code expectedVersion} is refused with 409 rather than
     * silently overwriting whatever changed underneath them.
     */
    @Test
    void versionMismatchIsRejectedAsConflict() throws Exception {
        String refCode = submitComplaint(citizenToken, "Version mismatch test", "ROADS", null);
        Complaint complaint = complaintRepository.findByReferenceCode(refCode).orElseThrow();

        TransitionCommand stale = TransitionCommand.of(
                ComplaintAction.VERIFY,
                complaint.getId(),
                officer.getId(),
                "Stale write",
                null,
                complaint.getVersion() + 7);

        assertThatThrownBy(() -> lifecycleService.execute(stale))
                .isInstanceOf(ConflictException.class);

        assertThat(complaintRepository.findByReferenceCode(refCode).orElseThrow().getStatus())
                .isEqualTo(ComplaintStatus.SUBMITTED);
    }

    // ------------------------------------------------------------------------- idempotency

    @Test
    void idempotencyReplayReturnsSameReferenceCode() throws Exception {
        String key = "test-idempotency-key-" + System.nanoTime();
        long before = complaintRepository.count();

        String refCode1 = submitComplaint(citizenToken, "Idempotency test", "ROADS", key);
        String refCode2 = submitComplaint(citizenToken, "Idempotency test", "ROADS", key);

        assertThat(refCode2).isEqualTo(refCode1);
        assertThat(complaintRepository.count()).isEqualTo(before + 1);
    }

    @Test
    void cancelOnlyByOwner() throws Exception {
        String refCode = submitComplaint(citizenToken, "Owner cancel test", "ROADS", null);

        mockMvc.perform(post("/api/complaints/{ref}/cancel", refCode)
                        .header("Authorization", "Bearer " + officerToken)
                        .param("reason", "Officer cancel"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/complaints/{ref}/cancel", refCode)
                        .header("Authorization", "Bearer " + citizenToken)
                        .param("reason", "Owner cancel"))
                .andExpect(status().isOk());
    }

    // ----------------------------------------------------------------------------- helpers

    private String submitComplaint(String token, String title, String category,
                                   String idempotencyKey) throws Exception {
        var builder = multipart("/api/complaints")
                .file(new MockMultipartFile("photos", "issue.png", "image/png", PNG))
                .header("Authorization", "Bearer " + token)
                .param("title", title)
                .param("description", "Description")
                .param("category", category)
                .param("latitude", LAT)
                .param("longitude", LNG);

        if (idempotencyKey != null) {
            builder = builder.header("Idempotency-Key", idempotencyKey);
        }

        MvcResult result = mockMvc.perform(builder).andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("referenceCode").asText();
    }
}
