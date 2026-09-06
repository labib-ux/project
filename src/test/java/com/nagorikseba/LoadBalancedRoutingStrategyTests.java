package com.nagorikseba;

import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.domain.ComplaintAssignment;
import com.nagorikseba.complaint.domain.enums.Category;
import com.nagorikseba.complaint.domain.enums.ComplaintStatus;
import com.nagorikseba.complaint.domain.enums.LocationSource;
import com.nagorikseba.complaint.domain.enums.ModerationStatus;
import com.nagorikseba.complaint.domain.enums.Priority;
import com.nagorikseba.complaint.repo.ComplaintAssignmentRepository;
import com.nagorikseba.complaint.repo.ComplaintRepository;
import com.nagorikseba.complaint.routing.LoadBalancedRoutingStrategy;
import com.nagorikseba.complaint.routing.RoutingDecision;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.identity.domain.UserMunicipalityMembership;
import com.nagorikseba.identity.repo.MembershipRepository;
import com.nagorikseba.identity.repo.UserRepository;
import com.nagorikseba.municipality.entity.Department;
import com.nagorikseba.municipality.entity.Municipality;
import com.nagorikseba.municipality.entity.Ward;
import com.nagorikseba.municipality.repository.DepartmentRepository;
import com.nagorikseba.municipality.repository.MunicipalityRepository;
import com.nagorikseba.municipality.repository.WardRepository;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Load-balancing proof (§7.2): active-assignment counts decide, and equal
 * loads always resolve to the lowest officer id — against Testcontainers
 * PostGIS, never mocks.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class LoadBalancedRoutingStrategyTests {

    private static final GeometryFactory GEOMETRY_FACTORY =
            new GeometryFactory(new PrecisionModel(), 4326);

    @Autowired
    private LoadBalancedRoutingStrategy strategy;

    @Autowired
    private ComplaintAssignmentRepository assignmentRepository;

    @Autowired
    private ComplaintRepository complaintRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private MembershipRepository membershipRepository;

    @Autowired
    private MunicipalityRepository municipalityRepository;

    @Autowired
    private WardRepository wardRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private Clock clock;

    @Test
    void equalLoadPicksLowerOfficerId() {
        Fixture fixture = twoOfficersNoLoad();

        RoutingDecision decision = strategy.route(fixture.complaint());

        assertThat(decision.strategy()).isEqualTo(LoadBalancedRoutingStrategy.TYPE);
        assertThat(decision.officer().getId())
                .isEqualTo(Math.min(fixture.officerA().getId(), fixture.officerB().getId()));
        assertThat(decision.explanation()).contains("least-loaded");
    }

    @Test
    void heavierOfficerLosesToLighterOne() {
        Fixture fixture = twoOfficersNoLoad();
        User lighter = fixture.officerA().getId() < fixture.officerB().getId()
                ? fixture.officerB() : fixture.officerA();
        User heavier = lighter.getId().equals(fixture.officerA().getId())
                ? fixture.officerB() : fixture.officerA();

        Complaint load = newComplaint(fixture.municipality(), fixture.ward(), fixture.citizen());
        assignmentRepository.saveAndFlush(ComplaintAssignment.builder()
                .complaint(load)
                .department(fixture.department())
                .officer(heavier)
                .strategyUsed("MANUAL")
                .strategyExplanation("load fixture")
                .build());

        RoutingDecision decision = strategy.route(fixture.complaint());

        assertThat(decision.officer().getId()).isEqualTo(lighter.getId());
    }

    // ------------------------------------------------------------------ fixture

    private record Fixture(Municipality municipality, Ward ward, User citizen,
                           Department department, User officerA, User officerB,
                           Complaint complaint) {
    }

    /** Municipality with one department and two posted officers, zero load. */
    private Fixture twoOfficersNoLoad() {
        long nano = System.nanoTime();
        Municipality municipality = municipalityRepository.save(Municipality.builder()
                .slug("lb-test-" + nano)
                .name("Load Balance Municipality")
                .isActive(true)
                .build());
        Ward ward = wardRepository.save(Ward.builder()
                .municipality(municipality)
                .wardNumber(1)
                .areaName("Load Ward")
                .boundary(rectangle(23.00, 90.00, 23.01, 90.01))
                .isActive(true)
                .build());
        Department department = departmentRepository.save(Department.builder()
                .municipality(municipality)
                .code("ROADS")
                .name("Roads")
                .handlesCategories(new String[]{"ROADS"})
                .isActive(true)
                .build());
        User citizen = userRepository.save(User.builder()
                .fullName("Load Citizen")
                .email("lb-citizen-" + nano + "@test.com")
                .phone("0175000" + Math.floorMod(nano, 100000))
                .passwordHash(passwordEncoder.encode("password"))
                .role(com.nagorikseba.enums.UserRole.CITIZEN)
                .active(true)
                .build());
        User officerA = newOfficer(municipality, ward, department, nano, 1);
        User officerB = newOfficer(municipality, ward, department, nano, 2);
        Complaint complaint = newComplaint(municipality, ward, citizen);
        return new Fixture(municipality, ward, citizen, department, officerA, officerB, complaint);
    }

    private User newOfficer(Municipality municipality, Ward ward, Department department,
                            long nano, int index) {
        User officer = userRepository.save(User.builder()
                .fullName("Load Officer " + index)
                .email("lb-officer-" + nano + "-" + index + "@test.com")
                .phone("0175001" + Math.floorMod(nano + index, 100000))
                .passwordHash(passwordEncoder.encode("password"))
                .role(com.nagorikseba.enums.UserRole.DEPT_OFFICER)
                .ward(ward)
                .department(department)
                .active(true)
                .build());
        membershipRepository.save(UserMunicipalityMembership.builder()
                .user(officer)
                .municipality(municipality)
                .ward(ward)
                .department(department)
                .validFrom(clock.instant())
                .build());
        return officer;
    }

    private Complaint newComplaint(Municipality municipality, Ward ward, User citizen) {
        Point location = GEOMETRY_FACTORY.createPoint(new Coordinate(90.005, 23.005));
        location.setSRID(4326);
        return complaintRepository.saveAndFlush(Complaint.builder()
                .referenceCode("LB-" + Math.floorMod(System.nanoTime(), 100000000))
                .municipality(municipality)
                .ward(ward)
                .citizen(citizen)
                .title("Load test complaint")
                .description("Load test description")
                .category(Category.ROADS)
                .status(ComplaintStatus.VERIFIED)
                .priority(Priority.NORMAL)
                .location(location)
                .locationSource(LocationSource.DEVICE)
                .moderationStatus(ModerationStatus.APPROVED)
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
