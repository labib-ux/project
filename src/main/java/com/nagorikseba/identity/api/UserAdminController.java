package com.nagorikseba.identity.api;

import com.nagorikseba.enums.UserRole;
import com.nagorikseba.identity.api.dto.UserResponse;
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
import com.nagorikseba.shared.config.DataRetentionJob;
import com.nagorikseba.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * User administration: JSON APIs under {@code /api/admin} (ADMIN role via the
 * security chain) plus the Thymeleaf page under {@code /admin/users}.
 *
 * <p>Deletion is anonymizing, never hard: the account is deactivated and its
 * complaints lose the owner link (keeping a SHA-256 dedup hash), so public
 * records survive while the person disappears (§9.4).
 */
@Controller
@RequiredArgsConstructor
public class UserAdminController {

    private final UserRepository userRepository;
    private final MembershipRepository membershipRepository;
    private final MunicipalityRepository municipalityRepository;
    private final WardRepository wardRepository;
    private final DepartmentRepository departmentRepository;
    private final DataRetentionJob retentionJob;
    private final Clock clock;

    /** Admin page: user table with activate/deactivate and membership forms. */
    @GetMapping("/admin/users")
    public String usersPage(Model model) {
        model.addAttribute("users", userRepository.findAll());
        return "admin/users";
    }

    @GetMapping("/api/admin/users")
    @ResponseBody
    public List<Map<String, Object>> listUsers() {
        return userRepository.findAll().stream().map(user -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", user.getId());
            row.put("fullName", user.getFullName());
            row.put("email", user.getEmail());
            row.put("phone", user.getPhone());
            row.put("role", user.getRole().name());
            row.put("active", user.isActive());
            row.put("municipalityIds",
                    membershipRepository.findCurrentMunicipalityIds(user.getId()));
            return row;
        }).toList();
    }

    public record ActiveBody(Boolean active) {
    }

    /** Activate or deactivate an account (deactivation keeps history). */
    @PatchMapping("/api/admin/users/{id}/active")
    @ResponseBody
    @Transactional
    public UserResponse setActive(@PathVariable Long id, @RequestBody ActiveBody body) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
        if (body.active() == null) {
            throw new IllegalArgumentException("active is required");
        }
        user.setActive(body.active());
        User saved = userRepository.save(user);
        return toResponse(saved);
    }

    public record MembershipBody(Long municipalityId, Long wardId, Long departmentId) {
    }

    /** Post a user to a municipality (optionally ward/department scoped). */
    @PostMapping("/api/admin/users/{id}/memberships")
    @ResponseBody
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> assignMembership(@PathVariable Long id,
                                                @RequestBody MembershipBody body) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
        Municipality municipality = municipalityRepository.findById(body.municipalityId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Municipality not found: " + body.municipalityId()));
        Ward ward = null;
        if (body.wardId() != null) {
            ward = wardRepository.findById(body.wardId())
                    .orElseThrow(() -> new ResourceNotFoundException("Ward not found: " + body.wardId()));
            if (!ward.getMunicipality().getId().equals(municipality.getId())) {
                throw new IllegalArgumentException("Ward does not belong to the municipality");
            }
        }
        Department department = null;
        if (body.departmentId() != null) {
            department = departmentRepository.findById(body.departmentId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Department not found: " + body.departmentId()));
            if (!department.getMunicipality().getId().equals(municipality.getId())) {
                throw new IllegalArgumentException("Department does not belong to the municipality");
            }
        }
        UserMunicipalityMembership saved = membershipRepository.save(
                UserMunicipalityMembership.builder()
                        .user(user)
                        .municipality(municipality)
                        .ward(ward)
                        .department(department)
                        .validFrom(clock.instant())
                        .build());
        Map<String, Object> body2 = new LinkedHashMap<>();
        body2.put("id", saved.getId());
        body2.put("userId", user.getId());
        body2.put("municipalityId", municipality.getId());
        return body2;
    }

    /**
     * Delete a user: deactivate the account and anonymize their complaints
     * (owner link cleared, SHA-256 dedup hash kept). Returns complaints
     * anonymized.
     */
    @DeleteMapping("/api/admin/users/{id}")
    @ResponseBody
    @Transactional
    public Map<String, Object> deleteUser(@PathVariable Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
        if (user.getRole() == UserRole.ADMIN) {
            throw new IllegalArgumentException("Admin accounts cannot be deleted through the API");
        }
        int anonymized = retentionJob.anonymizeComplaintsOf(id);
        user.setActive(false);
        userRepository.save(user);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", id);
        result.put("deactivated", true);
        result.put("complaintsAnonymized", anonymized);
        return result;
    }

    private UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getFullName(), user.getEmail(),
                user.getPhone(), user.getRole(),
                new java.util.HashSet<>(membershipRepository.findCurrentMunicipalityIds(user.getId())));
    }
}
