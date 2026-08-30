package com.nagorikseba.repository;

import com.nagorikseba.entity.SlaRule;
import com.nagorikseba.enums.ComplaintCategory;
import com.nagorikseba.enums.Priority;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SlaRuleRepository extends JpaRepository<SlaRule, Long> {
    Optional<SlaRule> findByCategoryAndPriority(ComplaintCategory category, Priority priority);
}
