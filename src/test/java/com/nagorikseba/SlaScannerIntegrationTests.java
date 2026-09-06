package com.nagorikseba;

import com.nagorikseba.complaint.domain.Attachment;
import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.domain.ResolutionAttempt;
import com.nagorikseba.complaint.domain.enums.Category;
import com.nagorikseba.complaint.domain.enums.ComplaintAction;
import com.nagorikseba.complaint.domain.enums.ComplaintStatus;
import com.nagorikseba.complaint.domain.enums.LocationSource;
import com.nagorikseba.complaint.domain.enums.ModerationStatus;
import com.nagorikseba.complaint.domain.enums.Priority;
import com.nagorikseba.complaint.lifecycle.ComplaintLifecycleService;
import com.nagorikseba.complaint.lifecycle.TransitionCommand;
import com.nagorikseba.complaint.repo.AttachmentRepository;
import com.nagorikseba.complaint.repo.ComplaintRepository;
import com.nagorikseba.complaint.repo.ResolutionAttemptRepository;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.identity.repo.UserRepository;
import com.nagorikseba.municipality.entity.Municipality;
import com.nagorikseba.municipality.entity.Ward;
import com.nagorikseba.municipality.repository.MunicipalityRepository;
import com.nagorikseba.municipality.repository.WardRepository;
import com.nagorikseba.shared.outbox.OutboxMessage;
import com.nagorikseba.shared.outbox.OutboxRepository;
import com.nagorikseba.sla.SlaBreach;
import com.nagorikseba.sla.SlaBreachRepository;
import com.nagorikseba.sla.SlaBreachScanner;
import com.nagorikseba.sla.SlaInstance;
import com.nagorikseba.sla.SlaPolicy;
import com.nagorikseba.sla.SlaPolicyRepository;
import com.nagorikseba.sla.SlaService;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SLA scanner proof (D13, §3.4, R4): breach-once, escalation, SKIP LOCKED
 * concurrency and breach clearing on resolve — all against Testcontainers
 * PostGIS with test-owned municipalities.
 */
