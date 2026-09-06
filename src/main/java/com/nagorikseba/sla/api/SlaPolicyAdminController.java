package com.nagorikseba.sla.api;

import com.nagorikseba.complaint.domain.enums.Category;
import com.nagorikseba.complaint.domain.enums.Priority;
import com.nagorikseba.municipality.repository.MunicipalityRepository;
import com.nagorikseba.shared.exception.ResourceNotFoundException;
import com.nagorikseba.sla.SlaPolicy;
import com.nagorikseba.sla.SlaPolicyRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SLA policy administration: JSON APIs under {@code /api/admin} (ADMIN role
 * via the security chain) plus the Thymeleaf page under
 * {@code /admin/sla-policies}.
 *
 * <p>Policy edits never rewrite history: instances snapshot their deadline at
 * creation, so changes apply to subsequently calculated deadlines only.
 */
@Controller
@RequiredArgsConstructor
public class SlaPolicyAdminController {

    private final SlaPolicyRepository policyRepository;
    private final MunicipalityRepository municipalityRepository;

    @PersistenceContext
    private EntityManager entityManager;

    /** Admin page: policy table with max-hours and threshold editors. */
    @GetMapping("/admin/sla-policies")
    public String policiesPage(Model model) {
        model.addAttribute("policies", policyRepository.findAll());
        return "admin/sla-policies";
    }

    @GetMapping("/api/admin/sla-policies")
    @ResponseBody
    public List<Map<String, Object>> listPolicies(
            @RequestParam(required = false) Long municipalityId) {
        return policyRepository.findAll().stream()
                .filter(policy -> municipalityId == null
                        || policy.getMunicipality().getId().equals(municipalityId))
                .map(SlaPolicyAdminController::describe)
                .toList();
    }

    public record PolicyBody(Long municipalityId, String category, String priority,
                             Integer maxHours, Integer escalationLevel1Hours,
                             Integer escalationLevel2Hours, Boolean active) {
    }

    @PostMapping("/api/admin/sla-policies")
    @ResponseBody
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> createPolicy(@RequestBody PolicyBody body) {
        if (body.municipalityId() == null || body.maxHours() == null || body.maxHours() <= 0) {
            throw new IllegalArgumentException("municipalityId, category, priority and positive maxHours are required");
        }
        SlaPolicy saved = policyRepository.save(SlaPolicy.builder()
                .municipality(municipalityRepository.findById(body.municipalityId())
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "Municipality not found: " + body.municipalityId())))
                .category(parseCategory(body.category()))
                .priority(parsePriority(body.priority()))
                .maxHours(body.maxHours())
                .escalationLevel1Hours(body.escalationLevel1Hours())
                .escalationLevel2Hours(body.escalationLevel2Hours())
                .active(body.active() == null || body.active())
                .build());
        return describe(saved);
    }

    /**
     * Edit max hours, escalation thresholds and the active flag.
     *
     * <p>Applied as an explicit native UPDATE: the policy entity exposes no
     * setters (instances snapshot their deadlines, so in-place edits are an
     * admin-only escape hatch and stay out of the domain model). Edits affect
     * subsequently calculated deadlines only — never existing instances.
     */
    @PutMapping("/api/admin/sla-policies/{id}")
    @ResponseBody
    @Transactional
    public Map<String, Object> updatePolicy(@PathVariable Long id,
                                            @RequestBody PolicyBody body) {
        if (!policyRepository.existsById(id)) {
            throw new ResourceNotFoundException("SLA policy not found: " + id);
        }
        if (body.maxHours() != null && body.maxHours() <= 0) {
            throw new IllegalArgumentException("maxHours must be positive");
        }
        StringBuilder sql = new StringBuilder("UPDATE sla_policies SET ");
        List<String> sets = new java.util.ArrayList<>();
        if (body.maxHours() != null) {
            sets.add("max_hours = " + body.maxHours());
        }
        if (body.escalationLevel1Hours() != null) {
            sets.add("escalation_level_1_hours = " + body.escalationLevel1Hours());
        }
        if (body.escalationLevel2Hours() != null) {
            sets.add("escalation_level_2_hours = " + body.escalationLevel2Hours());
        }
        if (body.active() != null) {
            sets.add("is_active = " + body.active());
        }
        if (sets.isEmpty()) {
            throw new IllegalArgumentException("Nothing to update: send maxHours, thresholds or active");
        }
        sql.append(String.join(", ", sets)).append(" WHERE id = ").append(id);
        entityManager.createNativeQuery(sql.toString()).executeUpdate();
        entityManager.clear();
        return describe(policyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("SLA policy not found: " + id)));
    }

    private static Map<String, Object> describe(SlaPolicy policy) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", policy.getId());
        row.put("municipalityId", policy.getMunicipality().getId());
        row.put("category", policy.getCategory().name());
        row.put("priority", policy.getPriority().name());
        row.put("maxHours", policy.getMaxHours());
        row.put("escalationLevel1Hours", policy.getEscalationLevel1Hours());
        row.put("escalationLevel2Hours", policy.getEscalationLevel2Hours());
        row.put("active", policy.isActive());
        return row;
    }

    private static Category parseCategory(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("category is required");
        }
        try {
            return Category.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown category: " + raw);
        }
    }

    private static Priority parsePriority(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("priority is required");
        }
        try {
            return Priority.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown priority: " + raw);
        }
    }
}
