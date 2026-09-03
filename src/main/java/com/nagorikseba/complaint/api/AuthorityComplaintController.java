package com.nagorikseba.complaint.api;

import com.nagorikseba.complaint.api.dto.ComplaintResponse;
import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.domain.enums.ComplaintAction;
import com.nagorikseba.complaint.lifecycle.ComplaintLifecycleService;
import com.nagorikseba.complaint.lifecycle.TransitionCommand;
import com.nagorikseba.complaint.repo.ComplaintRepository;
import com.nagorikseba.complaint.service.ComplaintQueryService;
import com.nagorikseba.identity.domain.UserMunicipalityMembership;
import com.nagorikseba.identity.repo.MembershipRepository;
import com.nagorikseba.shared.exception.ResourceNotFoundException;
import com.nagorikseba.shared.security.AuthenticatedUser;
import com.nagorikseba.shared.security.PrincipalContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Authority-side complaint actions (§9.2).
 *
 * <p>Role access is enforced by the filter chain ({@code /api/authority/**} →
 * WARD_COUNCILOR, DEPT_OFFICER, ADMIN). Tenancy is enforced here per complaint:
 * holding an authority role is not the same as serving the municipality the
 * complaint belongs to, and without the second check any officer anywhere could
 * verify any complaint in the country.
 *
 * <p>Only VERIFY and REJECT are exposed. ASSIGN, START, RESOLVE, CLOSE and REOPEN
 * have no handler in this build and would answer 422; their endpoints land with
 * their handlers in Phases 4 and 5 rather than shipping as 422-returning stubs.
 */
@RestController
@RequestMapping("/api/authority")
@RequiredArgsConstructor
public class AuthorityComplaintController {

    private final ComplaintLifecycleService lifecycleService;
    private final ComplaintQueryService queryService;
    private final ComplaintRepository complaintRepository;
    private final MembershipRepository membershipRepository;
    private final PrincipalContext principalContext;

    /**
     * Who the caller is and what they are posted to.
     *
     * <p>Deliberately claim-based plus one membership read, and it dereferences no
     * lazy association outside a transaction — the Phase 2 handoff records a
     * {@code LazyInitializationException} here from doing exactly that.
     */
    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        AuthenticatedUser principal = principalContext.requireUser();
        List<UserMunicipalityMembership> memberships =
                membershipRepository.findByUserIdAndValidUntilIsNull(principal.id());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", principal.id());
        body.put("role", principal.role().name());
        body.put("municipalityIds", principal.municipalityIds());
        body.put("postings", memberships.stream().map(this::describePosting).toList());
        return body;
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

    private ComplaintResponse act(ComplaintAction action, String referenceCode,
                                  String note, String idempotencyKey) {
        Complaint complaint = complaintRepository.findByReferenceCode(referenceCode)
                .orElseThrow(() -> new ResourceNotFoundException("Complaint not found: " + referenceCode));

        principalContext.requireMunicipality(complaint.getMunicipality().getId());

        TransitionCommand command = TransitionCommand.of(
                action,
                complaint.getId(),
                principalContext.requireUserId(),
                note,
                idempotencyKey,
                complaint.getVersion());

        return queryService.describe(lifecycleService.execute(command));
    }
}