@SpringBootTest(properties = "app.scheduling.enabled=true")
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class SlaScannerIntegrationTests {

    private static final GeometryFactory GEOMETRY_FACTORY =
            new GeometryFactory(new PrecisionModel(), 4326);

    @Autowired
    private SlaBreachScanner scanner;

    @Autowired
    private SlaService slaService;

    @Autowired
    private ComplaintLifecycleService lifecycleService;

    @Autowired
    private SlaPolicyRepository policyRepository;

    @Autowired
    private SlaBreachRepository breachRepository;

    @Autowired
    private ComplaintRepository complaintRepository;

    @Autowired
    private AttachmentRepository attachmentRepository;

    @Autowired
    private ResolutionAttemptRepository attemptRepository;

    @Autowired
    private OutboxRepository outboxRepository;

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
    void breachDetectedExactlyOnce() {
        Fixture fixture = overdueFixture(1, 1000);
        assertThat(scanner.scanOnce()).isEqualTo(1);
        assertThat(scanner.scanOnce()).isEqualTo(0);

        List<SlaBreach> breaches = breachRepository.findByComplaintIdOrderByDetectedAtAsc(
                fixture.complaint().getId());
        assertThat(breaches).hasSize(1);
        assertThat(breachRepository.findByComplaintIdAndResolvedAtIsNull(
                fixture.complaint().getId())).isPresent();
    }

    @Test
    void escalationLevel1FiresWithOutboxRow() {
        Fixture fixture = overdueFixture(1, 1000);

        scanner.scanOnce();

        SlaBreach breach = breachRepository.findByComplaintIdAndResolvedAtIsNull(
                fixture.complaint().getId()).orElseThrow();
        assertThat(breach.getEscalationLevel()).isEqualTo(1);
        assertThat(breach.getHoursOverdue().doubleValue()).isPositive();
        assertThat(outboxRepository.findByAggregateTypeAndAggregateIdOrderByIdAsc(
                "COMPLAINT", fixture.complaint().getId()).stream()
                .map(OutboxMessage::getEventType)).contains("SLA_ESCALATION");
    }

    @Test
    void concurrentScansDoNotDoubleClaim() throws Exception {
        Fixture fixture = overdueFixture(1, 1000);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger total = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    total.addAndGet(scanner.scanOnce());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        assertThat(total.get()).isEqualTo(1);
        assertThat(breachRepository.findByComplaintIdOrderByDetectedAtAsc(
                fixture.complaint().getId())).hasSize(1);
    }

    @Test
    void breachClearedWhenComplaintResolves() {
        Fixture fixture = overdueFixture(1, 1000);
        scanner.scanOnce();
        assertThat(breachRepository.findByComplaintIdAndResolvedAtIsNull(
                fixture.complaint().getId())).isPresent();

        Attachment proof = attachmentRepository.saveAndFlush(Attachment.builder()
                .complaint(fixture.complaint())
                .storageKey("sla-test/" + System.nanoTime() + ".jpg")
                .contentType("image/jpeg")
                .byteSize(512)
                .checksumSha256("abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890")
                .uploadedBy(fixture.officer())
                .build());
        Complaint fresh = complaintRepository.findById(fixture.complaint().getId()).orElseThrow();

        lifecycleService.execute(TransitionCommand.ofRated(
                ComplaintAction.RESOLVE, fresh.getId(), fixture.officer().getId(),
                "Fixed", List.of(proof.getId()), null, null, null, fresh.getVersion()));

        assertThat(complaintRepository.findById(fixture.complaint().getId()).orElseThrow()
                .getStatus()).isEqualTo(ComplaintStatus.RESOLVED);
        assertThat(breachRepository.findByComplaintIdAndResolvedAtIsNull(
                fixture.complaint().getId())).isEmpty();
        assertThat(attemptRepository.findFirstByComplaintIdOrderByAttemptNumberDesc(
                fixture.complaint().getId()).orElseThrow().getOutcome())
                .isEqualTo(ResolutionAttempt.Outcome.PENDING_CITIZEN);
    }

    // ------------------------------------------------------------------ fixture

    private record Fixture(Complaint complaint, User officer) {
    }

    /** Active overdue complaint with a 1-hour policy and a posted officer. */
    private Fixture overdueFixture(int policyHours, int level2Hours) {
        long nano = System.nanoTime();
        Municipality municipality = municipalityRepository.save(Municipality.builder()
                .slug("sla-test-" + nano)
                .name("SLA Test Municipality")
                .isActive(true)
                .build());
        Ward ward = wardRepository.save(Ward.builder()
                .municipality(municipality)
                .wardNumber(1)
                .areaName("SLA Ward")
                .boundary(rectangle(23.00, 90.00, 23.01, 90.01))
                .isActive(true)
                .build());
        policyRepository.save(SlaPolicy.builder()
                .municipality(municipality)
                .category(Category.ROADS)
                .priority(Priority.NORMAL)
                .maxHours(policyHours)
                .escalationLevel1Hours(1)
                .escalationLevel2Hours(level2Hours)
                .active(true)
                .build());
        User citizen = userRepository.save(User.builder()
                .fullName("SLA Citizen")
                .email("sla-citizen-" + nano + "@test.com")
                .phone("0178000" + Math.floorMod(nano, 100000))
                .passwordHash(passwordEncoder.encode("password"))
                .role(com.nagorikseba.enums.UserRole.CITIZEN)
                .active(true)
                .build());
        User officer = userRepository.save(User.builder()
                .fullName("SLA Officer")
                .email("sla-officer-" + nano + "@test.com")
                .phone("0178001" + Math.floorMod(nano, 100000))
                .passwordHash(passwordEncoder.encode("password"))
                .role(com.nagorikseba.enums.UserRole.DEPT_OFFICER)
                .active(true)
                .build());

        Instant submitted = clock.instant().minus(5, ChronoUnit.HOURS);
        Point location = GEOMETRY_FACTORY.createPoint(new Coordinate(90.005, 23.005));
        location.setSRID(4326);
        // Built IN_PROGRESS (builder-initialized, like the seeder) so the
        // resolve test can act without replaying the whole authority flow.
        Complaint complaint = complaintRepository.saveAndFlush(Complaint.builder()
                .referenceCode("SLA-" + Math.floorMod(nano, 100000000))
                .municipality(municipality)
                .ward(ward)
                .citizen(citizen)
                .title("SLA test complaint")
                .description("SLA test description")
                .category(Category.ROADS)
                .status(ComplaintStatus.IN_PROGRESS)
                .priority(Priority.NORMAL)
                .location(location)
                .locationSource(LocationSource.DEVICE)
                .moderationStatus(ModerationStatus.APPROVED)
                .assignedOfficer(officer)
                .submittedAt(submitted)
                .build());
        SlaInstance instance = slaService.ensureInstance(complaint);
        assertThat(instance.getDeadlineAt()).isBefore(clock.instant());
        return new Fixture(complaint, officer);
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
