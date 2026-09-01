package com.nagorikseba.strategy;

import com.nagorikseba.entity.Complaint;
import com.nagorikseba.municipality.entity.Department;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CategoryRoutingStrategy implements ComplaintRoutingStrategy {
    @Override
    public Department route(Complaint complaint, List<Department> departments) {
        return departments.stream()
                .filter(d -> d.getCode().equals(complaint.getCategory().name()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No matching department found for category: " + complaint.getCategory()));
    }

    @Override
    public String getStrategyName() {
        return "CATEGORY_ROUTING";
    }
}
