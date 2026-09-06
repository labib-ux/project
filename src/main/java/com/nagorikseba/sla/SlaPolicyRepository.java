package com.nagorikseba.sla;

import com.nagorikseba.complaint.domain.enums.Category;
import com.nagorikseba.complaint.domain.enums.Priority;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SlaPolicyRepository extends JpaRepository<SlaPolicy, Long> {

    Optional<SlaPolicy> findByMunicipalityIdAndCategoryAndPriorityAndActiveTrue(
            Long municipalityId, Category category, Priority priority);
}
