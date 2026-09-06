package com.nagorikseba.complaint.routing;

import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.repo.ComplaintAssignmentRepository;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.municipality.entity.Department;
import com.nagorikseba.municipality.repository.DepartmentRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Route by category handling (C26, §7.2).
 *
 * <p>Picks the lowest-id active department whose {@code handles_categories}
 * contains the complaint's category, then its least-loaded posted officer (if
 * the department currently has no officer, the assignment is dept-only and the
 * explanation says so). Deterministic: lowest department id, then lowest load,
 * then lowest officer id.
 */
@Component
public class CategoryRoutingStrategy extends AbstractRoutingSupport {

    public static final String TYPE = "CATEGORY";

    private final DepartmentRepository departmentRepository;

    public CategoryRoutingStrategy(ComplaintAssignmentRepository assignmentRepository,
                                   DepartmentRepository departmentRepository) {
        super(assignmentRepository);
        this.departmentRepository = departmentRepository;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public boolean supports(Complaint complaint) {
        return complaint.getMunicipality() != null && complaint.getCategory() != null
                && !handlingDepartments(complaint).isEmpty();
    }

    @Override
    public RoutingDecision route(Complaint complaint) {
        List<Department> candidates = handlingDepartments(complaint);
        if (candidates.isEmpty()) {
            throw new NoEligibleDepartmentException(
                    "No active department handles category " + complaint.getCategory()
                            + " in this municipality");
        }
        Department department = candidates.stream()
                .min((left, right) -> Long.compare(left.getId(), right.getId()))
                .orElseThrow();
        User officer = leastLoaded(officersOfDepartment(department.getId()));
        String explanation = officer != null
                ? "matched department " + department.getCode() + " (id " + department.getId()
                + ") for category " + complaint.getCategory()
                + "; officer " + officer.getFullName() + " (id " + officer.getId()
                + ") has the least load with " + activeLoad(officer.getId()) + " active assignments"
                : "matched department " + department.getCode() + " (id " + department.getId()
                + ") for category " + complaint.getCategory()
                + "; no officer currently posted, dept-only assignment";
        return new RoutingDecision(department, officer, TYPE, explanation);
    }

    private List<Department> handlingDepartments(Complaint complaint) {
        return departmentRepository.findByMunicipalityIdAndHandlesCategory(
                complaint.getMunicipality().getId(), complaint.getCategory().name());
    }
}
