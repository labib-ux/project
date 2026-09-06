package com.nagorikseba.complaint.api;

import com.nagorikseba.complaint.api.dto.ComplaintResponse;
import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.domain.enums.ComplaintAction;
import com.nagorikseba.complaint.domain.enums.ComplaintStatus;
import com.nagorikseba.complaint.lifecycle.ComplaintLifecycleService;
import com.nagorikseba.complaint.lifecycle.TransitionCommand;
import com.nagorikseba.complaint.repo.ComplaintRepository;
import com.nagorikseba.complaint.service.ComplaintQueryService;
import com.nagorikseba.entity.SlaRule;
import com.nagorikseba.enums.UserRole;
import com.nagorikseba.identity.domain.UserMunicipalityMembership;
import com.nagorikseba.identity.repo.MembershipRepository;
import com.nagorikseba.municipality.entity.Ward;
import com.nagorikseba.municipality.repository.WardRepository;
import com.nagorikseba.repository.SlaRuleRepository;
import com.nagorikseba.shared.exception.ResourceNotFoundException;
import com.nagorikseba.shared.security.AuthenticatedUser;
import com.nagorikseba.shared.security.PrincipalContext;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Authority-side complaint workflows (§6, §8.2, Phase 4).
 *
 * <p>Role access is enforced by the filter chain ({@code /api/authority/**} →
 * WARD_COUNCILOR, DEPT_OFFICER, ADMIN). Tenancy is enforced here per call:
 * holding an authority role is not the same as serving the municipality the
 * complaint belongs to, and without the second check any officer anywhere could
 * verify any complaint in the country.
 *
 * <p>All action endpoints live under {@code /api/authority/complaints/...} —
 * not under {@code /api/complaints/...}, whose chain only admits CITIZEN and
 * ADMIN and would answer 403 to an officer token.
 *
 * <p>Phase 4 exposes VERIFY, REJECT, ASSIGN (manual + auto) and START.
 * RESOLVE, CLOSE and REOPEN have no handler in this build and answer 422;
 * their endpoints land with their handlers in Phase 5.
 */
@RestController
@RequestMapping("/api/authority")
@RequiredArgsConstructor
public class AuthorityComplaintController {

    /** Default queue: every non-terminal status an officer can act on. */
    private static final List<ComplaintStatus> DEFAULT_QUEUE_STATUSES = List.of(
            ComplaintStatus.VERIFIED, ComplaintStatus.ASSIGNED,
            ComplaintStatus.IN_PROGRESS, ComplaintStatus.REOPENED);

    /** Fallback policy hours when no SLA rule matches (Phase 5 owns real policies). */
    private static final int FALLBACK_SLA_HOURS = 120;

    private final ComplaintLifecycleService lifecycleService;
    private final ComplaintQueryService queryService;
    private final ComplaintRepository complaintRepository;
    private final MembershipRepository membershipRepository;
    private final WardRepository wardRepository;
    private final SlaRuleRepository slaRuleRepository;
    private final PrincipalContext principalContext;
    private final Clock clock;

    /**
     * Who the caller is, what they are posted to, and their municipality's
     * aggregates.
     *
     * <p>Deliberately claim-based plus membership reads inside one read-only
     * transaction — the Phase 2 handoff records a
     * {@code LazyInitializationException} here from dereferencing lazy
     * associations outside a transaction.
     *
     * @param municipalityId optional when the caller serves exactly one
     *                       municipality; required when they serve several
     */
    @GetMapping("/dashboard")
    @Transactional(readOnly = true)
    public Map<String, Object> dashboard(
            @RequestParam(required = false) Long municipalityId) {
        AuthenticatedUser principal = principalContext.requireUser();
        List<UserMunicipalityMembership> memberships =
                membershipRepository.findByUserIdAndValidUntilIsNull(principal.id());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", principal.id());
        body.put("role", principal.role().name());
        body.put("municipalityIds", principal.municipalityIds());
        body.put("postings", memberships.stream().map(this::describePosting).toList());

        Long municipality = resolveMunicipality(principal, memberships, municipalityId);
        body.put("municipalityId", municipality);
        body.put("wardStats", wardStats(municipality, visibleWardIds(principal, memberships)));
        body.put("averageResolutionHours",
                complaintRepository.averageResolutionHoursByMunicipality(municipality));
        body.put("slaAtRiskCount", slaAtRiskCount(municipality));
        return body;
    }

    /**
     * Department work queue: active complaints of one municipality, filterable
     * by status and scoped to what the caller may see — officers to their
     * department (assigned, or VERIFIED work their department handles),
     * councilors to their wards, admins to everything.
     */
    @GetMapping("/queue")
    @Transactional(readOnly = true)
    public List<ComplaintResponse> queue(
            @RequestParam Long municipalityId,
            @RequestParam(required = false) List<String> status) {
        AuthenticatedUser principal = principalContext.requireUser();
        principalContext.requireMunicipality(municipalityId);

        List<ComplaintStatus> statuses = status == null || status.isEmpty()
                ? DEFAULT_QUEUE_STATUSES
                : status.stream().map(this::parseStatus).toList();

        List<Complaint> complaints =
                complaintRepository.findByMunicipalityIdAndStatusIn(municipalityId, statuses);

        List<UserMunicipalityMembership> memberships =
                membershipRepository.findByUserIdAndValidUntilIsNull(principal.id());
        List<Complaint> visible = applyQueueScope(principal, memberships, complaints);
        return visible.stream().map(queryService::describe).toList();
    }

    /** SUBMITTED → VERIFIED. The note is optional; it is recorded on the audit row. */
    @PostMapping("/complaints/{referenceCode}/verify")
    public ComplaintResponse verify(
            @PathVariable String referenceCode,
            @RequestParam(required = false) String note,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return act(ComplaintAction.VERIFY, referenceCode, note, idempotencyKey);
    }

    /** SUBMITTED → REJECTED. The reason is mandatory — a rejection a citizen cannot read is not one. */
    @PostMapping("/complaints/{referenceCode}/reject")
    public ComplaintResponse reject(
            @PathVariable String referenceCode,
            @RequestParam String reason,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return act(ComplaintAction.REJECT, referenceCode, reason, idempotencyKey);
    }

    /**
     * VERIFIED | REOPENED → ASSIGNED, manual path: the caller picks the
     * department and optionally the officer; §6 guards run in the handler.
     */
    @PostMapping("/complaints/{referenceCode}/assign")
    public ComplaintResponse assign(
            @PathVariable String referenceCode,
            @RequestParam Long departmentId,
            @RequestParam(required = false) Long officerId,
            @RequestParam(required = false) String note,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        Complaint complaint = loadInMunicipality(referenceCode);
        TransitionCommand command = TransitionCommand.ofAssignment(
                ComplaintAction.ASSIGN, complaint.getId(), principalContext.requireUserId(),
                note, departmentId, officerId, idempotencyKey, complaint.getVersion());
        return queryService.describe(lifecycleService.execute(command));
    }

    /**
     * VERIFIED | REOPENED → ASSIGNED, auto path: the routing resolver picks
     * department and officer; strategy and explanation are audited on the
     * assignment row and the transition metadata.
     */
    @PostMapping("/complaints/{referenceCode}/assign/auto")
    public ComplaintResponse autoAssign(
            @PathVariable String referenceCode,
            @RequestParam(required = false) String note,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        Complaint complaint = loadInMunicipality(referenceCode);
        TransitionCommand command = TransitionCommand.of(
                ComplaintAction.ASSIGN, complaint.getId(), principalContext.requireUserId(),
                note, idempotencyKey, complaint.getVersion());
        return queryService.describe(lifecycleService.execute(command));
    }

    /** ASSIGNED → IN_PROGRESS. Only the assigned officer (or an admin) may start. */
    @PostMapping("/complaints/{referenceCode}/start")
    public ComplaintResponse start(
            @PathVariable String referenceCode,
            @RequestParam(required = false) String note,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return act(ComplaintAction.START, referenceCode, note, idempotencyKey);
    }

    // ------------------------------------------------------------------ internals

    private Complaint loadInMunicipality(String referenceCode) {
        Complaint complaint = complaintRepository.findByReferenceCode(referenceCode)
                .orElseThrow(() -> new ResourceNotFoundException("Complaint not found: " + referenceCode));
        principalContext.requireMunicipality(complaint.getMunicipality().getId());
        return complaint;
    }

    private ComplaintResponse act(ComplaintAction action, String referenceCode,
                                  String note, String idempotencyKey) {
        Complaint complaint = loadInMunicipality(referenceCode);
        TransitionCommand command = TransitionCommand.of(
                action,
                complaint.getId(),
                principalContext.requireUserId(),
                note,
                idempotencyKey,
                complaint.getVersion());
        return queryService.describe(lifecycleService.execute(command));
    }

    private Map<String, Object> describePosting(UserMunicipalityMembership membership) {
        Map<String, Object> posting = new LinkedHashMap<>();
        posting.put("municipalityId", membership.getMunicipality().getId());
        posting.put("municipalityName", membership.getMunicipality().getName());
        posting.put("wardId", membership.getWard() != null ? membership.getWard().getId() : null);
        posting.put("wardName", membership.getWard() != null ? membership.getWard().getAreaName() : null);
        posting.put("departmentName", membership.getDepartment() != null ? membership.getDepartment().getName() : null);
        posting.put("validFrom", membership.getValidFrom());
        return posting;
    }

    private Long resolveMunicipality(AuthenticatedUser principal,
                                     List<UserMunicipalityMembership> memberships,
                                     Long requested) {
        if (requested != null) {
            principalContext.requireMunicipality(requested);
            return requested;
        }
        Set<Long> served = memberships.stream()
                .map(membership -> membership.getMunicipality().getId())
                .collect(Collectors.toSet());
        if (served.size() == 1) {
            return served.iterator().next();
        }
        if (principal.isAdmin() && served.isEmpty()) {
            throw new IllegalArgumentException(
                    "Pass municipalityId: this account serves no single municipality");
        }
        throw new IllegalArgumentException(
                "Pass municipalityId: this account serves several municipalities");
    }

    /** Wards the caller may see aggregate counts for; null means the whole municipality. */
    private Set<Long> visibleWardIds(AuthenticatedUser principal,
                                     List<UserMunicipalityMembership> memberships) {
        if (principal.role() != UserRole.WARD_COUNCILOR) {
            return null;
        }
        Set<Long> wards = memberships.stream()
                .filter(membership -> membership.getWard() != null)
                .map(membership -> membership.getWard().getId())
                .collect(Collectors.toSet());
        return wards.isEmpty() ? Set.of(-1L) : wards;
    }

    private List<Map<String, Object>> wardStats(Long municipalityId, Set<Long> visibleWardIds) {
        Map<Long, Ward> wards = wardRepository
                .findByMunicipalityIdAndIsActiveTrueOrderByWardNumberAsc(municipalityId).stream()
                .collect(Collectors.toMap(Ward::getId, ward -> ward));
        Map<Long, Map<ComplaintStatus, Long>> counts = new LinkedHashMap<>();
        for (Object[] row : complaintRepository.countByMunicipalityGroupByWardAndStatus(municipalityId)) {
            Long wardId = row[0] != null ? ((Number) row[0]).longValue() : null;
            ComplaintStatus status = (ComplaintStatus) row[1];
            long count = ((Number) row[2]).longValue();
            counts.computeIfAbsent(wardId, key -> new EnumMap<>(ComplaintStatus.class))
                    .merge(status, count, Long::sum);
        }
        List<Map<String, Object>> stats = new ArrayList<>();
        for (Map.Entry<Long, Map<ComplaintStatus, Long>> entry : counts.entrySet()) {
            Long wardId = entry.getKey();
            if (visibleWardIds != null && (wardId == null || !visibleWardIds.contains(wardId))) {
                continue;
            }
            Ward ward = wardId != null ? wards.get(wardId) : null;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("wardId", wardId);
            row.put("wardNumber", ward != null ? ward.getWardNumber() : null);
            row.put("wardName", ward != null ? ward.getAreaName() : "Outside ward boundaries");
            Map<String, Long> byStatus = new TreeMap<>();
            entry.getValue().forEach((status, count) -> byStatus.put(status.name(), count));
            row.put("counts", byStatus);
            stats.add(row);
        }
        return stats;
    }

    /**
     * Phase-4 SLA-at-risk approximation: active complaints whose
     * {@code submittedAt + policy hours} falls within the next 24 hours (or is
     * already past). Phase 5 replaces the policy lookup with per-complaint
     * {@code sla_instances} rows; the counting rule stays the same.
     */
    private long slaAtRiskCount(Long municipalityId) {
        Map<String, Integer> policyHours = new LinkedHashMap<>();
        for (SlaRule rule : slaRuleRepository.findAll()) {
            policyHours.putIfAbsent(
                    rule.getCategory().name() + "|" + rule.getPriority().name(), rule.getMaxHours());
        }
        Instant horizon = clock.instant().plusSeconds(24 * 60 * 60);
        return complaintRepository.findByMunicipalityIdAndStatusIn(municipalityId, DEFAULT_QUEUE_STATUSES)
                .stream()
                .filter(complaint -> {
                    int hours = policyHours.getOrDefault(
                            complaint.getCategory().name() + "|" + complaint.getPriority().name(),
                            FALLBACK_SLA_HOURS);
                    return !complaint.getSubmittedAt().plusSeconds((long) hours * 3600).isAfter(horizon);
                })
                .count();
    }

    private List<Complaint> applyQueueScope(AuthenticatedUser principal,
                                            List<UserMunicipalityMembership> memberships,
                                            List<Complaint> complaints) {
        if (principal.isAdmin()) {
            return complaints;
        }
        if (principal.role() == UserRole.WARD_COUNCILOR) {
            Set<Long> wards = memberships.stream()
                    .filter(membership -> membership.getWard() != null)
                    .map(membership -> membership.getWard().getId())
                    .collect(Collectors.toSet());
            return complaints.stream()
                    .filter(complaint -> complaint.getWard() != null
                            && wards.contains(complaint.getWard().getId()))
                    .toList();
        }
        Set<Long> departments = memberships.stream()
                .filter(membership -> membership.getDepartment() != null)
                .map(membership -> membership.getDepartment().getId())
                .collect(Collectors.toSet());
        if (departments.isEmpty()) {
            return List.of();
        }
        return complaints.stream()
                .filter(complaint -> complaint.getAssignedDepartment() != null
                        ? departments.contains(complaint.getAssignedDepartment().getId())
                        : complaint.getStatus() == ComplaintStatus.VERIFIED
                        && handlesIn(complaint, departments, memberships))
                .toList();
    }

    private boolean handlesIn(Complaint complaint, Set<Long> departments,
                              List<UserMunicipalityMembership> memberships) {
        return memberships.stream()
                .filter(membership -> membership.getDepartment() != null
                        && departments.contains(membership.getDepartment().getId()))
                .map(membership -> membership.getDepartment().getHandlesCategories())
                .filter(categories -> categories != null)
                .anyMatch(categories -> List.of(categories).contains(complaint.getCategory().name()));
    }

    private ComplaintStatus parseStatus(String raw) {
        try {
            return ComplaintStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown status filter: " + raw);
        }
    }
}
