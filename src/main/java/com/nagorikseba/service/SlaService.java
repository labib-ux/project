package com.nagorikseba.service;

import com.nagorikseba.complaint.domain.enums.Category;
import com.nagorikseba.complaint.domain.enums.Priority;
import com.nagorikseba.entity.SlaRule;
import com.nagorikseba.repository.SlaRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class SlaService {

    private final SlaRuleRepository slaRuleRepository;
    private final Clock clock;

    /** When work on a complaint of this category and priority is due. */
    public Instant calculateDeadline(Category category, Priority priority) {
        SlaRule rule = slaRuleRepository.findByCategoryAndPriority(category, priority)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No SLA rule found for " + category + " / " + priority));
        return clock.instant().plus(Duration.ofHours(rule.getMaxHours()));
    }

    /**
     * Breach detection lands with the scanner in Phase 5 — deadlines are computed
     * here, but nothing yet scans for the ones that have passed.
     */
    public void checkSlaBreaches() {
        log.debug("SLA breach scanning is not enabled until Phase 5");
    }
}
