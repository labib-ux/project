package com.nagorikseba.service;

import com.nagorikseba.complaint.domain.enums.Category;
import com.nagorikseba.complaint.domain.enums.Priority;
import com.nagorikseba.entity.SlaRule;
import com.nagorikseba.repository.SlaRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class SlaService {
    private final SlaRuleRepository slaRuleRepository;

    public Instant calculateDeadline(Category category, Priority priority) {
        SlaRule rule = slaRuleRepository.findByCategoryAndPriority(category, priority)
                .orElseThrow(() -> new IllegalArgumentException("No SLA rule found for " + category + " / " + priority));
        return Instant.now().plusSeconds(rule.getMaxHours() * 3600L);
    }

    // SLA breach checking is deferred to Phase 5 (SlaBreachScanner)
    // This method is kept for compatibility but does nothing in Phase 3
    public void checkSlaBreaches() {
        log.debug("SLA breach checking deferred to Phase 5");
    }
}