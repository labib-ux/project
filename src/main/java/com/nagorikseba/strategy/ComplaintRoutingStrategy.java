package com.nagorikseba.strategy;

import com.nagorikseba.entity.Complaint;
import com.nagorikseba.municipality.entity.Department;
import java.util.List;

public interface ComplaintRoutingStrategy {
    Department route(Complaint complaint, List<Department> availableDepartments);
    String getStrategyName();
}
