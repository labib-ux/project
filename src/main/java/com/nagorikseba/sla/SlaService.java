package com.nagorikseba.sla;

import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.domain.enums.Category;
import com.nagorikseba.complaint.domain.enums.Priority;
import com.nagorikseba.repository.SlaRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * SLA deadline engine (L7, §3.4).
 *
 * <p>Explicit bean name: the legacy unused
 * {@code com.nagorikseba.service.SlaService} already occupies the default
 * {@code slaService} name, and touching it is out of Phase 5 scope.
 *
 * <p>Policy precedence: municipality policy → legacy global rule → configured
 * default hours (never throws for a missing policy — a missing row must not
 * crash submission or transition flows). Deadlines derive from
 * {@code complaint.submittedAt}, never from the current instant, so historical
 * fixtures and backfilled rows get honest deadlines.
 */
@Service("slaEngine")
@RequiredArgsConstructor
@Slf4j
public class SlaService {

    private final SlaPolicyRepository policyRepository;
    private final SlaInstanceRepository instanceRepository;
    private final SlaRuleRepository legacyRuleRepository;
    private final Clock clock;

    /** Fallback hours when neither a policy nor a legacy rule matches. */
    @Value("${app.sla.default-hours:120}")
    private int defaultHours;

    /** Policy hours for a complaint's municipality/category/priority, if any. */
    public Optional<Integer> policyHours(Complaint complaint) {
        if (complaint.getMunicipality() == null) {
            return Optional.empty();
        }
        return policyRepository
                .findByMunicipalityIdAndCategoryAndPriorityAndActiveTrue(
                        complaint.getMunicipality().getId(),
                        complaint.getCategory(), complaint.getPriority())
                .map(SlaPolicy::getMaxHours);
    }

    /** Resolved hours with legacy + default fallbacks (logs at WARN past policy). */
    public int resolveHours(Complaint complaint) {
        Optional<Integer> policy = policyHours(complaint);
        if (policy.isPresent()) {
            return policy.get();
        }
        Optional<Integer> legacy = legacyRuleRepository
                .findByCategoryAndPriority(complaint.getCategory(), complaint.getPriority())
                .map(rule -> rule.getMaxHours());
        if (legacy.isPresent()) {
            return legacy.get();
        }
        log.warn("No SLA policy or rule for {}/{}; falling back to {}h default",
                complaint.getCategory(), complaint.getPriority(), defaultHours);
        return defaultHours;
    }

    /** Deadline anchored at submission, not at now. */
    public Instant calculateDeadline(Complaint complaint) {
        return calculateDeadline(complaint, resolveHours(complaint));
    }

    public Instant calculateDeadline(Complaint complaint, int hours) {
        return complaint.getSubmittedAt().plus(Duration.ofHours(hours));
    }

    /**
     * Find-or-create the instance for a complaint (idempotent; the unique
     * constraint on complaint backs it under races).
     */
    @Transactional
    public SlaInstance ensureInstance(Complaint complaint) {
        return instanceRepository.findByComplaintId(complaint.getId())
                .orElseGet(() -> instanceRepository.save(SlaInstance.builder()
                        .complaint(complaint)
                        .policy(activePolicyOrNull(complaint))
                        .deadlineAt(calculateDeadline(complaint))
                        .lastCalculatedAt(clock.instant())
                        .build()));
    }

    /**
     * Reopen rule (§6): fresh deadline of now + 50% of the (current) policy
     * hours. Priority is already HIGH by the time the handler calls this.
     */
    @Transactional
    public SlaInstance recalculateForReopen(Complaint complaint) {
        SlaInstance instance = ensureInstance(complaint);
        int halved = Math.max(1, resolveHours(complaint) / 2);
        Instant now = clock.instant();
        instance.setPolicy(activePolicyOrNull(complaint));
        instance.setDeadlineAt(now.plus(Duration.ofHours(halved)));
        instance.setLastCalculatedAt(now);
        return instanceRepository.save(instance);
    }

    /** Priority-change rule: recompute from submission with current hours. */
    @Transactional
    public SlaInstance recalculateForPriority(Complaint complaint) {
        SlaInstance instance = ensureInstance(complaint);
        instance.setPolicy(activePolicyOrNull(complaint));
        instance.setDeadlineAt(calculateDeadline(complaint));
        instance.setLastCalculatedAt(clock.instant());
        return instanceRepository.save(instance);
    }

    public Optional<SlaInstance> findInstance(Long complaintId) {
        return instanceRepository.findByComplaintId(complaintId);
    }

    private SlaPolicy activePolicyOrNull(Complaint complaint) {
        if (complaint.getMunicipality() == null) {
            return null;
        }
        return policyRepository
                .findByMunicipalityIdAndCategoryAndPriorityAndActiveTrue(
                        complaint.getMunicipality().getId(),
                        complaint.getCategory(), complaint.getPriority())
                .orElse(null);
    }

    /** For tests: the legacy rule hours for a category/priority pair. */
    int legacyHours(Category category, Priority priority) {
        return legacyRuleRepository.findByCategoryAndPriority(category, priority)
                .map(rule -> rule.getMaxHours()).orElse(defaultHours);
    }
}
