package com.nagorikseba.config;

import com.nagorikseba.complaint.domain.Attachment;
import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.domain.ComplaintTransition;
import com.nagorikseba.complaint.domain.enums.Category;
import com.nagorikseba.complaint.domain.enums.ComplaintAction;
import com.nagorikseba.complaint.domain.enums.ComplaintStatus;
import com.nagorikseba.complaint.domain.enums.LocationSource;
import com.nagorikseba.complaint.domain.enums.ModerationStatus;
import com.nagorikseba.complaint.domain.enums.Priority;
import com.nagorikseba.complaint.repo.ComplaintRepository;
import com.nagorikseba.complaint.repo.ComplaintTransitionRepository;
import com.nagorikseba.complaint.repo.AttachmentRepository;
import com.nagorikseba.entity.SlaRule;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.enums.UserRole;
import com.nagorikseba.municipality.entity.Department;
import com.nagorikseba.municipality.entity.Municipality;
import com.nagorikseba.municipality.entity.Ward;
import com.nagorikseba.municipality.repository.DepartmentRepository;
import com.nagorikseba.municipality.repository.MunicipalityRepository;
import com.nagorikseba.municipality.repository.WardRepository;
import com.nagorikseba.repository.SlaRuleRepository;
import com.nagorikseba.identity.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements CommandLineRunner {

    private final WardRepository wardRepository;
    private final MunicipalityRepository municipalityRepository;
    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final SlaRuleRepository slaRuleRepository;
    private final ComplaintRepository complaintRepository;
    private final ComplaintTransitionRepository transitionRepository;
    private final AttachmentRepository attachmentRepository;
    private final PasswordEncoder passwordEncoder;

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

    @Override
    @Transactional
    public void run(String... args) {
        if (wardRepository.count() > 0) {
            log.info("Database already seeded. Skipping seed execution.");
            return;
        }

        log.info("Seeding initial data...");

        // Seed Municipality
        Municipality dhakaNorth = municipalityRepository.save(Municipality.builder()
                .slug("dhaka-north")
                .name("Dhaka North City Corporation")
                .nameBn("ঢাকা উত্তর সিটি কর্পোরেশন")
                .isActive(true)
                .build());

        Municipality dhakaSouth = municipalityRepository.save(Municipality.builder()
                .slug("dhaka-south")
                .name("Dhaka South City Corporation")
                .nameBn("ঢাকা দক্ষিণ সিটি কর্পোরেশন")
                .isActive(true)
                .build());

        // Seed Users - Admin
        User admin = userRepository.save(User.builder()
                .fullName("System Admin")
                .email("admin@example.com")
                .phone("01700000001")
                .passwordHash(passwordEncoder.encode("admin123"))
                .role(UserRole.ADMIN)
                .active(true)
                .build());

        // Seed Demo Citizens (Phase 3 requirement)
        User citizen1 = userRepository.save(User.builder()
                .fullName("Rahim Ahmed")
                .email("citizen1@demo")
                .phone("01710000001")
                .passwordHash(passwordEncoder.encode("demo1234"))
                .role(UserRole.CITIZEN)
                .active(true)
                .build());

        User citizen2 = userRepository.save(User.builder()
                .fullName("Karim Hassan")
                .email("citizen2@demo")
                .phone("01710000002")
                .passwordHash(passwordEncoder.encode("demo1234"))
                .role(UserRole.CITIZEN)
                .active(true)
                .build());

        User citizen3 = userRepository.save(User.builder()
                .fullName("Salma Begum")
                .email("citizen3@demo")
                .phone("01710000003")
                .passwordHash(passwordEncoder.encode("demo1234"))
                .role(UserRole.CITIZEN)
                .active(true)
                .build());

        // Seed Wards with PostGIS boundaries
        Ward ward1 = wardRepository.save(Ward.builder()
                .municipality(dhakaNorth)
                .wardNumber(1)
                .areaName("Gulshan")
                .areaNameBn("গুলশান")
                .boundary(createPolygon(23.7900, 90.4050, 23.8050, 90.4200))
                .isActive(true)
                .build());

        Ward ward2 = wardRepository.save(Ward.builder()
                .municipality(dhakaNorth)
                .wardNumber(2)
                .areaName("Banani")
                .areaNameBn("বনানী")
                .boundary(createPolygon(23.7850, 90.3950, 23.7950, 90.4050))
                .isActive(true)
                .build());

        Ward ward3 = wardRepository.save(Ward.builder()
                .municipality(dhakaSouth)
                .wardNumber(3)
                .areaName("Dhanmondi")
                .areaNameBn("ধানমন্ডি")
                .boundary(createPolygon(23.7400, 90.3700, 23.7500, 90.3800))
                .isActive(true)
                .build());

        // Seed Councilors
        User councilor1 = userRepository.save(User.builder()
                .fullName("Councilor Ward 1")
                .email("councilor1@example.com")
                .phone("01700000002")
                .passwordHash(passwordEncoder.encode("councilor123"))
                .role(UserRole.WARD_COUNCILOR)
                .ward(ward1)
                .active(true)
                .build());

        // Seed Departments & Officers for Dhaka North
        for (Category category : Category.values()) {
            Department dept = departmentRepository.save(Department.builder()
                    .municipality(dhakaNorth)
                    .code(category.name())
                    .name(category.name())
                    .handlesCategories(new String[]{category.name()})
                    .isActive(true)
                    .build());

            if (category == Category.ROADS) {
                userRepository.save(User.builder()
                        .fullName("Roads Officer North")
                        .email("roads.north@example.com")
                        .phone("01700100001")
                        .passwordHash(passwordEncoder.encode("officer123"))
                        .role(UserRole.DEPT_OFFICER)
                        .ward(ward1)
                        .department(dept)
                        .active(true)
                        .build());
            }
        }

        // Seed Departments & Officers for Dhaka South
        for (Category category : Category.values()) {
            Department dept = departmentRepository.save(Department.builder()
                    .municipality(dhakaSouth)
                    .code(category.name())
                    .name(category.name())
                    .handlesCategories(new String[]{category.name()})
                    .isActive(true)
                    .build());

            if (category == Category.ROADS) {
                userRepository.save(User.builder()
                        .fullName("Roads Officer South")
                        .email("roads.south@example.com")
                        .phone("01700100002")
                        .passwordHash(passwordEncoder.encode("officer123"))
                        .role(UserRole.DEPT_OFFICER)
                        .ward(ward3)
                        .department(dept)
                        .active(true)
                        .build());
            }
        }

        // Seed SLA Rules
        for (Category category : Category.values()) {
            slaRuleRepository.saveAll(List.of(
                    SlaRule.builder().category(category).priority(Priority.LOW).maxHours(72)
                            .escalationLevel(1).build(),
                    SlaRule.builder().category(category).priority(Priority.NORMAL).maxHours(48)
                            .escalationLevel(1).build(),
                    SlaRule.builder().category(category).priority(Priority.HIGH).maxHours(24)
                            .escalationLevel(1).build(),
                    SlaRule.builder().category(category).priority(Priority.CRITICAL).maxHours(12)
                            .escalationLevel(2).build()));
        }

        // Seed Demo Complaints in various statuses
        seedDemoComplaints(citizen1, citizen2, citizen3, ward1, ward2, ward3, dhakaNorth, dhakaSouth);

        log.info("Initial data successfully seeded!");
    }

    private void seedDemoComplaints(User citizen1, User citizen2, User citizen3,
                                     Ward ward1, Ward ward2, Ward ward3,
                                     Municipality dhakaNorth, Municipality dhakaSouth) {
        Instant now = Instant.now();

        // Complaint 1: SUBMITTED
        Complaint c1 = createComplaint(citizen1, ward1, dhakaNorth,
                "Large pothole on Gulshan Avenue", "Dangerous pothole near Gulshan 1 circle causing accidents",
                Category.ROADS, Priority.NORMAL, 23.7925, 90.4120, now.minus(2, ChronoUnit.DAYS));
        c1.setStatus(ComplaintStatus.SUBMITTED);
        c1 = complaintRepository.saveAndFlush(c1);
        addTransition(c1, null, ComplaintStatus.SUBMITTED, ComplaintAction.SUBMIT, citizen1, "Complaint submitted", now.minus(2, ChronoUnit.DAYS));

        // Complaint 2: VERIFIED
        Complaint c2 = createComplaint(citizen1, ward1, dhakaNorth,
                "Broken streetlight in Banani", "Streetlight not working for 3 days in Banani Block C",
                Category.ELECTRICITY, Priority.NORMAL, 23.7890, 90.4000, now.minus(5, ChronoUnit.DAYS));
        c2.setStatus(ComplaintStatus.VERIFIED);
        c2.setFirstVerifiedAt(now.minus(3, ChronoUnit.DAYS));
        c2 = complaintRepository.saveAndFlush(c2);
        addTransition(c2, null, ComplaintStatus.SUBMITTED, ComplaintAction.SUBMIT, citizen1, "Complaint submitted", now.minus(5, ChronoUnit.DAYS));
        addTransition(c2, ComplaintStatus.SUBMITTED, ComplaintStatus.VERIFIED, ComplaintAction.VERIFY, councilor1, "Verified by ward councilor", now.minus(3, ChronoUnit.DAYS));

        // Complaint 3: REJECTED
        Complaint c3 = createComplaint(citizen2, ward2, dhakaNorth,
                "Fake complaint test", "This is a test complaint that will be rejected",
                Category.OTHER, Priority.LOW, 23.7870, 90.4020, now.minus(10, ChronoUnit.DAYS));
        c3.setStatus(ComplaintStatus.REJECTED);
        c3.setRejectionReason("Duplicate complaint - already reported");
        c3.setPublicVisible(false);
        c3.setModerationStatus(ModerationStatus.REJECTED);
        c3 = complaintRepository.saveAndFlush(c3);
        addTransition(c3, null, ComplaintStatus.SUBMITTED, ComplaintAction.SUBMIT, citizen2, "Complaint submitted", now.minus(10, ChronoUnit.DAYS));
        addTransition(c3, ComplaintStatus.SUBMITTED, ComplaintStatus.REJECTED, ComplaintAction.REJECT, councilor1, "Duplicate complaint - already reported", now.minus(8, ChronoUnit.DAYS));

        // Complaint 4: CANCELLED
        Complaint c4 = createComplaint(citizen2, ward3, dhakaSouth,
                "Waterlogging in Dhanmondi", "Road flooded after rain in Dhanmondi 27",
                Category.WATERLOGGING, Priority.HIGH, 23.7450, 90.3750, now.minus(7, ChronoUnit.DAYS));
        c4.setStatus(ComplaintStatus.CANCELLED);
        c4.setCancellationReason("Issue resolved by self");
        c4.setPublicVisible(false);
        c4 = complaintRepository.saveAndFlush(c4);
        addTransition(c4, null, ComplaintStatus.SUBMITTED, ComplaintAction.SUBMIT, citizen2, "Complaint submitted", now.minus(7, ChronoUnit.DAYS));
        addTransition(c4, ComplaintStatus.SUBMITTED, ComplaintStatus.CANCELLED, ComplaintAction.CANCEL, citizen2, "Issue resolved by self", now.minus(5, ChronoUnit.DAYS));

        // Complaint 5: SUBMITTED (anonymous)
        Complaint c5 = Complaint.builder()
                .municipality(dhakaNorth)
                .ward(ward1)
                .citizen(null)
                .anonymousContactPhone("01720000001")
                .title("Garbage not collected in Banani")
                .description("Garbage has not been collected for 4 days in Banani residential area")
                .category(Category.WASTE_MANAGEMENT)
                .status(ComplaintStatus.SUBMITTED)
                .priority(Priority.NORMAL)
                .location(createPoint(23.7880, 90.4010))
                .locationSource(LocationSource.MAP_PIN)
                .addressText("Banani Block D")
                .moderationStatus(ModerationStatus.PENDING)
                .submittedAt(now.minus(1, ChronoUnit.DAYS))
                .build();
        c5 = complaintRepository.saveAndFlush(c5);
        addTransition(c5, null, ComplaintStatus.SUBMITTED, ComplaintAction.SUBMIT, null, "Anonymous complaint submitted", now.minus(1, ChronoUnit.DAYS));

        // Complaint 6: VERIFIED (another citizen)
        Complaint c6 = createComplaint(citizen3, ward3, dhakaSouth,
                "Mosquito breeding in Dhanmondi Lake", "Standing water in lake area causing mosquito infestation",
                Category.MOSQUITO_BREEDING, Priority.HIGH, 23.7420, 90.3730, now.minus(4, ChronoUnit.DAYS));
        c6.setStatus(ComplaintStatus.VERIFIED);
        c6.setFirstVerifiedAt(now.minus(2, ChronoUnit.DAYS));
        c6 = complaintRepository.saveAndFlush(c6);
        addTransition(c6, null, ComplaintStatus.SUBMITTED, ComplaintAction.SUBMIT, citizen3, "Complaint submitted", now.minus(4, ChronoUnit.DAYS));
        addTransition(c6, ComplaintStatus.SUBMITTED, ComplaintStatus.VERIFIED, ComplaintAction.VERIFY, councilor1, "Verified - health hazard confirmed", now.minus(2, ChronoUnit.DAYS));

        // Complaint 7: SUBMITTED (recent)
        Complaint c7 = createComplaint(citizen1, ward2, dhakaNorth,
                "Broken footpath in Banani", "Footpath tiles broken and dangerous for pedestrians",
                Category.ROADS, Priority.NORMAL, 23.7860, 90.4030, now.minus(12, ChronoUnit.HOURS));
        c7.setStatus(ComplaintStatus.SUBMITTED);
        c7 = complaintRepository.saveAndFlush(c7);
        addTransition(c7, null, ComplaintStatus.SUBMITTED, ComplaintAction.SUBMIT, citizen1, "Complaint submitted", now.minus(12, ChronoUnit.HOURS));

        // Complaint 8: SUBMITTED (water supply)
        Complaint c8 = createComplaint(citizen3, ward1, dhakaNorth,
                "No water supply in Gulshan 2", "Water supply interrupted for 6 hours",
                Category.WATER_SUPPLY, Priority.CRITICAL, 23.7910, 90.4100, now.minus(6, ChronoUnit.HOURS));
        c8.setStatus(ComplaintStatus.SUBMITTED);
        c8 = complaintRepository.saveAndFlush(c8);
        addTransition(c8, null, ComplaintStatus.SUBMITTED, ComplaintAction.SUBMIT, citizen3, "Complaint submitted", now.minus(6, ChronoUnit.HOURS));

        // Complaint 9: VERIFIED (sanitation)
        Complaint c9 = createComplaint(citizen2, ward2, dhakaNorth,
                "Open drain in Banani", "Open drain causing foul smell and health hazard",
                Category.SANITATION, Priority.HIGH, 23.7885, 90.3990, now.minus(3, ChronoUnit.DAYS));
        c9.setStatus(ComplaintStatus.VERIFIED);
        c9.setFirstVerifiedAt(now.minus(1, ChronoUnit.DAYS));
        c9 = complaintRepository.saveAndFlush(c9);
        addTransition(c9, null, ComplaintStatus.SUBMITTED, ComplaintAction.SUBMIT, citizen2, "Complaint submitted", now.minus(3, ChronoUnit.DAYS));
        addTransition(c9, ComplaintStatus.SUBMITTED, ComplaintStatus.VERIFIED, ComplaintAction.VERIFY, councilor1, "Verified - sanitation issue", now.minus(1, ChronoUnit.DAYS));

        // Complaint 10: SUBMITTED
        Complaint c10 = createComplaint(citizen1, ward3, dhakaSouth,
                "Streetlight flickering in Dhanmondi", "Streetlight near Dhanmondi 27 flickering dangerously",
                Category.ELECTRICITY, Priority.NORMAL, 23.7460, 90.3740, now.minus(1, ChronoUnit.DAYS));
        c10.setStatus(ComplaintStatus.SUBMITTED);
        c10 = complaintRepository.saveAndFlush(c10);
        addTransition(c10, null, ComplaintStatus.SUBMITTED, ComplaintAction.SUBMIT, citizen1, "Complaint submitted", now.minus(1, ChronoUnit.DAYS));
    }

    private Complaint createComplaint(User citizen, Ward ward, Municipality municipality,
                                      String title, String description, Category category, Priority priority,
                                      double lat, double lng, Instant submittedAt) {
        return Complaint.builder()
                .municipality(municipality)
                .ward(ward)
                .citizen(citizen)
                .title(title)
                .description(description)
                .category(category)
                .priority(priority)
                .location(createPoint(lat, lng))
                .locationSource(LocationSource.DEVICE)
                .addressText(ward.getAreaName())
                .moderationStatus(ModerationStatus.APPROVED)
                .submittedAt(submittedAt)
                .build();
    }

    private Point createPoint(double lat, double lng) {
        Point point = GEOMETRY_FACTORY.createPoint(new Coordinate(lng, lat));
        point.setSRID(4326);
        return point;
    }

    private void addTransition(Complaint complaint, ComplaintStatus from, ComplaintStatus to,
                                ComplaintAction action, User actor, String note, Instant at) {
        ComplaintTransition transition = ComplaintTransition.builder()
                .complaint(complaint)
                .fromStatus(from)
                .toStatus(to)
                .action(action)
                .actor(actor)
                .actorRole(actor != null ? actor.getRole().name() : "SYSTEM")
                .note(note)
                .createdAt(at)
                .build();
        transitionRepository.save(transition);
    }

    private MultiPolygon createPolygon(double minLat, double minLon, double maxLat, double maxLon) {
        Coordinate[] coords = new Coordinate[]{
                new Coordinate(minLon, minLat),
                new Coordinate(maxLon, minLat),
                new Coordinate(maxLon, maxLat),
                new Coordinate(minLon, maxLat),
                new Coordinate(minLon, minLat)
        };
        Polygon polygon = GEOMETRY_FACTORY.createPolygon(coords);
        return GEOMETRY_FACTORY.createMultiPolygon(new Polygon[]{polygon});
    }
}