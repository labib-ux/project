package com.nagorikseba;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.enums.NotificationChannel;
import com.nagorikseba.enums.UserRole;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.identity.domain.UserMunicipalityMembership;
import com.nagorikseba.identity.repo.MembershipRepository;
import com.nagorikseba.identity.repo.UserRepository;
import com.nagorikseba.municipality.entity.Municipality;
import com.nagorikseba.municipality.repository.MunicipalityRepository;
import com.nagorikseba.notification.NotificationDispatcher;
import com.nagorikseba.notification.NotificationMessage;
import com.nagorikseba.notification.NotificationMessageRepository;
import com.nagorikseba.notification.NotificationRecipientResolver;
import com.nagorikseba.notification.OutboxWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The requirement this suite exists to protect: <em>when a citizen reports a
 * problem, the officer and the admin are both notified.</em>
 *
 * <p>Four things had to line up for that to hold, and three regress silently
 * because each alone compiles and boots:
 *
 * <ol>
 *   <li>the outbox relay must actually run (schedulers are off in the test profile),</li>
 *   <li>the dispatcher must fan out on {@code SUBMIT}, where no assignee exists,</li>
 *   <li>the resolver must reach officers <em>via membership</em>, not
 *       {@code users.department_id},</li>
 *   <li>the read API must expose the rows — it did not exist at all before this.</li>
 * </ol>
 */
