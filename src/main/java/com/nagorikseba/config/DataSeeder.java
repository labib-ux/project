package com.nagorikseba.config;

import com.nagorikseba.entity.Department;
import com.nagorikseba.entity.SlaRule;
import com.nagorikseba.entity.User;
import com.nagorikseba.entity.Ward;
import com.nagorikseba.enums.ComplaintCategory;
import com.nagorikseba.enums.Priority;
import com.nagorikseba.enums.UserRole;
import com.nagorikseba.repository.DepartmentRepository;
import com.nagorikseba.repository.SlaRuleRepository;
import com.nagorikseba.repository.UserRepository;
import com.nagorikseba.repository.WardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements CommandLineRunner {

        private final WardRepository wardRepository;
        private final UserRepository userRepository;
        private final DepartmentRepository departmentRepository;
        private final SlaRuleRepository slaRuleRepository;
        private final PasswordEncoder passwordEncoder;

        @Override
        @Transactional
        public void run(String... args) {
                if (wardRepository.count() > 0) {
                        log.info("Database already seeded. Skipping seed execution.");
                        return;
                }

                log.info("Seeding initial data...");

                // Seed Users - Admin
                User admin = userRepository.save(User.builder()
                                .fullName("System Admin")
                                .email("admin@example.com")
                                .phone("01700000001")
                                .password(passwordEncoder.encode("admin123"))
                                .role(UserRole.ADMIN)
                                .active(true)
                                .build());

                // Seed Wards
                // Ward 17: Covers Dhanmondi test coordinates (Lat: 23.7465, Lon: 90.3742)
                Ward ward17 = wardRepository.save(Ward.builder()
                                .wardNumber(17)
                                .areaName("Dhanmondi")
                                .cityCorporation("Dhaka South")
                                .minLatitude(new BigDecimal("23.74000000"))
                                .maxLatitude(new BigDecimal("23.75000000"))
                                .minLongitude(new BigDecimal("90.37000000"))
                                .maxLongitude(new BigDecimal("90.38000000"))
                                .build());

                Ward ward18 = wardRepository.save(Ward.builder()
                                .wardNumber(18)
                                .areaName("Gulshan")
                                .cityCorporation("Dhaka North")
                                .minLatitude(new BigDecimal("23.79000000"))
                                .maxLatitude(new BigDecimal("23.80500000"))
                                .minLongitude(new BigDecimal("90.40500000"))
                                .maxLongitude(new BigDecimal("90.42000000"))
                                .build());

                Ward ward19 = wardRepository.save(Ward.builder()
                                .wardNumber(19)
                                .areaName("Banani")
                                .cityCorporation("Dhaka North")
                                .minLatitude(new BigDecimal("23.78500000"))
                                .maxLatitude(new BigDecimal("23.79500000"))
                                .minLongitude(new BigDecimal("90.39500000"))
                                .maxLongitude(new BigDecimal("90.40500000"))
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
                ward17.setCouncilor(councilor17);
                wardRepository.save(ward17);

                // Seed Departments & Officers for Ward 17
                for (ComplaintCategory category : ComplaintCategory.values()) {
                        Department dept = departmentRepository.save(Department.builder()
                                        .name(category)
                                        .ward(ward17)
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
}
