package com.nagorikseba;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.domain.ComplaintTransition;
import com.nagorikseba.complaint.domain.ResolutionAttempt;
import com.nagorikseba.complaint.domain.enums.ComplaintAction;
import com.nagorikseba.complaint.domain.enums.ComplaintStatus;
import com.nagorikseba.complaint.lifecycle.AutoCloseScheduler;
import com.nagorikseba.complaint.repo.ComplaintRepository;
import com.nagorikseba.complaint.repo.ComplaintTransitionRepository;
import com.nagorikseba.complaint.repo.ResolutionAttemptRepository;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.identity.domain.UserMunicipalityMembership;
import com.nagorikseba.identity.repo.MembershipRepository;
import com.nagorikseba.identity.repo.UserRepository;
import com.nagorikseba.municipality.entity.Department;
import com.nagorikseba.municipality.entity.Municipality;
import com.nagorikseba.municipality.repository.DepartmentRepository;
import com.nagorikseba.municipality.repository.MunicipalityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AUTO_CLOSE (§6): RESOLVED + 7 silent days → CLOSED with no rating.
 *
 * <p>Drives a complaint to RESOLVED through the real API, backdates
 * {@code resolved_at} past the grace period (the scheduler reads it, never the
 * wall clock), then asserts the scheduler closes it exactly once — and leaves
 * a fresh RESOLVED complaint alone.
 */
@SpringBootTest(properties = "app.scheduling.enabled=true")
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class AutoCloseIntegrationTests {

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
    private ResolutionAttemptRepository attemptRepository;
    @Autowired
    private DepartmentRepository departmentRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private MembershipRepository membershipRepository;
    @Autowired
    private MunicipalityRepository municipalityRepository;
    @Autowired
    private AutoCloseScheduler autoCloseScheduler;
    @Autowired
    private JdbcTemplate jdbcTemplate;
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

    @Test
    void staleResolvedComplaintAutoClosesExactlyOnce() throws Exception {
        String staleRef = resolveComplaint("Auto-close stale test");

        jdbcTemplate.update(
                "UPDATE complaints SET resolved_at = now() - interval '8 days' WHERE reference_code = ?",
                staleRef);

        // At least the backdated complaint closes; seeded demo rows past their
        // own grace period may close too, so the count is a lower bound, not exact.
        assertThat(autoCloseScheduler.autoCloseOnce()).isPositive();

        Complaint closedComplaint = complaintRepository.findByReferenceCode(staleRef).orElseThrow();
        assertThat(closedComplaint.getStatus()).isEqualTo(ComplaintStatus.CLOSED);
        assertThat(closedComplaint.getClosedAt()).isNotNull();

        ResolutionAttempt attempt = attemptRepository
                .findFirstByComplaintIdOrderByAttemptNumberDesc(closedComplaint.getId()).orElseThrow();
        assertThat(attempt.getOutcome()).isEqualTo(ResolutionAttempt.Outcome.CLOSED);
        assertThat(attempt.getRating()).isNull();

        ComplaintTransition autoClose = transitionRepository
                .findByComplaintIdOrderByCreatedAtAsc(closedComplaint.getId()).stream()
                .filter(t -> t.getAction() == ComplaintAction.AUTO_CLOSE)
                .findFirst().orElseThrow();
        assertThat(autoClose.getActor()).isNull();
        assertThat(autoClose.getActorRole()).isEqualTo("SYSTEM");

        // Second pass converges: this complaint is CLOSED, so it is never due again.
        autoCloseScheduler.autoCloseOnce();
        Complaint stillClosed = complaintRepository.findByReferenceCode(staleRef).orElseThrow();
        assertThat(stillClosed.getStatus()).isEqualTo(ComplaintStatus.CLOSED);
        assertThat(transitionRepository.findByComplaintIdOrderByCreatedAtAsc(stillClosed.getId()).stream()
                .filter(t -> t.getAction() == ComplaintAction.AUTO_CLOSE).count()).isEqualTo(1);
    }

    @Test
    void freshResolvedComplaintIsLeftAlone() throws Exception {
        String freshRef = resolveComplaint("Auto-close fresh test");

        // The run may close seeded demo rows past their grace period; what
        // matters is the fresh resolution is untouched.
        autoCloseScheduler.autoCloseOnce();

        Complaint stillOpen = complaintRepository.findByReferenceCode(freshRef).orElseThrow();
        assertThat(stillOpen.getStatus()).isEqualTo(ComplaintStatus.RESOLVED);
    }

    // ------------------------------------------------------------------ helpers

    private String resolveComplaint(String title) throws Exception {
        String refCode = submitComplaint(title);

        mockMvc.perform(post("/api/authority/complaints/{ref}/verify", refCode)
                        .header("Authorization", "Bearer " + officerToken)
                        .param("note", "verified"))
                .andExpect(status().isOk());

        Municipality dhakaNorth = municipalityRepository.findBySlug("dhaka-north").orElseThrow();
        Department roads = departmentRepository
                .findByMunicipalityIdAndCode(dhakaNorth.getId(), "ROADS").orElseThrow();
        User deptOfficer = findOrCreate("dept-officer@test.com", "01700000300",
                "Dept Officer", com.nagorikseba.enums.UserRole.DEPT_OFFICER);
        if (membershipRepository.findByUserIdAndValidUntilIsNull(deptOfficer.getId()).isEmpty()) {
            membershipRepository.save(UserMunicipalityMembership.builder()
                    .user(deptOfficer)
                    .municipality(dhakaNorth)
                    .department(roads)
                    .validFrom(clock.instant())
                    .build());
        }
        String deptToken = login("dept-officer@test.com");

        mockMvc.perform(post("/api/authority/complaints/{ref}/assign", refCode)
                        .header("Authorization", "Bearer " + officerToken)
                        .param("departmentId", roads.getId().toString())
                        .param("officerId", deptOfficer.getId().toString()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/authority/complaints/{ref}/start", refCode)
                        .header("Authorization", "Bearer " + deptToken))
                .andExpect(status().isOk());

        mockMvc.perform(multipart("/api/authority/complaints/{ref}/resolve", refCode)
                        .file(new MockMultipartFile("photos", "fixed.png", "image/png", PNG))
                        .header("Authorization", "Bearer " + deptToken)
                        .param("note", "fixed"))
                .andExpect(status().isOk());

        Complaint resolved = complaintRepository.findByReferenceCode(refCode).orElseThrow();
        assertThat(resolved.getStatus()).isEqualTo(ComplaintStatus.RESOLVED);
        return refCode;
    }

    private String submitComplaint(String title) throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/complaints")
                        .file(new MockMultipartFile("photos", "issue.png", "image/png", PNG))
                        .header("Authorization", "Bearer " + citizenToken)
                        .param("title", title)
                        .param("description", "Description")
                        .param("category", "ROADS")
                        .param("latitude", LAT)
                        .param("longitude", LNG))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("referenceCode").asText();
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
}
