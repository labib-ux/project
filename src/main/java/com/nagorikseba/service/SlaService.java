package com.nagorikseba.service;

import com.nagorikseba.entity.Complaint;
import com.nagorikseba.entity.SlaRule;
import com.nagorikseba.enums.ComplaintCategory;
import com.nagorikseba.enums.ComplaintStatus;
import com.nagorikseba.enums.Priority;
import com.nagorikseba.repository.ComplaintRepository;
import com.nagorikseba.repository.SlaRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SlaService {
    private final SlaRuleRepository slaRuleRepository;
    private final ComplaintRepository complaintRepository;

    public LocalDateTime calculateDeadline(ComplaintCategory category, Priority priority) {
        SlaRule rule = slaRuleRepository.findByCategoryAndPriority(category, priority)
                .orElseThrow(() -> new IllegalArgumentException("No SLA rule found for " + category + " / " + priority));
        return LocalDateTime.now().plusHours(rule.getMaxHours());
    }

    @Scheduled(fixedRate = 3600000) // Runs every hour
    @Transactional
    public void checkSlaBreaches() {
        log.info("Checking for SLA breaches...");
        List<Complaint> breached = complaintRepository.findByStatusNotInAndDeadlineAtBefore(
                List.of(ComplaintStatus.CLOSED, ComplaintStatus.RESOLVED),
                LocalDateTime.now()
        );
        
        for (Complaint complaint : breached) {
            log.warn("SLA breached for complaint ID: {}. Escalating...", complaint.getId());
            if (complaint.getPriority() != Priority.CRITICAL) {
                complaint.setPriority(Priority.CRITICAL);
                complaintRepository.save(complaint);
            }
        }
    }
}
