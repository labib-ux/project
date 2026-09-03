package com.nagorikseba;

import com.nagorikseba.complaint.domain.Attachment;
import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.domain.ComplaintTransition;
import com.nagorikseba.complaint.domain.enums.Category;
import com.nagorikseba.complaint.domain.enums.ComplaintAction;
import com.nagorikseba.complaint.domain.enums.ComplaintStatus;
import com.nagorikseba.complaint.domain.enums.LocationSource;
import com.nagorikseba.complaint.domain.enums.ModerationStatus;
import com.nagorikseba.complaint.domain.enums.Priority;
import com.nagorikseba.complaint.repo.AttachmentRepository;
import com.nagorikseba.complaint.repo.ComplaintRepository;
import com.nagorikseba.complaint.repo.ComplaintTransitionRepository;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.identity.repo.UserRepository;
import com.nagorikseba.municipality.entity.Municipality;
import com.nagorikseba.municipality.entity.Ward;
import com.nagorikseba.municipality.repository.MunicipalityRepository;
import com.nagorikseba.municipality.repository.WardRepository;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class RepositorySmokeTests {

    @Autowired
    private ComplaintRepository complaintRepository;

    @Autowired
    private ComplaintTransitionRepository transitionRepository;

    @Autowired
    private AttachmentRepository attachmentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MunicipalityRepository municipalityRepository;

    @Autowired
    private WardRepository wardRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private Clock clock;

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(null, 4326);

    @Test
    @Transactional
    void complaintRepositoryBasicCrud() {
        Municipality municipality = municipalityRepository.save(Municipality.builder()
                .slug("test-municipality")
                .name("Test Municipality")
                .isActive(true)
                .build());

        Ward ward = wardRepository.save(Ward.builder()
                .municipality(municipality)
                .wardNumber(1)
                .areaName("Test Ward")
                .boundary(createPolygon(23.7, 90.3, 23.8, 90.4))
                .isActive(true)
                .build());

        User citizen = userRepository.save(User.builder()
                .fullName("Test Citizen")
                .email("repo-test@test.com")
                .phone("01700000400")
                .passwordHash(passwordEncoder.encode("password"))
                .role(com.nagorikseba.enums.UserRole.CITIZEN)
                .active(true)
                .build());

        Point location = GEOMETRY_FACTORY.createPoint(new Coordinate(90.35, 23.75));
        location.setSRID(4326);

        // Create
        Complaint complaint = Complaint.builder()
                .referenceCode("NS-2026-000999")
                .municipality(municipality)
                .ward(ward)
                .citizen(citizen)
                .title("Test Complaint")
                .description("Test description")
                .category(Category.ROADS)
                .status(ComplaintStatus.SUBMITTED)
                .priority(Priority.NORMAL)
                .location(location)
                .locationSource(LocationSource.DEVICE)
                .moderationStatus(ModerationStatus.APPROVED)
                .submittedAt(clock.instant())
                .build();
        complaint = complaintRepository.saveAndFlush(complaint);

        assertThat(complaint.getId()).isNotNull();
        assertThat(complaint.getReferenceCode()).isEqualTo("NS-2026-000999");

        // Read by ID
        Complaint found = complaintRepository.findById(complaint.getId()).orElseThrow();
        assertThat(found.getTitle()).isEqualTo("Test Complaint");

        // Read by reference code
        Complaint byRef = complaintRepository.findByReferenceCode("NS-2026-000999").orElseThrow();
        assertThat(byRef.getId()).isEqualTo(complaint.getId());

        // Update
        complaint.setTitle("Updated Title");
        complaint = complaintRepository.saveAndFlush(complaint);
        assertThat(complaintRepository.findById(complaint.getId()).orElseThrow().getTitle())
                .isEqualTo("Updated Title");

        // Find by citizen
        List<Complaint> byCitizen = complaintRepository.findByCitizenIdOrderBySubmittedAtDesc(citizen.getId());
        assertThat(byCitizen).hasSize(1);

        // Find by municipality and status
        List<Complaint> byMuniStatus = complaintRepository.findByMunicipalityIdAndStatusIn(
                municipality.getId(), List.of(ComplaintStatus.SUBMITTED));
        assertThat(byMuniStatus).hasSize(1);

        // Delete
        complaintRepository.delete(complaint);
        assertThat(complaintRepository.findById(complaint.getId())).isEmpty();
    }

    @Test
    @Transactional
    void complaintTransitionRepositoryBasicCrud() {
        Municipality municipality = municipalityRepository.save(Municipality.builder()
                .slug("test-municipality-2")
                .name("Test Municipality 2")
                .isActive(true)
                .build());

        User citizen = userRepository.save(User.builder()
                .fullName("Transition Test Citizen")
                .email("transition-test@test.com")
                .phone("01700000500")
                .passwordHash(passwordEncoder.encode("password"))
                .role(com.nagorikseba.enums.UserRole.CITIZEN)
                .active(true)
                .build());

        Complaint complaint = Complaint.builder()
                .referenceCode("NS-2026-000998")
                .municipality(municipality)
                .citizen(citizen)
                .title("Transition Test")
                .description("Test")
                .category(Category.ROADS)
                .status(ComplaintStatus.SUBMITTED)
                .priority(Priority.NORMAL)
                .location(GEOMETRY_FACTORY.createPoint(new Coordinate(90.35, 23.75)))
                .locationSource(LocationSource.DEVICE)
                .moderationStatus(ModerationStatus.APPROVED)
                .submittedAt(clock.instant())
                .build();
        complaint = complaintRepository.saveAndFlush(complaint);

        // Create transition
        ComplaintTransition transition = ComplaintTransition.builder()
                .complaint(complaint)
                .fromStatus(null)
                .toStatus(ComplaintStatus.SUBMITTED)
                .action(ComplaintAction.SUBMIT)
                .actor(citizen)
                .actorRole("CITIZEN")
                .note("Complaint submitted")
                .idempotencyKey("test-key-1")
                .createdAt(clock.instant())
                .build();
        transition = transitionRepository.saveAndFlush(transition);

        assertThat(transition.getId()).isNotNull();
        assertThat(transition.getAction()).isEqualTo(ComplaintAction.SUBMIT);

        // Read by complaint
        List<ComplaintTransition> transitions = transitionRepository.findByComplaintIdOrderByCreatedAtAsc(complaint.getId());
        assertThat(transitions).hasSize(1);

        // Idempotency check
        assertThat(transitionRepository.existsByComplaintIdAndIdempotencyKey(complaint.getId(), "test-key-1")).isTrue();
        assertThat(transitionRepository.existsByComplaintIdAndIdempotencyKey(complaint.getId(), "other-key")).isFalse();
    }

    @Test
    @Transactional
    void attachmentRepositoryBasicCrud() {
        Municipality municipality = municipalityRepository.save(Municipality.builder()
                .slug("test-municipality-3")
                .name("Test Municipality 3")
                .isActive(true)
                .build());

        User citizen = userRepository.save(User.builder()
                .fullName("Attachment Test Citizen")
                .email("attachment-test@test.com")
                .phone("01700000600")
                .passwordHash(passwordEncoder.encode("password"))
                .role(com.nagorikseba.enums.UserRole.CITIZEN)
                .active(true)
                .build());

        Complaint complaint = Complaint.builder()
                .referenceCode("NS-2026-000997")
                .municipality(municipality)
                .citizen(citizen)
                .title("Attachment Test")
                .description("Test")
                .category(Category.ROADS)
                .status(ComplaintStatus.SUBMITTED)
                .priority(Priority.NORMAL)
                .location(GEOMETRY_FACTORY.createPoint(new Coordinate(90.35, 23.75)))
                .locationSource(LocationSource.DEVICE)
                .moderationStatus(ModerationStatus.APPROVED)
                .submittedAt(clock.instant())
                .build();
        complaint = complaintRepository.saveAndFlush(complaint);

        // Create attachment
        Attachment attachment = Attachment.builder()
                .complaint(complaint)
                .storageKey("complaints/2026/09/ns-2026-000997/test.jpg")
                .originalFilename("test.jpg")
                .contentType("image/jpeg")
                .byteSize(1024)
                .checksumSha256("abcdef1234567890")
                .workProof(false)
                .scanStatus("PENDING")
                .uploadedBy(citizen)
                .build();
        attachment = attachmentRepository.saveAndFlush(attachment);

        assertThat(attachment.getId()).isNotNull();

        // Read by complaint (non-deleted)
        List<Attachment> attachments = attachmentRepository.findByComplaintIdAndDeletedAtIsNullOrderByCreatedAtAsc(complaint.getId());
        assertThat(attachments).hasSize(1);

        // Soft delete
        attachment.softDelete(clock.instant());
        attachmentRepository.saveAndFlush(attachment);

        // Should not appear in non-deleted query
        List<Attachment> nonDeleted = attachmentRepository.findByComplaintIdAndDeletedAtIsNullOrderByCreatedAtAsc(complaint.getId());
        assertThat(nonDeleted).isEmpty();

        // But appears in all query
        List<Attachment> all = attachmentRepository.findByComplaintIdOrderByCreatedAtAsc(complaint.getId());
        assertThat(all).hasSize(1);
    }

    private org.locationtech.jts.geom.MultiPolygon createPolygon(double minLat, double minLon, double maxLat, double maxLon) {
        Coordinate[] coords = new Coordinate[]{
                new Coordinate(minLon, minLat),
                new Coordinate(maxLon, minLat),
                new Coordinate(maxLon, maxLat),
                new Coordinate(minLon, maxLat),
                new Coordinate(minLon, minLat)
        };
        org.locationtech.jts.geom.Polygon polygon = GEOMETRY_FACTORY.createPolygon(coords);
        return GEOMETRY_FACTORY.createMultiPolygon(new org.locationtech.jts.geom.Polygon[]{polygon});
    }
}