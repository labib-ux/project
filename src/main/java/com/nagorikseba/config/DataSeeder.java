package com.nagorikseba.config;

import com.nagorikseba.entity.SlaRule;
import com.nagorikseba.entity.User;
import com.nagorikseba.enums.ComplaintCategory;
import com.nagorikseba.enums.Priority;
import com.nagorikseba.enums.UserRole;
import com.nagorikseba.municipality.entity.Department;
import com.nagorikseba.municipality.entity.Municipality;
import com.nagorikseba.municipality.entity.Ward;
import com.nagorikseba.municipality.repository.DepartmentRepository;
import com.nagorikseba.municipality.repository.MunicipalityRepository;
import com.nagorikseba.municipality.repository.WardRepository;
import com.nagorikseba.repository.SlaRuleRepository;
import com.nagorikseba.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
                .password(passwordEncoder.encode("admin123"))
                .role(UserRole.ADMIN)
                .active(true)
                .build());

        // Seed Wards with PostGIS boundaries
        // Ward 17: Dhanmondi (Dhaka South) - approximate boundary
        Ward ward17 = wardRepository.save(Ward.builder()
                .municipality(dhakaSouth)
                .wardNumber(17)
                .areaName("Dhanmondi")
                .areaNameBn("ধানমন্ডি")
                .boundary(createPolygon(23.7400, 90.3700, 23.7500, 90.3800))
                .isActive(true)
                .build());

        // Ward 18: Gulshan (Dhaka North)
        Ward ward18 = wardRepository.save(Ward.builder()
                .municipality(dhakaNorth)
                .wardNumber(18)
                .areaName("Gulshan")
                .areaNameBn("গুলশান")
                .boundary(createPolygon(23.7900, 90.4050, 23.8050, 90.4200))
                .isActive(true)
                .build());

        // Ward 19: Banani (Dhaka North)
        Ward ward19 = wardRepository.save(Ward.builder()
                .municipality(dhakaNorth)
                .wardNumber(19)
                .areaName("Banani")
                .areaNameBn("বনানী")
                .boundary(createPolygon(23.7850, 90.3950, 23.7950, 90.4050))
                .isActive(true)
                .build());

        // Seed Councilors
        User councilor17 = userRepository.save(User.builder()
                .fullName("Councilor Ward 17")
                .email("councilor17@example.com")
                .phone("01700000017")
                .password(passwordEncoder.encode("councilor123"))
                .role(UserRole.WARD_COUNCILOR)
                .ward(ward17)
                .active(true)
                .build());

        // Seed Departments & Officers for Ward 17's municipality
        for (ComplaintCategory category : ComplaintCategory.values()) {
            Department dept = departmentRepository.save(Department.builder()
                    .municipality(dhakaSouth)
                    .code(category.name())
                    .name(category.name())
                    .handlesCategories(new String[]{category.name()})
                    .isActive(true)
                    .build());

            // Just create one officer for ROADS to keep it simple
            if (category == ComplaintCategory.ROADS) {
                userRepository.save(User.builder()
                        .fullName("Roads Officer 17")
                        .email("roads17@example.com")
                        .phone("01700100017")
                        .password(passwordEncoder.encode("officer123"))
                        .role(UserRole.DEPT_OFFICER)
                        .ward(ward17)
                        .department(dept)
                        .active(true)
                        .build());
            }
        }

        // Seed SLA Rules
        for (ComplaintCategory category : ComplaintCategory.values()) {
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

        log.info("Initial data successfully seeded!");
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