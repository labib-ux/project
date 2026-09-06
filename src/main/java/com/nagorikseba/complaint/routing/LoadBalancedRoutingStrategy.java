package com.nagorikseba.complaint.routing;

import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.repo.ComplaintAssignmentRepository;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.municipality.entity.Department;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Route by least officer load (C27, §7.2).
 *
 * <p>Considers every active officer posted to a department in the complaint's
 * municipality and picks the least-loaded one (ties → lowest officer id); the
 * department is the officer's current posting. Falls through (does not support)
 * when the municipality has no posted officers at all.
 */
@Component
public class LoadBalancedRoutingStrategy extends AbstractRoutingSupport {

    public static final String TYPE = "LOAD_BALANCED";

    public LoadBalancedRoutingStrategy(ComplaintAssignmentRepository assignmentRepository) {
        super(assignmentRepository);
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public boolean supports(Complaint complaint) {
        return complaint.getMunicipality() != null
                && !officersOfMunicipality(complaint.getMunicipality().getId()).isEmpty();
    }

    @Override
    public RoutingDecision route(Complaint complaint) {
        Long municipalityId = complaint.getMunicipality().getId();
        List<User> officers = officersOfMunicipality(municipalityId);
        if (officers.isEmpty()) {
            throw new NoEligibleDepartmentException(
                    "No active officers posted in this municipality");
        }
        User officer = leastLoaded(officers);
        Department department = postingDepartment(officer.getId(), municipalityId);
        if (department == null) {
            throw new NoEligibleDepartmentException(
                    "Officer " + officer.getId() + " has no current department posting");
        }
        String explanation = "least-loaded officer " + officer.getFullName() + " (id " + officer.getId()
                + ") with " + activeLoad(officer.getId()) + " active assignments"
                + " in department " + department.getCode() + " (id " + department.getId() + ")";
        return new RoutingDecision(department, officer, TYPE, explanation);
    }
}
