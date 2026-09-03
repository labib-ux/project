package com.nagorikseba.repository;

import com.nagorikseba.complaint.domain.enums.Category;
import com.nagorikseba.complaint.domain.enums.Priority;
import com.nagorikseba.entity.SlaRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SlaRuleRepository extends JpaRepository<SlaRule, Long> {

    Optional<SlaRule> findByCategoryAndPriority(Category category, Priority priority);
}