@SpringBootTest(properties = "app.scheduling.enabled=true")
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class NotificationFanoutIntegrationTests {

    /** Inside the seeded dhaka-north boundary (see AutoCloseIntegrationTests). */
    private static final String LAT = "23.7804";
    private static final String LNG = "90.3944";

    private static final byte[] PNG = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52};

    @Autowired
    private WebApplicationContext webApplicationContext;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private OutboxWorker outboxWorker;
    @Autowired
    private NotificationDispatcher dispatcher;
    @Autowired
    private NotificationMessageRepository notificationRepository;
    @Autowired
    private NotificationRecipientResolver recipientResolver;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private MembershipRepository membershipRepository;
    @Autowired
    private MunicipalityRepository municipalityRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private Clock clock;

    private MockMvc mockMvc;
    private User citizen;
    private User officer;
    private User admin;
    private Municipality municipality;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        municipality = municipalityRepository.findBySlug("dhaka-north").orElseThrow();

        long nano = System.nanoTime();
        citizen = user("nf-citizen-" + nano + "@test.com", "0171" + pad(nano), "Notify Citizen", UserRole.CITIZEN);
        officer = user("nf-officer-" + nano + "@test.com", "0172" + pad(nano), "Notify Officer", UserRole.DEPT_OFFICER);
        admin = user("nf-admin-" + nano + "@test.com", "0173" + pad(nano), "Notify Admin", UserRole.ADMIN);

        // Only the officer gets a membership. The admin is expected to be reached
        // cross-tenant with no membership row at all — that asymmetry is the point.
        membershipRepository.save(UserMunicipalityMembership.builder()
                .user(officer)
                .municipality(municipality)
                .validFrom(clock.instant())
                .build());
    }

    /**
     * The headline requirement, end to end: file a complaint, run the relay, and the
     * officer and the admin each hold an unread in-app row naming that complaint.
     */
    @Test
    void submittingAComplaintNotifiesOfficerAndAdmin() throws Exception {
        String referenceCode = submitComplaint("Broken streetlight on the new report path");

        drainOutbox();

        List<NotificationMessage> officerInbox =
                notificationRepository.findByUserIdOrderByIdDesc(officer.getId(), PageRequest.of(0, 20));
        List<NotificationMessage> adminInbox =
                notificationRepository.findByUserIdOrderByIdDesc(admin.getId(), PageRequest.of(0, 20));

        assertThat(officerInbox)
                .as("officer posted to the complaint's municipality must be alerted")
                .isNotEmpty();
        assertThat(adminInbox)
                .as("admin is cross-tenant and must be alerted without a membership")
                .isNotEmpty();

        NotificationMessage officerAlert = officerInbox.get(0);
        NotificationMessage adminAlert = adminInbox.get(0);

        assertThat(officerAlert.getTemplateCode())
                .isEqualTo(NotificationDispatcher.AUTHORITY_NEW_REPORT);
        assertThat(officerAlert.getChannel()).isEqualTo(NotificationChannel.IN_APP);
        assertThat(officerAlert.getMessage())
                .as("the alert must name the complaint so the desk can act on it")
                .contains(referenceCode);

        assertThat(adminAlert.getTemplateCode())
                .isEqualTo(NotificationDispatcher.AUTHORITY_NEW_REPORT);
        assertThat(adminAlert.getMessage()).contains(referenceCode);

        assertThat(officerAlert.isRead()).isFalse();
        assertThat(adminAlert.isRead()).isFalse();
    }

    /**
     * The citizen still hears about their own report, and the alert does not read as
     * though the recipient filed it — the reason AUTHORITY_NEW_REPORT exists
     * separately from COMPLAINT_SUBMITTED.
     */
    @Test
    void citizenIsStillNotifiedInTheirOwnWords() throws Exception {
        String referenceCode = submitComplaint("Citizen wording check");

        drainOutbox();

        NotificationMessage citizenAlert = newestFor(citizen);
        assertThat(citizenAlert.getTemplateCode()).isEqualTo("COMPLAINT_SUBMITTED");
        assertThat(citizenAlert.getMessage()).contains(referenceCode);
        assertThat(citizenAlert.getMessage())
                .doesNotContain("reported by a citizen")
                .as("the citizen-facing line must not be the authority-facing one");

        // Guards a real bug: the Bangla authority template was first written with
        // "your complaint" (আপনার) instead of "new complaint" (নতুন), which made the
        // officer's alert read as though the officer had filed it. The English key
        // differs in wording, so an English-only assertion would not have caught it.
        NotificationMessage officerAlert = newestFor(officer);
        assertThat(officerAlert.getTemplateCode())
                .isEqualTo(NotificationDispatcher.AUTHORITY_NEW_REPORT);
        assertThat(officerAlert.getMessage())
                .as("the Bangla authority alert must say 'new complaint', not 'your complaint'")
                .contains("নতুন")
                .doesNotContain("আপনার");
    }

    /**
     * Later transitions must not re-page the whole authority. Only SUBMIT fans out;
     * otherwise a VERIFY would notify every officer on every status change.
     */
    @Test
    void laterTransitionsDoNotRefanOutToTheWholeAuthority() throws Exception {
        String referenceCode = submitComplaint("Fan-out should stop after submit");
        drainOutbox();

        mockMvc.perform(post("/api/authority/complaints/{ref}/verify", referenceCode)
                        .header("Authorization", "Bearer " + login(officer))
                        .param("note", "site visit done"))
                .andExpect(status().isOk());

        drainOutbox();

        // The admin acted on nothing here, so a second alert would be noise. The
        // officer legitimately keeps their own copy of the verify.
        assertThat(notificationRepository.countByUserIdAndReadFalse(admin.getId()))
                .as("admin is alerted once, on submission")
                .isEqualTo(1);
    }

    /** Officers in another municipality must not be woken by an unrelated report. */
    @Test
    void officersOutsideTheMunicipalityAreNotNotified() throws Exception {
        long nano = System.nanoTime();
        User outsider = user("nf-outsider-" + nano + "@test.com", "0174" + pad(nano),
                "Outsider Officer", UserRole.DEPT_OFFICER);
        Municipality other = municipalityRepository.findAll().stream()
                .filter(m -> !m.getId().equals(municipality.getId()))
                .findFirst()
                .orElseThrow();
        membershipRepository.save(UserMunicipalityMembership.builder()
                .user(outsider)
                .municipality(other)
                .validFrom(clock.instant())
                .build());

        submitComplaint("Tenancy boundary check");
        drainOutbox();

        assertThat(notificationRepository.findByUserIdOrderByIdDesc(
                outsider.getId(), PageRequest.of(0, 20)))
                .as("an officer posted elsewhere has no standing to hear about this ward")
                .isEmpty();
    }

    /**
     * The read API: the officer's alert is listed, counted, marked read, and the
     * count drops. This is what makes the feature visible in the demo — without it
     * rows are written and nothing ever surfaces them.
     */
    @Test
    void officerCanReadAndClearNotificationsThroughTheApi() throws Exception {
        String referenceCode = submitComplaint("Read API check");
        drainOutbox();

        String token = login(officer);

        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].referenceCode").value(referenceCode))
                .andExpect(jsonPath("$[0].read").value(false))
                .andExpect(jsonPath("$[0].message").isNotEmpty());

        mockMvc.perform(get("/api/notifications/unread-count")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));

        long notificationId = newestFor(officer).getId();

        mockMvc.perform(post("/api/notifications/{id}/read", notificationId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true))
                .andExpect(jsonPath("$.readAt").isNotEmpty());

        mockMvc.perform(get("/api/notifications/unread-count")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    /**
     * Notifications are personal. Marking someone else's row read must 404 rather
     * than 403, and must not actually flip their flag.
     */
    @Test
    void oneUserCannotMarkAnotherUsersNotificationRead() throws Exception {
        submitComplaint("Ownership check");
        drainOutbox();

        long officerNotificationId = newestFor(officer).getId();

        mockMvc.perform(post("/api/notifications/{id}/read", officerNotificationId)
                        .header("Authorization", "Bearer " + login(admin)))
                .andExpect(status().isNotFound());

        assertThat(notificationRepository.findById(officerNotificationId).orElseThrow().isRead())
                .as("the officer's row must be untouched by the admin's request")
                .isFalse();
    }

    /** Anonymous callers get nothing — the bell and the API are both authenticated. */
    @Test
    void notificationsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/notifications/unread-count")).andExpect(status().isUnauthorized());
    }

    /**
     * A citizen who also holds staff rights must not be paged about their own report
     * — the resolver drops the filing citizen from the fan-out.
     */
    @Test
    void filingCitizenIsNotPagedAboutTheirOwnComplaint() {
        long nano = System.nanoTime();
        User staffCitizen = user("nf-staffcitizen-" + nano + "@test.com", "0175" + pad(nano),
                "Officer Reporting", UserRole.DEPT_OFFICER);
        membershipRepository.save(UserMunicipalityMembership.builder()
                .user(staffCitizen)
                .municipality(municipality)
                .validFrom(clock.instant())
                .build());

        Complaint complaint = Complaint.builder()
                .referenceCode("NS-TEST-000001")
                .municipality(municipality)
                .citizen(staffCitizen)
                .build();

        assertThat(recipientResolver.authorityRecipientsFor(complaint))
                .as("the reporter is excluded even though they hold the DEPT_OFFICER role")
                .doesNotContain(staffCitizen.getId());
    }

    // PLACEHOLDER_TESTS_3
    // ------------------------------------------------------------------ helpers

    /**
     * Runs every due outbox row to completion.
     *
     * <p>{@code processBatch} rather than {@code processOne} so a test never depends
     * on how many rows the submission happened to produce. Bounded loop rather than
     * "until zero": a bug that re-enqueues forever should fail the suite on time,
     * not hang it.
     */
    private void drainOutbox() {
        for (int attempt = 0; attempt < 5; attempt++) {
            if (outboxWorker.processBatch(100) == 0) {
                return;
            }
        }
    }

    /** Newest notification for a user, failing loudly when the inbox is empty. */
    private NotificationMessage newestFor(User user) {
        List<NotificationMessage> rows = notificationRepository
                .findByUserIdOrderByIdDesc(user.getId(), PageRequest.of(0, 1));
        assertThat(rows).as("expected at least one notification for %s", user.getEmail()).isNotEmpty();
        return rows.get(0);
    }

    private String submitComplaint(String title) throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/complaints")
                        .file(new MockMultipartFile("photos", "issue.png", "image/png", PNG))
                        .header("Authorization", "Bearer " + login(citizen))
                        .param("title", title)
                        .param("description", "A description long enough to be realistic.")
                        .param("category", "ROADS")
                        .param("latitude", LAT)
                        .param("longitude", LNG))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("referenceCode").asText();
    }

    private String login(User user) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"" + user.getEmail()
                                + "\",\"password\":\"password\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("accessToken").asText();
    }

    private User user(String email, String phone, String name, UserRole role) {
        return userRepository.saveAndFlush(User.builder()
                .fullName(name)
                .email(email)
                .phone(phone)
                .passwordHash(passwordEncoder.encode("password"))
                .role(role)
                .active(true)
                .build());
    }

    /** {@code 01XXXXXXXXX} tail from a seed, so every fixture row is unique. */
    private static String pad(long seed) {
        return String.format("%08d", Math.floorMod(seed, 100_000_000L));
    }
}