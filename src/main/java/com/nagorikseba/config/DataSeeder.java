package com.nagorikseba.config;

import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.domain.ComplaintAssignment;
import com.nagorikseba.complaint.domain.ComplaintTransition;
import com.nagorikseba.complaint.domain.ResolutionAttempt;
import com.nagorikseba.complaint.domain.enums.Category;
import com.nagorikseba.complaint.domain.enums.ComplaintAction;
import com.nagorikseba.complaint.domain.enums.ComplaintStatus;
import com.nagorikseba.complaint.domain.enums.LocationSource;
import com.nagorikseba.complaint.domain.enums.ModerationStatus;
import com.nagorikseba.complaint.domain.enums.Priority;
import com.nagorikseba.complaint.repo.ComplaintAssignmentRepository;
import com.nagorikseba.complaint.repo.ComplaintRepository;
import com.nagorikseba.complaint.repo.ComplaintTransitionRepository;
import com.nagorikseba.complaint.repo.ResolutionAttemptRepository;
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
import com.nagorikseba.sla.SlaPolicy;
import com.nagorikseba.sla.SlaPolicyRepository;
import com.nagorikseba.sla.SlaService;
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
    private final SlaPolicyRepository slaPolicyRepository;
    private final ComplaintRepository complaintRepository;
    private final ComplaintTransitionRepository transitionRepository;
    private final ComplaintAssignmentRepository assignmentRepository;
    private final ResolutionAttemptRepository attemptRepository;
    private final SlaService slaService;
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

        // Final cleanup: wards 4–5 fill the Mirpur/Pallabi gaps with boxes that
        // touch nothing existing (ward 4 shares only the y=23.805 edge with
        // ward 1 — adjacency, not overlap).
        Ward ward4 = wardRepository.save(Ward.builder()
                .municipality(dhakaNorth)
                .wardNumber(4)
                .areaName("Mirpur 10")
                .areaNameBn("মিরপুর ১০")
                .boundary(createPolygon(23.8050, 90.3550, 23.8200, 90.3700))
                .isActive(true)
                .build());

        Ward ward5 = wardRepository.save(Ward.builder()
                .municipality(dhakaNorth)
                .wardNumber(5)
                .areaName("Pallabi")
                .areaNameBn("পল্লবী")
                .boundary(createPolygon(23.7550, 90.3500, 23.7700, 90.3650))
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

        // Final cleanup: the legacy suite logs in as councilor17@example.com,
        // so that account stays untouched; these @demo twins are the documented
        // demo credentials (all demo1234) for humans and the smoke test.
        User councilorDemo = userRepository.save(User.builder()
                .fullName("Councilor Ward 17 Demo")
                .email("councilor17@demo")
                .phone("01700000003")
                .passwordHash(passwordEncoder.encode("demo1234"))
                .role(UserRole.WARD_COUNCILOR)
                .ward(ward17)
                .active(true)
                .build());

        User adminDemo = userRepository.save(User.builder()
                .fullName("Demo Admin")
                .email("admin@demo")
                .phone("01700000004")
                .passwordHash(passwordEncoder.encode("demo1234"))
                .role(UserRole.ADMIN)
                .active(true)
                .build());

        // Memberships are what populate the JWT `mids` claim, which is what every
        // tenancy check reads. Without them an authority account authenticates but
        // serves no municipality, and every authority action answers 403.
        seedMembership(councilor, dhakaNorth, ward17, null);
        seedMembership(admin, dhakaNorth, null, null);
        seedMembership(councilorDemo, dhakaNorth, ward17, null);
        seedMembership(adminDemo, dhakaNorth, null, null);

        seedDepartmentsAndOfficers(dhakaNorth, ward1, "north", "01700100001");
        seedDepartmentsAndOfficers(dhakaSouth, ward3, "south", "01700100002");

        // Final cleanup: PARKS has no Category enum value, so it handles OTHER —
        // the category-routing lookup matches on the handles array verbatim.
        // Both municipalities get one: the bulk seeder resolves OTHER in either.
        for (Municipality municipality : List.of(dhakaNorth, dhakaSouth)) {
            departmentRepository.save(Department.builder()
                    .municipality(municipality)
                    .code("PARKS")
                    .name("Parks & Green Spaces")
                    .handlesCategories(new String[]{Category.OTHER.name()})
                    .isActive(true)
                    .build());
        }

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

        // Phase 5: municipality SLA policies for every category × priority.
        // Hours mirror the legacy rules (LOW 72, NORMAL 48, HIGH 24, CRITICAL 12);
        // level 1 escalates at three quarters of the deadline, level 2 at it.
        for (Municipality municipality : List.of(dhakaNorth, dhakaSouth)) {
            for (Category category : Category.values()) {
                for (Priority priority : Priority.values()) {
                    int hours = switch (priority) {
                        case LOW -> 72;
                        case NORMAL -> 48;
                        case HIGH -> 24;
                        case CRITICAL -> 12;
                    };
                    slaPolicyRepository.save(SlaPolicy.builder()
                            .municipality(municipality)
                            .category(category)
                            .priority(priority)
                            .maxHours(hours)
                            .escalationLevel1Hours(hours * 3 / 4)
                            .escalationLevel2Hours(hours)
                            .active(true)
                            .build());
                }
            }
        }

        seedDemoComplaints(citizen1, citizen2, citizen3, councilor,
                ward1, ward2, ward3, dhakaNorth, dhakaSouth);

        // Phase 4: one officer per department for the first four categories plus
        // sample complaints in ASSIGNED and IN_PROGRESS states.
        seedPhase4OfficersAndAssignments(citizen1, citizen3, councilor,
                ward1, dhakaNorth);

        // Final cleanup: bulk demo spread (~48 more) across wards, categories
        // and all nine statuses with matching history rows.
        seedBulkDemoComplaints(citizen1, citizen2, citizen3, councilor,
                ward1, ward2, ward3, ward4, ward5, dhakaNorth, dhakaSouth);

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

    /**
     * Phase 4 demo data: one officer per department for the first four
     * categories (officer1@demo … officer4@demo, password demo1234), office
     * locations on those departments for distance routing, and sample
     * complaints in ASSIGNED and IN_PROGRESS states with assignment rows.
     */
    private void seedPhase4OfficersAndAssignments(User citizen1, User citizen3, User councilor,
                                                  Ward ward1, Municipality dhakaNorth) {
        Instant now = clock.instant();
        Category[] categories = {Category.ROADS, Category.WATER_SUPPLY, Category.ELECTRICITY, Category.SANITATION};
        double[][] offices = {{23.7925, 90.4120}, {23.7890, 90.4000}, {23.7870, 90.4020}, {23.7885, 90.3990}};

        for (int i = 0; i < categories.length; i++) {
            Department department = departmentRepository
                    .findByMunicipalityIdAndCode(dhakaNorth.getId(), categories[i].name())
                    .orElseThrow();
            department.setOfficeLocation(createPoint(offices[i][0], offices[i][1]));
            departmentRepository.save(department);

            User officer = userRepository.save(User.builder()
                    .fullName("Demo Officer " + (i + 1))
                    .email("officer" + (i + 1) + "@demo")
                    .phone("0170020000" + (i + 1))
                    .passwordHash(passwordEncoder.encode("demo1234"))
                    .role(UserRole.DEPT_OFFICER)
                    .ward(ward1)
                    .department(department)
                    .active(true)
                    .build());
            seedMembership(officer, dhakaNorth, ward1, department);

            if (i == 0) {
                // 11 — ASSIGNED (ROADS, officer1)
                Instant assigned = now.minus(1, ChronoUnit.DAYS);
                Complaint c11 = save(base(citizen1, ward1, dhakaNorth,
                        "Pothole cluster near Gulshan 2", "Three potholes in a row near the Gulshan 2 circle",
                        Category.ROADS, Priority.HIGH, 23.7910, 90.4100, now.minus(2, ChronoUnit.DAYS))
                        .status(ComplaintStatus.ASSIGNED)
                        .assignedDepartment(department)
                        .assignedOfficer(officer)
                        .firstVerifiedAt(now.minus(2, ChronoUnit.DAYS).plus(6, ChronoUnit.HOURS))
                        .firstAssignedAt(assigned)
                        .lastTransitionAt(assigned)
                        .build());
                addTransition(c11, null, ComplaintStatus.SUBMITTED, ComplaintAction.SUBMIT, citizen1,
                        "Complaint submitted", now.minus(2, ChronoUnit.DAYS));
                addTransition(c11, ComplaintStatus.SUBMITTED, ComplaintStatus.VERIFIED, ComplaintAction.VERIFY,
                        councilor, "Verified - road damage confirmed", now.minus(2, ChronoUnit.DAYS).plus(6, ChronoUnit.HOURS));
                addTransition(c11, ComplaintStatus.VERIFIED, ComplaintStatus.ASSIGNED, ComplaintAction.ASSIGN,
                        councilor, "Assigned to ROADS", assigned);
                assignmentRepository.save(ComplaintAssignment.builder()
                        .complaint(c11)
                        .department(department)
                        .officer(officer)
                        .assignedBy(councilor)
                        .strategyUsed("MANUAL")
                        .strategyExplanation("manually assigned to department ROADS by seed data")
                        .build());
            }

            if (i == 1) {
                // 12 — IN_PROGRESS (WATER_SUPPLY, officer2)
                Instant assigned = now.minus(20, ChronoUnit.HOURS);
                Complaint c12 = save(base(citizen3, ward1, dhakaNorth,
                        "Low water pressure in Gulshan", "Water pressure very low for two days in Gulshan block B",
                        Category.WATER_SUPPLY, Priority.HIGH, 23.7930, 90.4110, now.minus(2, ChronoUnit.DAYS))
                        .status(ComplaintStatus.IN_PROGRESS)
                        .assignedDepartment(department)
                        .assignedOfficer(officer)
                        .firstVerifiedAt(now.minus(2, ChronoUnit.DAYS).plus(4, ChronoUnit.HOURS))
                        .firstAssignedAt(assigned)
                        .lastTransitionAt(now.minus(4, ChronoUnit.HOURS))
                        .build());
                addTransition(c12, null, ComplaintStatus.SUBMITTED, ComplaintAction.SUBMIT, citizen3,
                        "Complaint submitted", now.minus(2, ChronoUnit.DAYS));
                addTransition(c12, ComplaintStatus.SUBMITTED, ComplaintStatus.VERIFIED, ComplaintAction.VERIFY,
                        councilor, "Verified - supply issue confirmed", now.minus(2, ChronoUnit.DAYS).plus(4, ChronoUnit.HOURS));
                addTransition(c12, ComplaintStatus.VERIFIED, ComplaintStatus.ASSIGNED, ComplaintAction.ASSIGN,
                        councilor, "Assigned to WATER_SUPPLY", assigned);
                addTransition(c12, ComplaintStatus.ASSIGNED, ComplaintStatus.IN_PROGRESS, ComplaintAction.START,
                        officer, "Work started", now.minus(4, ChronoUnit.HOURS));
                assignmentRepository.save(ComplaintAssignment.builder()
                        .complaint(c12)
                        .department(department)
                        .officer(officer)
                        .assignedBy(councilor)
                        .strategyUsed("MANUAL")
                        .strategyExplanation("manually assigned to department WATER_SUPPLY by seed data")
                        .build());
            }
        }
    }

    /**
     * Final cleanup bulk demo: ~48 complaints across five wards, all categories
     * and all nine statuses, with history rows matching each status.
     *
     * <p>Deterministic by construction (modular arithmetic over fixed arrays —
     * no Random), spanning ~6 months back. dhakaSouth ward 3 only carries
     * SUBMITTED/VERIFIED/REJECTED/CANCELLED (its only officer is ROADS-posted);
     * the north wards carry the full spectrum. Every status reaches its value
     * through {@code Complaint.builder()} at construction — never
     * {@code setStatus()}, which this class could not call anyway (§7.1
     * invariant).
     */
    private void seedBulkDemoComplaints(User citizen1, User citizen2, User citizen3, User councilor,
                                        Ward ward1, Ward ward2, Ward ward3, Ward ward4, Ward ward5,
                                        Municipality dhakaNorth, Municipality dhakaSouth) {
        Instant now = clock.instant();
        User[] citizens = {citizen1, citizen2, citizen3};
        Ward[] wards = {ward1, ward2, ward4, ward5, ward3};
        double[][][] pins = {
                {{23.7925, 90.4120}, {23.7955, 90.4080}, {23.7975, 90.4150}, {23.7905, 90.4070}},
                {{23.7870, 90.3980}, {23.7910, 90.4010}, {23.7890, 90.4030}},
                {{23.8120, 90.3600}, {23.8150, 90.3650}, {23.8080, 90.3620}},
                {{23.7600, 90.3550}, {23.7650, 90.3600}, {23.7580, 90.3580}},
                {{23.7430, 90.3730}, {23.7470, 90.3770}, {23.7450, 90.3750}},
        };
        String[][] titles = {
                {"Pothole on main road", "Broken footpath slabs"},
                {"No water in the morning line", "Leaking supply pipe"},
                {"Streetlight dead for a week", "Flickering lamp post"},
                {"Overflowing dustbin", "Garbage pile uncollected"},
                {"Waterlogged lane after rain", "Blocked roadside drain"},
                {"Stagnant pool breeds mosquitoes", "Fogging requested"},
                {"Open drain foul smell", "Clogged sewer line"},
                {"Fallen tree branch", "Damaged park bench"},
        };
        String[] descriptions = {
                "Reported by several residents; needs ward attention.",
                "Worse after rain; schoolchildren use this route daily.",
        };
        // Residue-planned spectra: south wards sit at i%5==4, removing one slot
        // from residues r0–r8 in turn (north counts per residue: 5,5,5,4,4,4,4,4,4).
        // CLOSED sits at r0 (5 north) plus exactly one south CLOSED via ordinal
        // indexing — 6 rated closes total. Guaranteed minimums: CLOSED×6 rated,
        // REOPENED×4 (reopenCount 1–2), REJECTED×6 with reasons, 30+ past-deadline
        // actives, all 8 categories ×6 and all 5 wards covered.
        ComplaintStatus[] fullSpectrum = {
                ComplaintStatus.CLOSED, ComplaintStatus.SUBMITTED, ComplaintStatus.VERIFIED,
                ComplaintStatus.ASSIGNED, ComplaintStatus.IN_PROGRESS, ComplaintStatus.RESOLVED,
                ComplaintStatus.REJECTED, ComplaintStatus.CANCELLED, ComplaintStatus.REOPENED,
        };
        ComplaintStatus[] southSpectrum = {
                ComplaintStatus.SUBMITTED, ComplaintStatus.VERIFIED,
                ComplaintStatus.REJECTED, ComplaintStatus.CANCELLED, ComplaintStatus.CLOSED,
        };
        int[] ratings = {5, 4, 3, 5, 4};

        User officer1 = findUser("officer1@demo");
        User officer2 = findUser("officer2@demo");
        User officer3 = findUser("officer3@demo");
        User officer4 = findUser("officer4@demo");

        for (int i = 0; i < 48; i++) {
            Category category = Category.values()[i % Category.values().length];
            Ward ward = wards[i % wards.length];
            Municipality municipality = ward == ward3 ? dhakaSouth : dhakaNorth;
            User citizen = citizens[i % citizens.length];
            double[] pin = pins[i % pins.length][(i / pins.length) % pins[i % pins.length].length];
            Priority priority = Priority.values()[i % Priority.values().length];
            Instant submitted = now.minus((i * 3L) + 5L, ChronoUnit.DAYS);
            long ageHours = Math.max(24L, durationHours(submitted, now));

            ComplaintStatus status = ward == ward3
                    ? southSpectrum[((i - 4) / 5) % southSpectrum.length]
                    : fullSpectrum[i % fullSpectrum.length];

            Instant verified = submitted.plus(ageHours * 20 / 100, ChronoUnit.HOURS);
            Instant assigned = submitted.plus(ageHours * 40 / 100, ChronoUnit.HOURS);
            Instant started = submitted.plus(ageHours * 55 / 100, ChronoUnit.HOURS);
            Instant resolved = submitted.plus(ageHours * 70 / 100, ChronoUnit.HOURS);
            Instant terminal = submitted.plus(ageHours * 85 / 100, ChronoUnit.HOURS);

            Department department = findDepartment(municipality, category);
            User officer = pickOfficer(category, officer1, officer2, officer3, officer4);

            Complaint.ComplaintBuilder builder = base(citizen, ward, municipality,
                    titles[category.ordinal()][i % 2] + " #" + (13 + i),
                    descriptions[i % descriptions.length],
                    category, priority, pin[0], pin[1], submitted)
                    .status(status)
                    .lastTransitionAt(submitted);

            switch (status) {
                case VERIFIED, ASSIGNED, IN_PROGRESS, RESOLVED, CLOSED, REOPENED -> {
                    builder.firstVerifiedAt(verified).lastTransitionAt(verified);
                }
                default -> {
                }
            }
            if (status == ComplaintStatus.ASSIGNED || status == ComplaintStatus.IN_PROGRESS
                    || status == ComplaintStatus.RESOLVED || status == ComplaintStatus.CLOSED
                    || status == ComplaintStatus.REOPENED) {
                builder.assignedDepartment(department).assignedOfficer(officer)
                        .firstAssignedAt(assigned).lastTransitionAt(assigned);
            }
            if (status == ComplaintStatus.RESOLVED || status == ComplaintStatus.CLOSED
                    || status == ComplaintStatus.REOPENED) {
                builder.resolvedAt(resolved).lastTransitionAt(resolved);
            }
            if (status == ComplaintStatus.CLOSED) {
                builder.closedAt(terminal).lastTransitionAt(terminal);
            }
            if (status == ComplaintStatus.REJECTED) {
                builder.rejectionReason("Seeded rejection: duplicate of an older report")
                        .publicVisible(false)
                        .moderationStatus(ModerationStatus.REJECTED)
                        .lastTransitionAt(terminal);
            }
            if (status == ComplaintStatus.CANCELLED) {
                builder.cancellationReason("Seeded cancellation: reporter withdrew")
                        .publicVisible(false)
                        .lastTransitionAt(terminal);
            }
            if (status == ComplaintStatus.REOPENED) {
                builder.reopenCount(1 + (i % 2)).priority(Priority.HIGH).lastTransitionAt(terminal);
            }
            Complaint complaint = save(builder.build());

            addTransition(complaint, null, ComplaintStatus.SUBMITTED, ComplaintAction.SUBMIT,
                    citizen, "Complaint submitted", submitted);
            if (status != ComplaintStatus.SUBMITTED) {
                User verifier = status == ComplaintStatus.CANCELLED ? citizen : councilor;
                ComplaintAction verifyAction = status == ComplaintStatus.REJECTED
                        ? ComplaintAction.REJECT : status == ComplaintStatus.CANCELLED
                        ? ComplaintAction.CANCEL : ComplaintAction.VERIFY;
                ComplaintStatus afterVerify = status == ComplaintStatus.REJECTED ? ComplaintStatus.REJECTED
                        : status == ComplaintStatus.CANCELLED ? ComplaintStatus.CANCELLED
                        : ComplaintStatus.VERIFIED;
                String verifyNote = status == ComplaintStatus.REJECTED ? "Seeded rejection: duplicate"
                        : status == ComplaintStatus.CANCELLED ? "Seeded cancellation" : "Seeded verification";
                addTransition(complaint, ComplaintStatus.SUBMITTED, afterVerify, verifyAction,
                        verifier, verifyNote, verifiedOrTerminal(verified, terminal, status));
            }
            if (status == ComplaintStatus.ASSIGNED || status == ComplaintStatus.IN_PROGRESS
                    || status == ComplaintStatus.RESOLVED || status == ComplaintStatus.CLOSED
                    || status == ComplaintStatus.REOPENED) {
                addTransition(complaint, ComplaintStatus.VERIFIED, ComplaintStatus.ASSIGNED,
                        ComplaintAction.ASSIGN, councilor,
                        "Seeded assignment to " + department.getCode(), assigned);
                assignmentRepository.save(ComplaintAssignment.builder()
                        .complaint(complaint)
                        .department(department)
                        .officer(officer)
                        .assignedBy(councilor)
                        .strategyUsed("MANUAL")
                        .strategyExplanation("seeded demo assignment")
                        .build());
            }
            if (status == ComplaintStatus.IN_PROGRESS || status == ComplaintStatus.RESOLVED
                    || status == ComplaintStatus.CLOSED || status == ComplaintStatus.REOPENED) {
                addTransition(complaint, ComplaintStatus.ASSIGNED, ComplaintStatus.IN_PROGRESS,
                        ComplaintAction.START, officer, "Seeded work start", started);
            }
            if (status == ComplaintStatus.RESOLVED || status == ComplaintStatus.CLOSED
                    || status == ComplaintStatus.REOPENED) {
                addTransition(complaint, ComplaintStatus.IN_PROGRESS, ComplaintStatus.RESOLVED,
                        ComplaintAction.RESOLVE, officer, "Seeded resolution", resolved);
                int attemptNumber = (int) attemptRepository.countByComplaintId(complaint.getId()) + 1;
                ResolutionAttempt attempt = attemptRepository.save(ResolutionAttempt.builder()
                        .complaint(complaint)
                        .attemptNumber(attemptNumber)
                        .resolvedAt(resolved)
                        .resolvedBy(officer)
                        .resolutionNote("Seeded resolution")
                        .outcome(ResolutionAttempt.Outcome.PENDING_CITIZEN)
                        .build());
                if (status == ComplaintStatus.CLOSED) {
                    addTransition(complaint, ComplaintStatus.RESOLVED, ComplaintStatus.CLOSED,
                            ComplaintAction.CLOSE, citizen, "Seeded rating", terminal);
                    attempt.setOutcome(ResolutionAttempt.Outcome.CLOSED);
                    attempt.setRating(ratings[i % ratings.length]);
                    attempt.setRatingFeedback("Seeded feedback");
                    attempt.setRatedAt(terminal);
                    attemptRepository.save(attempt);
                }
                if (status == ComplaintStatus.REOPENED) {
                    addTransition(complaint, ComplaintStatus.RESOLVED, ComplaintStatus.REOPENED,
                            ComplaintAction.REOPEN, citizen, "Seeded reopen: still broken", terminal);
                    attempt.setOutcome(ResolutionAttempt.Outcome.REOPENED);
                    attempt.setReopenReason("Seeded reopen: still broken");
                    attempt.setReopenedAt(terminal);
                    attemptRepository.save(attempt);
                }
                // Close the assignment once work left IN_PROGRESS.
                assignmentRepository.findByComplaintIdAndUnassignedAtIsNull(complaint.getId())
                        .ifPresent(open -> open.close(resolved));
            }
            if (status == ComplaintStatus.SUBMITTED || status == ComplaintStatus.VERIFIED
                    || status == ComplaintStatus.ASSIGNED || status == ComplaintStatus.IN_PROGRESS
                    || status == ComplaintStatus.REOPENED) {
                // Old submissions snapshot past deadlines so the scanner demo fires.
                slaService.ensureInstance(complaint);
            }
        }
    }

    /** Dh : picks the seeded officer posted to the category's department. */
    private User pickOfficer(Category category, User officer1, User officer2, User officer3, User officer4) {
        return switch (category) {
            case ROADS -> officer1;
            case WATER_SUPPLY -> officer2;
            case ELECTRICITY -> officer3;
            case SANITATION -> officer4;
            default -> officer1;
        };
    }

    private Department findDepartment(Municipality municipality, Category category) {
        String code = category == Category.OTHER ? "PARKS" : category.name();
        return departmentRepository.findByMunicipalityIdAndCode(municipality.getId(), code)
                .orElseThrow(() -> new IllegalStateException("Missing seeded department " + code));
    }

    private User findUser(String email) {
        return userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new IllegalStateException("Missing seeded user " + email));
    }

    private static long durationHours(Instant start, Instant end) {
        return Math.max(1L, java.time.Duration.between(start, end).toHours());
    }

    private static Instant verifiedOrTerminal(Instant verified, Instant terminal, ComplaintStatus status) {
        return status == ComplaintStatus.REJECTED || status == ComplaintStatus.CANCELLED ? terminal : verified;
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
