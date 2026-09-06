package com.nagorikseba;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.domain.ComplaintAssignment;
import com.nagorikseba.complaint.domain.ComplaintTransition;
import com.nagorikseba.complaint.domain.enums.Category;
import com.nagorikseba.complaint.domain.enums.ComplaintAction;
import com.nagorikseba.complaint.domain.enums.ComplaintStatus;
import com.nagorikseba.complaint.domain.enums.LocationSource;
import com.nagorikseba.complaint.domain.enums.ModerationStatus;
import com.nagorikseba.complaint.domain.enums.Priority;
import com.nagorikseba.complaint.repo.ComplaintAssignmentRepository;
import com.nagorikseba.complaint.repo.ComplaintRepository;
import com.nagorikseba.complaint.repo.ComplaintTransitionRepository;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.identity.repo.UserRepository;
import com.nagorikseba.municipality.entity.Municipality;
import com.nagorikseba.municipality.entity.Ward;
import com.nagorikseba.municipality.repository.MunicipalityRepository;
import com.nagorikseba.municipality.repository.WardRepository;
import com.nagorikseba.municipality.service.WardBoundaryService;
import com.nagorikseba.shared.outbox.OutboxMessage;
import com.nagorikseba.shared.outbox.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 4 spatial + routing proof (§3.1, §4, §7.2).
 *
 * <p>Each test builds its own municipality (unique slug) so it never depends on
 * — or disturbs — the seeder's wards. PostGIS behaviour is asserted against the
 * real Testcontainers database, never mocked.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class WardBoundaryIntegrationTests {

    private static final GeometryFactory GEOMETRY_FACTORY =
            new GeometryFactory(new PrecisionModel(), 4326);

    private static final byte[] PNG = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52};

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WardBoundaryService wardBoundaryService;

    @Autowired
    private WardRepository wardRepository;

    @Autowired
    private MunicipalityRepository municipalityRepository;

    @Autowired
    private ComplaintRepository complaintRepository;

    @Autowired
    private ComplaintAssignmentRepository assignmentRepository;

    @Autowired
    private ComplaintTransitionRepository transitionRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private Clock clock;

    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    void pointInsideWardReturnsCorrectWard() {
        Municipality municipality = newMunicipality();
        Ward west = newWard(municipality, 1, "West", 23.00, 90.00, 23.01, 90.01);
        newWard(municipality, 2, "East", 23.00, 90.01, 23.01, 90.02);

        Ward found = wardBoundaryService.resolvePoint(municipality.getId(), 23.005, 90.005);

        assertThat(found.getId()).isEqualTo(west.getId());
    }

    @Test
    void pointOutsideAllWardsThrows() {
        Municipality municipality = newMunicipality();
        newWard(municipality, 1, "West", 23.00, 90.00, 23.01, 90.01);

        assertThatThrownBy(() -> wardBoundaryService.resolvePoint(municipality.getId(), 24.50, 91.50))
                .isInstanceOf(WardBoundaryService.WardNotCoveredException.class);
    }

    @Test
    void pointOnSharedBoundaryResolvesToLowestWardId() {
        Municipality municipality = newMunicipality();
        Ward west = newWard(municipality, 1, "West", 23.00, 90.00, 23.01, 90.01);
        Ward east = newWard(municipality, 2, "East", 23.00, 90.01, 23.01, 90.02);
        assertThat(east.getId()).isGreaterThan(west.getId());

        // Exactly on the shared edge x=90.01: ST_Covers matches both, ORDER BY id wins.
        Ward found = wardBoundaryService.resolvePoint(municipality.getId(), 23.005, 90.01);

        assertThat(found.getId()).isEqualTo(west.getId());
    }

    @Test
    void overlappingBoundariesAreReportedAndTouchingOnesAreNot() {
        Municipality municipality = newMunicipality();
        Ward west = newWard(municipality, 1, "West", 23.00, 90.00, 23.01, 90.01);
        Ward east = newWard(municipality, 2, "East", 23.00, 90.01, 23.01, 90.02);

        // Merely touching at the shared edge is adjacency, not an overlap.
        assertThat(wardBoundaryService.validateBoundaryOverlap(municipality.getId())).isEmpty();

        Ward overlap = newWard(municipality, 3, "Overlap", 23.005, 90.005, 23.015, 90.015);
        List<WardBoundaryService.OverlapPair> overlaps =
                wardBoundaryService.validateBoundaryOverlap(municipality.getId());

        assertThat(overlaps).hasSize(2);
        assertThat(overlaps.stream()
                .flatMap(pair -> java.util.stream.Stream.of(pair.firstWardId(), pair.secondWardId()))
                .toList()).contains(west.getId(), east.getId(), overlap.getId());
    }

    @Test
    void dashboardAggregatesReturnCorrectCounts() {
        Municipality municipality = newMunicipality();
        Ward ward = newWard(municipality, 1, "Only", 23.00, 90.00, 23.01, 90.01);
        User citizen = newCitizen();
        Instant submitted = clock.instant().minus(10, ChronoUnit.HOURS);

        saveComplaint(municipality, ward, citizen, Category.ROADS, ComplaintStatus.SUBMITTED, submitted, null);
        saveComplaint(municipality, ward, citizen, Category.ROADS, ComplaintStatus.SUBMITTED, submitted, null);
        saveComplaint(municipality, ward, citizen, Category.ELECTRICITY, ComplaintStatus.RESOLVED,
                submitted, submitted.plus(5, ChronoUnit.HOURS));

        List<Object[]> rows =
                complaintRepository.countByMunicipalityGroupByWardAndStatus(municipality.getId());
        long submittedCount = rows.stream()
                .filter(row -> row[1] == ComplaintStatus.SUBMITTED)
                .mapToLong(row -> ((Number) row[2]).longValue()).sum();
        long resolvedCount = rows.stream()
                .filter(row -> row[1] == ComplaintStatus.RESOLVED)
                .mapToLong(row -> ((Number) row[2]).longValue()).sum();
        assertThat(submittedCount).isEqualTo(2);
        assertThat(resolvedCount).isEqualTo(1);

        Double average = complaintRepository.averageResolutionHoursByMunicipality(municipality.getId());
        assertThat(average).isNotNull();
        assertThat(average).isCloseTo(5.0, within(0.01));
    }

    /**
     * Full authority flow on seeded dhaka-north data: submit → verify →
     * queue contains it → auto-assign audits strategy → admin starts work.
     * Asserts the §7.2 auditability contract end to end.
     */
    @Test
    void autoAssignAuditsStrategyInAssignmentRowAndTransitionMetadata() throws Exception {
        String citizenToken = registerCitizen();
        String refCode = submitComplaint(citizenToken, "Phase 4 routing probe", Category.ROADS);

        String councilorToken = login("councilor17@example.com", "councilor123");
        mockMvc.perform(post("/api/authority/complaints/{ref}/verify", refCode)
                        .header("Authorization", "Bearer " + councilorToken)
                        .param("note", "Verified for routing"))
                .andExpect(status().isOk());

        String officerToken = login("officer1@demo", "demo1234");
        MvcResult queue = mockMvc.perform(get("/api/authority/queue")
                        .header("Authorization", "Bearer " + officerToken)
                        .param("municipalityId", dhakaNorthId())
                        .param("status", "VERIFIED"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(queue.getResponse().getContentAsString()).contains(refCode);

        mockMvc.perform(post("/api/authority/complaints/{ref}/assign/auto", refCode)
                        .header("Authorization", "Bearer " + officerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ASSIGNED"));

        Complaint complaint = complaintRepository.findByReferenceCode(refCode).orElseThrow();
        ComplaintAssignment assignment = assignmentRepository
                .findByComplaintIdAndUnassignedAtIsNull(complaint.getId()).orElseThrow();
        assertThat(assignment.getStrategyUsed()).isEqualTo("CATEGORY");
        assertThat(assignment.getStrategyExplanation()).contains("ROADS");
        assertThat(assignment.getDepartment()).isNotNull();

        ComplaintTransition assignTransition = transitionRepository
                .findByComplaintIdOrderByCreatedAtAsc(complaint.getId()).stream()
                .filter(transition -> transition.getAction() == ComplaintAction.ASSIGN)
                .findFirst().orElseThrow();
        assertThat(assignTransition.getMetadata()).contains("CATEGORY");
        assertThat(assignTransition.getMetadata()).contains("ROADS");

        List<OutboxMessage> events = outboxRepository
                .findByAggregateTypeAndAggregateIdOrderByIdAsc("COMPLAINT", complaint.getId());
        assertThat(events.stream().map(OutboxMessage::getEventType))
                .contains("COMPLAINT_ASSIGNED");

        String adminToken = login("admin@example.com", "admin123");
        mockMvc.perform(post("/api/authority/complaints/{ref}/start", refCode)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        mockMvc.perform(get("/api/authority/dashboard")
                        .header("Authorization", "Bearer " + councilorToken)
                        .param("municipalityId", dhakaNorthId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("WARD_COUNCILOR"))
                .andExpect(jsonPath("$.wardStats").isArray())
                .andExpect(jsonPath("$.slaAtRiskCount").isNumber());
    }

    // ------------------------------------------------------------------ helpers

    private Municipality newMunicipality() {
        return municipalityRepository.save(Municipality.builder()
                .slug("wb-test-" + System.nanoTime())
                .name("Ward Boundary Test Municipality")
                .isActive(true)
                .build());
    }

    private Ward newWard(Municipality municipality, int number, String name,
                         double minLat, double minLon, double maxLat, double maxLon) {
        return wardRepository.save(Ward.builder()
                .municipality(municipality)
                .wardNumber(number)
                .areaName(name)
                .boundary(rectangle(minLat, minLon, maxLat, maxLon))
                .isActive(true)
                .build());
    }

    private static MultiPolygon rectangle(double minLat, double minLon, double maxLat, double maxLon) {
        Coordinate[] coords = {
                new Coordinate(minLon, minLat),
                new Coordinate(maxLon, minLat),
                new Coordinate(maxLon, maxLat),
                new Coordinate(minLon, maxLat),
                new Coordinate(minLon, minLat)
        };
        return GEOMETRY_FACTORY.createMultiPolygon(
                new Polygon[]{GEOMETRY_FACTORY.createPolygon(coords)});
    }

    private User newCitizen() {
        long nano = System.nanoTime();
        return userRepository.save(User.builder()
                .fullName("Boundary Test Citizen")
                .email("wb-citizen-" + nano + "@test.com")
                .phone("0179000" + Math.floorMod(nano, 100000))
                .passwordHash(passwordEncoder.encode("password"))
                .role(com.nagorikseba.enums.UserRole.CITIZEN)
                .active(true)
                .build());
    }

    private Complaint saveComplaint(Municipality municipality, Ward ward, User citizen,
                                    Category category, ComplaintStatus status,
                                    Instant submittedAt, Instant resolvedAt) {
        Point location = GEOMETRY_FACTORY.createPoint(new Coordinate(90.005, 23.005));
        location.setSRID(4326);
        return complaintRepository.saveAndFlush(Complaint.builder()
                .referenceCode("WB-" + Math.floorMod(System.nanoTime(), 100000000))
                .municipality(municipality)
                .ward(ward)
                .citizen(citizen)
                .title("Boundary test complaint")
                .description("Boundary test description")
                .category(category)
                .status(status)
                .priority(Priority.NORMAL)
                .location(location)
                .locationSource(LocationSource.DEVICE)
                .moderationStatus(ModerationStatus.APPROVED)
                .submittedAt(submittedAt)
                .resolvedAt(resolvedAt)
                .build());
    }

    private String dhakaNorthId() {
        return municipalityRepository.findBySlug("dhaka-north").orElseThrow().getId().toString();
    }

    private String registerCitizen() throws Exception {
        long nano = System.nanoTime();
        String email = "wb-flow-" + nano + "@test.com";
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content("""
                                {"fullName":"Flow Citizen","email":"%s","phone":"017%08d","password":"password"}
                                """.formatted(email, Math.floorMod(nano, 100000000))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("accessToken").asText();
    }

    private String login(String identifier, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"identifier\": \"" + identifier + "\", \"password\": \"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("accessToken").asText();
    }

    private String submitComplaint(String token, String title, Category category) throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/complaints")
                        .file(new MockMultipartFile("photos", "issue.png", "image/png", PNG))
                        .header("Authorization", "Bearer " + token)
                        .param("title", title)
                        .param("description", "Phase 4 end-to-end description")
                        .param("category", category.name())
                        .param("latitude", "23.7925")
                        .param("longitude", "90.4120"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("referenceCode").asText();
    }
}
