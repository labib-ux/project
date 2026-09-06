package com.nagorikseba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.domain.enums.Category;
import com.nagorikseba.complaint.domain.enums.ComplaintStatus;
import com.nagorikseba.complaint.domain.enums.LocationSource;
import com.nagorikseba.complaint.domain.enums.ModerationStatus;
import com.nagorikseba.complaint.domain.enums.Priority;
import com.nagorikseba.complaint.repo.ComplaintRepository;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.identity.repo.UserRepository;
import com.nagorikseba.municipality.entity.Municipality;
import com.nagorikseba.municipality.entity.Ward;
import com.nagorikseba.municipality.repository.MunicipalityRepository;
import com.nagorikseba.municipality.repository.WardRepository;
import com.nagorikseba.transparency.PerformanceSnapshotJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Public transparency proof (D15, §9): heatmap responses carry no citizen
 * fields, coordinates snap to ~100 m, rejected/hidden reports are excluded,
 * dense areas cluster, and the scoreboard exposes ward aggregates only.
 */
@SpringBootTest(properties = "app.scheduling.enabled=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class HeatmapPrivacyIntegrationTests {

    private static final GeometryFactory GEOMETRY_FACTORY =
            new GeometryFactory(new PrecisionModel(), 4326);

    /** Exact pin, deliberately off the 0.001° grid. */
    private static final double LAT = 23.00567;
    private static final double LNG = 90.00532;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ComplaintRepository complaintRepository;

    @Autowired
    private MunicipalityRepository municipalityRepository;

    @Autowired
    private WardRepository wardRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PerformanceSnapshotJob snapshotJob;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private Clock clock;

    private Municipality municipality;
    private Ward ward;
    private User citizen;
    private String publicRef;
    private String rejectedRef;
    private String pendingRef;
    private String hiddenRef;

    @BeforeEach
    void setup() {
        long nano = System.nanoTime();
        municipality = municipalityRepository.save(Municipality.builder()
                .slug("hp-test-" + nano)
                .name("Heatmap Privacy Municipality")
                .isActive(true)
                .build());
        ward = wardRepository.save(Ward.builder()
                .municipality(municipality)
                .wardNumber(1)
                .areaName("Privacy Ward")
                .boundary(rectangle(23.00, 90.00, 23.02, 90.02))
                .isActive(true)
                .build());
        citizen = userRepository.save(User.builder()
                .fullName("Privacy Citizen")
                .email("hp-citizen-" + nano + "@test.com")
                .phone("0176000" + Math.floorMod(nano, 100000))
                .passwordHash(passwordEncoder.encode("password"))
                .role(com.nagorikseba.enums.UserRole.CITIZEN)
                .active(true)
                .build());

        publicRef = save(municipality, ward, citizen, LAT, LNG,
                ComplaintStatus.SUBMITTED, ModerationStatus.APPROVED, true).getReferenceCode();
        rejectedRef = save(municipality, ward, citizen, 23.006, 90.006,
                ComplaintStatus.REJECTED, ModerationStatus.REJECTED, false).getReferenceCode();
        pendingRef = save(municipality, ward, citizen, 23.007, 90.007,
                ComplaintStatus.SUBMITTED, ModerationStatus.PENDING, true).getReferenceCode();
        hiddenRef = save(municipality, ward, citizen, 23.008, 90.008,
                ComplaintStatus.SUBMITTED, ModerationStatus.APPROVED, false).getReferenceCode();
    }

    @Test
    void publicResponseContainsNoCitizenFields() throws Exception {
        String body = heatmapBody();

        assertThat(body).doesNotContain("citizenName", "citizenPhone", "Privacy Citizen",
                "0176000", "@test.com", "addressText", "anonymousContactPhone");
        JsonNode root = objectMapper.readTree(body);
        assertThat(root.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("clustered", "points");
        assertThat(root.path("clustered").asBoolean()).isFalse();
        Set<String> pointKeys = new HashSet<>();
        root.path("points").forEach(point -> point.fieldNames().forEachRemaining(pointKeys::add));
        assertThat(pointKeys).containsExactlyInAnyOrder(
                "referenceCode", "category", "status", "lng", "lat");
    }

    @Test
    void coordinatesAreObfuscatedWithin150m() throws Exception {
        JsonNode root = objectMapper.readTree(heatmapBody());

        JsonNode point = null;
        for (JsonNode candidate : root.path("points")) {
            if (candidate.path("referenceCode").asText().equals(publicRef)) {
                point = candidate;
            }
        }
        assertThat(point).isNotNull();
        double lng = point.path("lng").asDouble();
        double lat = point.path("lat").asDouble();
        // Snapped to the 0.001° grid: differs from the exact pin, within ~150 m.
        assertThat(Math.abs(lng - LNG)).isGreaterThan(0.0);
        assertThat(Math.abs(lat - LAT)).isGreaterThan(0.0);
        assertThat(Math.abs(lng - LNG)).isLessThanOrEqualTo(0.002);
        assertThat(Math.abs(lat - LAT)).isLessThanOrEqualTo(0.002);
    }

    @Test
    void rejectedPendingAndHiddenComplaintsAreExcluded() throws Exception {
        String body = heatmapBody();

        assertThat(body).contains(publicRef);
        assertThat(body).doesNotContain(rejectedRef, pendingRef, hiddenRef);
    }

    @Test
    void denseAreasReturnClusters() throws Exception {
        for (int i = 0; i < 501; i++) {
            double lat = 23.001 + (i % 100) * 0.0001;
            double lng = 90.001 + (i / 100) * 0.0001;
            save(municipality, ward, citizen, lat, lng,
                    ComplaintStatus.SUBMITTED, ModerationStatus.APPROVED, true);
        }

        JsonNode root = objectMapper.readTree(heatmapBody());

        assertThat(root.path("clustered").asBoolean()).isTrue();
        assertThat(root.path("points").size()).isPositive();
        Set<String> cellKeys = new HashSet<>();
        root.path("points").forEach(cell -> cell.fieldNames().forEachRemaining(cellKeys::add));
        assertThat(cellKeys).containsExactlyInAnyOrder("lng", "lat", "category", "count");
    }

    @Test
    void scoreboardExposesWardAggregatesOnly() throws Exception {
        snapshotJob.snapshotCurrentMonth();
        String period = YearMonth.now(clock.withZone(ZoneOffset.UTC)).toString();
        LocalDate expectedMonth = LocalDate.now(clock.withZone(ZoneOffset.UTC)).withDayOfMonth(1);
        assertThat(expectedMonth).isNotNull();

        MvcResult result = mockMvc.perform(get("/api/public/wards/scoreboard")
                        .param("municipality", municipality.getSlug())
                        .param("period", period))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode rows = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(rows.size()).isPositive();

        Set<String> keys = new HashSet<>();
        rows.forEach(row -> row.fieldNames().forEachRemaining(keys::add));
        assertThat(keys).containsExactlyInAnyOrder("wardId", "wardNumber", "areaName",
                "totalComplaints", "resolvedComplaints", "resolutionRate",
                "averageResolutionHours", "slaBreachCount");
        JsonNode wardRow = rows.get(0);
        assertThat(wardRow.path("totalComplaints").asInt()).isPositive();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("citizenName", "citizenPhone", "@test.com");
    }

    // ------------------------------------------------------------------ helpers

    private String heatmapBody() throws Exception {
        return mockMvc.perform(get("/api/public/heatmap")
                        .param("municipality", municipality.getSlug())
                        .param("minLng", "90.00")
                        .param("minLat", "23.00")
                        .param("maxLng", "90.02")
                        .param("maxLat", "23.02"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private Complaint save(Municipality municipality, Ward ward, User citizen,
                           double lat, double lng, ComplaintStatus status,
                           ModerationStatus moderation, boolean visible) {
        Point location = GEOMETRY_FACTORY.createPoint(new Coordinate(lng, lat));
        location.setSRID(4326);
        return complaintRepository.saveAndFlush(Complaint.builder()
                .referenceCode("HP-" + Math.floorMod(System.nanoTime(), 100000000))
                .municipality(municipality)
                .ward(ward)
                .citizen(citizen)
                .title("Privacy test complaint")
                .description("Privacy test description")
                .category(Category.ROADS)
                .status(status)
                .priority(Priority.NORMAL)
                .location(location)
                .locationSource(LocationSource.DEVICE)
                .moderationStatus(moderation)
                .publicVisible(visible)
                .submittedAt(clock.instant())
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
}
