package com.nagorikseba.config;

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
import com.nagorikseba.entity.SlaRule;
import com.nagorikseba.enums.UserRole;
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
import com.nagorikseba.repository.SlaRuleRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.Year;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Demo data for local development and the integration suite.
 *
 * <h2>Why the complaints are built, never mutated</h2>
 * <p>Every seeded complaint reaches its status through {@code Complaint.builder()}.
 * It has to: {@code setStatus} is package-private and reachable only through
 * {@code ComplaintMutator}, which only transition handlers extend. A seeder that
 * could flip a complaint to VERIFIED directly would be the exact hole §7.1 exists
 * to close, so this class not compiling if it tried is the invariant working.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements CommandLineRunner {

    private final WardRepository wardRepository;
    private final MunicipalityRepository municipalityRepository;
    private final UserRepository userRepository;
    private final MembershipRepository membershipRepository;
    private final DepartmentRepository departmentRepository;
    private final SlaRuleRepository slaRuleRepository;
    private final ComplaintRepository complaintRepository;
    private final ComplaintTransitionRepository transitionRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

    @Override
    @Transactional
    public void run(String... args) {
        if (wardRepository.count() > 0) {
            log.info("Database already seeded. Skipping seed execution.");
            return;
        }

        log.info("Seeding initial data...");

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

        User admin = userRepository.save(User.builder()
                .fullName("System Admin")
                .email("admin@example.com")
                .phone("01700000001")
                .passwordHash(passwordEncoder.encode("admin123"))
                .role(UserRole.ADMIN)
                .active(true)
                .build());

        User citizen1 = seedCitizen("Rahim Ahmed", "citizen1@demo", "01710000001");
        User citizen2 = seedCitizen("Karim Hassan", "citizen2@demo", "01710000002");
        User citizen3 = seedCitizen("Salma Begum", "citizen3@demo", "01710000003");

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

        // Ward 17 is the councillor the security suite logs in as; the account name and
        // the ward number are expected to agree, so the ward exists even though no demo
        // complaint falls inside it.
        Ward ward17 = wardRepository.save(Ward.builder()
                .municipality(dhakaNorth)
                .wardNumber(17)
                .areaName("Uttara Sector 7")
                .areaNameBn("উত্তরা সেক্টর ৭")
                .boundary(createPolygon(23.8600, 90.3900, 23.8750, 90.4050))
                .isActive(true)
                .build());

        User councilor = userRepository.save(User.builder()
                .fullName("Councilor Ward 17")
                .email("councilor17@example.com")
                .phone("01700000002")
                .passwordHash(passwordEncoder.encode("councilor123"))
                .role(UserRole.WARD_COUNCILOR)
                .ward(ward17)
                .active(true)
                .build());

        // Memberships are what populate the JWT `mids` claim, which is what every
        // tenancy check reads. Without them an authority account authenticates but
        // serves no municipality, and every authority action answers 403.
        seedMembership(councilor, dhakaNorth, ward17, null);
        seedMembership(admin, dhakaNorth, null, null);

        seedDepartmentsAndOfficers(dhakaNorth, ward1, "north", "01700100001");
        seedDepartmentsAndOfficers(dhakaSouth, ward3, "south", "01700100002");

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

        seedDemoComplaints(citizen1, citizen2, citizen3, councilor,
                ward1, ward2, ward3, dhakaNorth, dhakaSouth);

        log.info("Initial data successfully seeded!");
    }

    private User seedCitizen(String fullName, String email, String phone) {
        return userRepository.save(User.builder()
                .fullName(fullName)
                .email(email)
                .phone(phone)
                .passwordHash(passwordEncoder.encode("demo1234"))
                .role(UserRole.CITIZEN)
                .active(true)
                .build());
    }

    private void seedMembership(User user, Municipality municipality, Ward ward, Department department) {
        membershipRepository.save(UserMunicipalityMembership.builder()
                .user(user)
                .municipality(municipality)
                .ward(ward)
                .department(department)
                .validFrom(clock.instant())
                .build());
    }

    private void seedDepartmentsAndOfficers(Municipality municipality, Ward officerWard,
                                            String suffix, String officerPhone) {
        for (Category category : Category.values()) {
            Department department = departmentRepository.save(Department.builder()
                    .municipality(municipality)
                    .code(category.name())
                    .name(category.name())
                    .handlesCategories(new String[]{category.name()})
                    .isActive(true)
                    .build());

            if (category == Category.ROADS) {
                User officer = userRepository.save(User.builder()
                        .fullName("Roads Officer " + Character.toUpperCase(suffix.charAt(0)) + suffix.substring(1))
                        .email("roads." + suffix + "@example.com")
                        .phone(officerPhone)
                        .passwordHash(passwordEncoder.encode("officer123"))
                        .role(UserRole.DEPT_OFFICER)
                        .ward(officerWard)
                        .department(department)
                        .active(true)
                        .build());
                seedMembership(officer, municipality, officerWard, department);
            }
        }
    }

    private void seedDemoComplaints(User citizen1, User citizen2, User citizen3, User councilor,
                                    Ward ward1, Ward ward2, Ward ward3,
                                    Municipality dhakaNorth, Municipality dhakaSouth) {
        Instant now = clock.instant();

        // 1 — SUBMITTED
        Complaint c1 = save(base(citizen1, ward1, dhakaNorth,
                "Large pothole on Gulshan Avenue", "Dangerous pothole near Gulshan 1 circle causing accidents",
                Category.ROADS, Priority.NORMAL, 23.7925, 90.4120, now.minus(2, ChronoUnit.DAYS))
                .status(ComplaintStatus.SUBMITTED)
                .build());
        addTransition(c1, null, ComplaintStatus.SUBMITTED, ComplaintAction.SUBMIT, citizen1,
                "Complaint submitted", now.minus(2, ChronoUnit.DAYS));

        // 2 — VERIFIED
        Instant c2Verified = now.minus(3, ChronoUnit.DAYS);
        Complaint c2 = save(base(citizen1, ward1, dhakaNorth,
                "Broken streetlight in Banani", "Streetlight not working for 3 days in Banani Block C",
                Category.ELECTRICITY, Priority.NORMAL, 23.7890, 90.4000, now.minus(5, ChronoUnit.DAYS))
                .status(ComplaintStatus.VERIFIED)
                .firstVerifiedAt(c2Verified)
                .lastTransitionAt(c2Verified)
                .build());
        addTransition(c2, null, ComplaintStatus.SUBMITTED, ComplaintAction.SUBMIT, citizen1,
                "Complaint submitted", now.minus(5, ChronoUnit.DAYS));
        addTransition(c2, ComplaintStatus.SUBMITTED, ComplaintStatus.VERIFIED, ComplaintAction.VERIFY, councilor,
                "Verified by ward councilor", c2Verified);

        // 3 — REJECTED
        Instant c3Rejected = now.minus(8, ChronoUnit.DAYS);
        Complaint c3 = save(base(citizen2, ward2, dhakaNorth,
                "Fake complaint test", "This is a test complaint that will be rejected",
                Category.OTHER, Priority.LOW, 23.7870, 90.4020, now.minus(10, ChronoUnit.DAYS))
                .status(ComplaintStatus.REJECTED)
                .rejectionReason("Duplicate complaint - already reported")
                .publicVisible(false)
                .moderationStatus(ModerationStatus.REJECTED)
                .lastTransitionAt(c3Rejected)
                .build());
        addTransition(c3, null, ComplaintStatus.SUBMITTED, ComplaintAction.SUBMIT, citizen2,
                "Complaint submitted", now.minus(10, ChronoUnit.DAYS));
        addTransition(c3, ComplaintStatus.SUBMITTED, ComplaintStatus.REJECTED, ComplaintAction.REJECT, councilor,
                "Duplicate complaint - already reported", c3Rejected);

        // 4 — CANCELLED
        Instant c4Cancelled = now.minus(5, ChronoUnit.DAYS);
        Complaint c4 = save(base(citizen2, ward3, dhakaSouth,
                "Waterlogging in Dhanmondi", "Road flooded after rain in Dhanmondi 27",
                Category.WATERLOGGING, Priority.HIGH, 23.7450, 90.3750, now.minus(7, ChronoUnit.DAYS))
                .status(ComplaintStatus.CANCELLED)
                .cancellationReason("Issue resolved by self")
                .publicVisible(false)
                .lastTransitionAt(c4Cancelled)
                .build());
        addTransition(c4, null, ComplaintStatus.SUBMITTED, ComplaintAction.SUBMIT, citizen2,
                "Complaint submitted", now.minus(7, ChronoUnit.DAYS));
        addTransition(c4, ComplaintStatus.SUBMITTED, ComplaintStatus.CANCELLED, ComplaintAction.CANCEL, citizen2,
                "Issue resolved by self", c4Cancelled);

        // 5 — SUBMITTED, anonymous: no citizen, contact phone instead, pending moderation
        Complaint c5 = save(Complaint.builder()
                .referenceCode(nextReferenceCode())
                .municipality(dhakaNorth)
                .ward(ward1)
                .citizen(null)
                .anonymousContactPhone("01720000001")
                .title("Garbage not collected in Banani")
                .description("Garbage has not been collected for 4 days in Banani residential area")
                .category(Category.WASTE_MANAGEMENT)
                .status(ComplaintStatus.SUBMITTED)
                .priority(Priority.LOW)
                .location(createPoint(23.7880, 90.4010))
                .locationSource(LocationSource.MAP_PIN)
                .addressText("Banani Block D")
                .publicVisible(true)
                .moderationStatus(ModerationStatus.PENDING)
                .submittedAt(now.minus(1, ChronoUnit.DAYS))
                .build());
        addTransition(c5, null, ComplaintStatus.SUBMITTED, ComplaintAction.SUBMIT, null,
                "Anonymous complaint submitted", now.minus(1, ChronoUnit.DAYS));

        // 6 — VERIFIED
        Instant c6Verified = now.minus(2, ChronoUnit.DAYS);
        Complaint c6 = save(base(citizen3, ward3, dhakaSouth,
                "Mosquito breeding in Dhanmondi Lake", "Standing water in lake area causing mosquito infestation",
                Category.MOSQUITO_BREEDING, Priority.HIGH, 23.7420, 90.3730, now.minus(4, ChronoUnit.DAYS))
                .status(ComplaintStatus.VERIFIED)
                .firstVerifiedAt(c6Verified)
                .lastTransitionAt(c6Verified)
                .build());
        addTransition(c6, null, ComplaintStatus.SUBMITTED, ComplaintAction.SUBMIT, citizen3,
                "Complaint submitted", now.minus(4, ChronoUnit.DAYS));
        addTransition(c6, ComplaintStatus.SUBMITTED, ComplaintStatus.VERIFIED, ComplaintAction.VERIFY, councilor,
                "Verified - health hazard confirmed", c6Verified);

        // 7 — SUBMITTED
        Complaint c7 = save(base(citizen1, ward2, dhakaNorth,
                "Broken footpath in Banani", "Footpath tiles broken and dangerous for pedestrians",
                Category.ROADS, Priority.NORMAL, 23.7860, 90.4030, now.minus(12, ChronoUnit.HOURS))
                .status(ComplaintStatus.SUBMITTED)
                .build());
        addTransition(c7, null, ComplaintStatus.SUBMITTED, ComplaintAction.SUBMIT, citizen1,
                "Complaint submitted", now.minus(12, ChronoUnit.HOURS));

        // 8 — SUBMITTED
        Complaint c8 = save(base(citizen3, ward1, dhakaNorth,
                "No water supply in Gulshan 2", "Water supply interrupted for 6 hours",
                Category.WATER_SUPPLY, Priority.CRITICAL, 23.7910, 90.4100, now.minus(6, ChronoUnit.HOURS))
                .status(ComplaintStatus.SUBMITTED)
                .build());
        addTransition(c8, null, ComplaintStatus.SUBMITTED, ComplaintAction.SUBMIT, citizen3,
                "Complaint submitted", now.minus(6, ChronoUnit.HOURS));

        // 9 — VERIFIED
        Instant c9Verified = now.minus(1, ChronoUnit.DAYS);
        Complaint c9 = save(base(citizen2, ward2, dhakaNorth,
                "Open drain in Banani", "Open drain causing foul smell and health hazard",
                Category.SANITATION, Priority.HIGH, 23.7885, 90.3990, now.minus(3, ChronoUnit.DAYS))
                .status(ComplaintStatus.VERIFIED)
                .firstVerifiedAt(c9Verified)
                .lastTransitionAt(c9Verified)
                .build());
        addTransition(c9, null, ComplaintStatus.SUBMITTED, ComplaintAction.SUBMIT, citizen2,
                "Complaint submitted", now.minus(3, ChronoUnit.DAYS));
        addTransition(c9, ComplaintStatus.SUBMITTED, ComplaintStatus.VERIFIED, ComplaintAction.VERIFY, councilor,
                "Verified - sanitation issue", c9Verified);

        // 10 — SUBMITTED
        Complaint c10 = save(base(citizen1, ward3, dhakaSouth,
                "Streetlight flickering in Dhanmondi", "Streetlight near Dhanmondi 27 flickering dangerously",
                Category.ELECTRICITY, Priority.NORMAL, 23.7460, 90.3740, now.minus(1, ChronoUnit.DAYS))
                .status(ComplaintStatus.SUBMITTED)
                .build());
        addTransition(c10, null, ComplaintStatus.SUBMITTED, ComplaintAction.SUBMIT, citizen1,
                "Complaint submitted", now.minus(1, ChronoUnit.DAYS));
    }

    /** The fields every demo complaint shares; callers add status and its timestamps. */
    private Complaint.ComplaintBuilder base(User citizen, Ward ward, Municipality municipality,
                                            String title, String description, Category category,
                                            Priority priority, double lat, double lng, Instant submittedAt) {
        return Complaint.builder()
                .referenceCode(nextReferenceCode())
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
                .publicVisible(true)
                .moderationStatus(ModerationStatus.APPROVED)
                .submittedAt(submittedAt);
    }

    private Complaint save(Complaint complaint) {
        return complaintRepository.saveAndFlush(complaint);
    }

    /** Same sequence the submission template draws from, so demo and live codes never collide. */
    private String nextReferenceCode() {
        Number sequence = (Number) entityManager
                .createNativeQuery("SELECT nextval('complaint_ref_seq')")
                .getSingleResult();
        int year = Year.from(clock.instant().atZone(ZoneOffset.UTC)).getValue();
        return "NS-%d-%06d".formatted(year, sequence.longValue());
    }

    private Point createPoint(double lat, double lng) {
        Point point = GEOMETRY_FACTORY.createPoint(new Coordinate(lng, lat));
        point.setSRID(4326);
        return point;
    }

    private void addTransition(Complaint complaint, ComplaintStatus from, ComplaintStatus to,
                               ComplaintAction action, User actor, String note, Instant at) {
        transitionRepository.save(ComplaintTransition.builder()
                .complaint(complaint)
                .fromStatus(from)
                .toStatus(to)
                .action(action)
                .actor(actor)
                .actorRole(actor != null ? actor.getRole().name() : "SYSTEM")
                .note(note)
                .createdAt(at)
                .build());
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
