package com.nagorikseba.complaint.routing;

import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.repo.ComplaintAssignmentRepository;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.municipality.entity.Department;
import com.nagorikseba.municipality.repository.DepartmentRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Route by nearest department office (C28, §7.2).
 *
 * <p>Orders active departments with a set {@code office_location} by
 * {@code ST_Distance} on geography and picks the nearest, then its least-loaded
 * posted officer (dept-only when none). Falls through when the complaint has no
 * location or no department in the municipality has an office location — both
 * are normal in Phase 4 seed data, so this strategy is last by default.
 */
@Component
public class DistanceBasedRoutingStrategy extends AbstractRoutingSupport {

    public static final String TYPE = "DISTANCE";

    private final DepartmentRepository departmentRepository;

    public DistanceBasedRoutingStrategy(ComplaintAssignmentRepository assignmentRepository,
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
        return complaint.getMunicipality() != null && complaint.getLocation() != null
                && !locatedDepartments(complaint.getMunicipality().getId()).isEmpty();
    }

    @Override
    @SuppressWarnings("unchecked")
    public RoutingDecision route(Complaint complaint) {
        Long municipalityId = complaint.getMunicipality().getId();
        double lng = complaint.getLocation().getX();
        double lat = complaint.getLocation().getY();
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT d.id, ST_Distance(d.office_location,
                    ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) AS dist_m
                FROM departments d
                WHERE d.municipality_id = :municipalityId
                  AND d.is_active = true
                  AND d.office_location IS NOT NULL
                ORDER BY dist_m ASC, d.id ASC
                LIMIT 1
                """)
                .setParameter("municipalityId", municipalityId)
                .setParameter("lng", lng)
                .setParameter("lat", lat)
                .getResultList();
        if (rows.isEmpty()) {
            throw new NoEligibleDepartmentException(
                    "No department office location set in this municipality");
        }
        Long departmentId = ((Number) rows.get(0)[0]).longValue();
        double km = ((Number) rows.get(0)[1]).doubleValue() / 1000.0;
        Department department = departmentRepository.findById(departmentId)
                .orElseThrow(() -> new NoEligibleDepartmentException(
                        "Nearest department " + departmentId + " no longer exists"));
        User officer = leastLoaded(officersOfDepartment(departmentId));
        String explanation = officer != null
                ? "nearest department " + department.getCode() + " (id " + department.getId()
                + ") at " + String.format("%.2f", km) + " km"
                + "; officer " + officer.getFullName() + " (id " + officer.getId()
                + ") has the least load with " + activeLoad(officer.getId()) + " active assignments"
                : "nearest department " + department.getCode() + " (id " + department.getId()
                + ") at " + String.format("%.2f", km) + " km"
                + "; no officer currently posted, dept-only assignment";
        return new RoutingDecision(department, officer, TYPE, explanation);
    }

    private List<Department> locatedDepartments(Long municipalityId) {
        return departmentRepository.findByMunicipalityIdAndIsActiveTrue(municipalityId).stream()
                .filter(department -> department.getOfficeLocation() != null)
                .toList();
    }
}
