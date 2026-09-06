package com.nagorikseba;

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
import com.nagorikseba.notification.NotificationDispatcher;
import com.nagorikseba.notification.NotificationMessageRepository;
import com.nagorikseba.notification.OutboxWorker;
import com.nagorikseba.shared.outbox.OutboxMessage;
import com.nagorikseba.shared.outbox.OutboxRepository;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Outbox relay proof (D14, §7.3, R5): delivery writes the in-app row and marks
 * SENT; failures back off and park FAILED after 5 attempts; redelivery
 * converges on the partial unique constraint instead of duplicating; two
 * workers claiming at once split the batch disjointly.
 *
 * <p>Timing is deterministic: rows under test carry far-future
 * {@code nextAttemptAt} values claimed with an explicit future instant, so the
 * background 10 s poller (real clock) never sees them.
 */
@SpringBootTest(properties = "app.scheduling.enabled=true")
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class OutboxDeliveryIntegrationTests {

    private static final GeometryFactory GEOMETRY_FACTORY =
            new GeometryFactory(new PrecisionModel(), 4326);

    @Autowired
    private OutboxWorker worker;

    @Autowired
    private NotificationDispatcher dispatcher;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private NotificationMessageRepository notificationRepository;

    @Autowired
    private ComplaintRepository complaintRepository;

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
    void eventRowDeliversAndWritesInAppNotification() {
        Fixture fixture = newFixture();
        OutboxMessage row = saveRow("COMPLAINT", fixture.complaintId(),
                "COMPLAINT_SUBMITTED", payload(fixture.complaintId(), "NS-0", "SUBMITTED", null));

        worker.processOne(row.getId());

        OutboxMessage delivered = outboxRepository.findById(row.getId()).orElseThrow();
        assertThat(delivered.getStatus()).isEqualTo("SENT");
        assertThat(notificationRepository.existsByOutboxIdAndUserId(
                row.getId(), fixture.citizenId())).isTrue();
        assertThat(dispatcher.channels()).contains("SMS", "EMAIL");
    }

    @Test
    void senderFailsTwiceThenSucceeds() {
        Fixture fixture = newFixture();
        // Unknown complaint id fails dispatch (JSON stays valid — the payload
        // column is JSON-typed); the row is repaired before the 3rd attempt.
        OutboxMessage row = saveRow("COMPLAINT", 999999999L,
                "COMPLAINT_SUBMITTED", payload(999999999L, "NS-0", "SUBMITTED", null));

        worker.processOne(row.getId());
        worker.processOne(row.getId());
        OutboxMessage retrying = outboxRepository.findById(row.getId()).orElseThrow();
        assertThat(retrying.getRetryCount()).isEqualTo(2);
        assertThat(retrying.getNextAttemptAt()).isAfter(clock.instant());

        retrying.setPayload(payload(fixture.complaintId(), "NS-0", "SUBMITTED", null));
        retrying.setStatus(OutboxMessage.STATUS_PENDING);
        outboxRepository.saveAndFlush(retrying);

        worker.processOne(row.getId());
        assertThat(outboxRepository.findById(row.getId()).orElseThrow().getStatus())
                .isEqualTo("SENT");
    }

    @Test
    void fiveFailuresParkTheRowFailed() {
        Fixture fixture = newFixture();
        OutboxMessage row = saveRow("COMPLAINT", 999999998L,
                "COMPLAINT_SUBMITTED", payload(999999998L, "NS-1", "SUBMITTED", null));
        row.setRetryCount(4);
        outboxRepository.saveAndFlush(row);

        worker.processOne(row.getId());

        OutboxMessage failed = outboxRepository.findById(row.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(OutboxMessage.STATUS_FAILED);
        assertThat(failed.getRetryCount()).isEqualTo(5);
    }

    @Test
    void duplicateDispatchDoesNotDuplicateInAppRows() {
        Fixture fixture = newFixture();
        OutboxMessage row = saveRow("COMPLAINT", fixture.complaintId(),
                "COMPLAINT_SUBMITTED", payload(fixture.complaintId(), "NS-0", "SUBMITTED", null));

        worker.processOne(row.getId());
        // Simulate a redelivery of the same row (crash between send and SENT).
        row.setStatus(OutboxMessage.STATUS_PENDING);
        outboxRepository.saveAndFlush(row);
        worker.processOne(row.getId());

        long rows = notificationRepository.findAll().stream()
                .filter(notification -> notification.getOutbox() != null
                        && notification.getOutbox().getId().equals(row.getId()))
                .count();
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void concurrentWorkersClaimDisjointSets() throws Exception {
        Instant future = clock.instant().plusSeconds(3600);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            ids.add(saveRow("COMPLAINT", (long) i, "COMPLAINT_SUBMITTED",
                    payload((long) i, "NS-" + i, "SUBMITTED", null)).getId());
        }

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Set<Long>> claimed = List.of(new HashSet<>(), new HashSet<>());
        for (int i = 0; i < 2; i++) {
            int index = i;
            executor.submit(() -> {
                try {
                    start.await();
                    // Through the worker's transactional claim, like the relay does.
                    worker.claim(4, future).stream()
                            .map(OutboxMessage::getId).forEach(claimed.get(index)::add);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        Set<Long> union = new HashSet<>(claimed.get(0));
        union.addAll(claimed.get(1));
        assertThat(union).containsExactlyInAnyOrderElementsOf(ids);
        assertThat(claimed.get(0)).doesNotContainAnyElementsOf(claimed.get(1));
    }

    // ------------------------------------------------------------------ helpers

    private record Fixture(Long complaintId, Long citizenId) {
    }

    private Fixture newFixture() {
        long nano = System.nanoTime();
        Municipality municipality = municipalityRepository.save(Municipality.builder()
                .slug("ob-test-" + nano)
                .name("Outbox Test Municipality")
                .isActive(true)
                .build());
        Ward ward = wardRepository.save(Ward.builder()
                .municipality(municipality)
                .wardNumber(1)
                .areaName("Outbox Ward")
                .boundary(rectangle(23.00, 90.00, 23.01, 90.01))
                .isActive(true)
                .build());
        User citizen = userRepository.save(User.builder()
                .fullName("Outbox Citizen")
                .email("ob-citizen-" + nano + "@test.com")
                .phone("0177000" + Math.floorMod(nano, 100000))
                .passwordHash(passwordEncoder.encode("password"))
                .role(com.nagorikseba.enums.UserRole.CITIZEN)
                .active(true)
                .build());
        Point location = GEOMETRY_FACTORY.createPoint(new Coordinate(90.005, 23.005));
        location.setSRID(4326);
        Complaint complaint = complaintRepository.saveAndFlush(Complaint.builder()
                .referenceCode("OB-" + Math.floorMod(nano, 100000000))
                .municipality(municipality)
                .ward(ward)
                .citizen(citizen)
                .title("Outbox test complaint")
                .description("Outbox test description")
                .category(Category.ROADS)
                .status(ComplaintStatus.SUBMITTED)
                .priority(Priority.NORMAL)
                .location(location)
                .locationSource(LocationSource.DEVICE)
                .moderationStatus(ModerationStatus.APPROVED)
                .submittedAt(clock.instant())
                .build());
        return new Fixture(complaint.getId(), citizen.getId());
    }

    private OutboxMessage saveRow(String aggregateType, Long aggregateId,
                                  String eventType, String payload) {
        return outboxRepository.saveAndFlush(OutboxMessage.builder()
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .eventType(eventType)
                .payload(payload)
                .status(OutboxMessage.STATUS_PENDING)
                .retryCount(0)
                .nextAttemptAt(clock.instant().plusSeconds(3600))
                .build());
    }

    private static String payload(long complaintId, String referenceCode, String to, String note) {
        return "{\"complaintId\":" + complaintId
                + ",\"referenceCode\":\"" + referenceCode + "\""
                + ",\"action\":\"SUBMIT\",\"from\":null,\"to\":\"" + to + "\""
                + ",\"actorId\":null"
                + (note == null ? ",\"note\":null" : ",\"note\":\"" + note + "\"") + "}";
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
