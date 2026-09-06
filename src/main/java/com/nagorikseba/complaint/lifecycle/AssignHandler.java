package com.nagorikseba.complaint.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.domain.ComplaintAssignment;
import com.nagorikseba.complaint.domain.ComplaintMutator;
import com.nagorikseba.complaint.domain.enums.ComplaintAction;
import com.nagorikseba.complaint.domain.enums.ComplaintStatus;
import com.nagorikseba.complaint.repo.ComplaintAssignmentRepository;
import com.nagorikseba.complaint.routing.RoutingDecision;
import com.nagorikseba.complaint.routing.RoutingStrategyResolver;
import com.nagorikseba.enums.UserRole;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.identity.repo.MembershipRepository;
import com.nagorikseba.identity.repo.UserRepository;
import com.nagorikseba.municipality.entity.Department;
import com.nagorikseba.municipality.repository.DepartmentRepository;
import com.nagorikseba.shared.exception.ResourceNotFoundException;
import com.nagorikseba.shared.outbox.OutboxPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Arrays;
import java.util.Set;

/**
 * VERIFIED | REOPENED → ASSIGNED (§6, Phase 4).
 *
 * <p>Two paths through one handler. Manual: the caller picks department (and
 * optionally officer); every §6 guard is checked here rather than the
 * controller so future callers inherit them. Auto: {@code departmentId} is null
 * and the {@link RoutingStrategyResolver} decides (the SYSTEM path — no human
 * picked, the strategy name and explanation are the audit trail).
 *
 * <p>Both paths close any previous active assignment, open a new
 * {@code complaint_assignments} row, and publish a {@code COMPLAINT_ASSIGNED}
 * outbox row in the same transaction. The routing explanation additionally
 * lands on the transition {@code metadata} via {@link #transitionMetadata}.
 */
@Component
public class AssignHandler extends ComplaintMutator implements TransitionHandler {

    public static final String STRATEGY_MANUAL = "MANUAL";

    private final ComplaintAssignmentRepository assignmentRepository;
    private final DepartmentRepository departmentRepository;
    private final UserRepository userRepository;
    private final MembershipRepository membershipRepository;
    private final RoutingStrategyResolver resolver;
    private final OutboxPublisher outboxPublisher;
    private final ObjectMapper objectMapper;

    public AssignHandler(ComplaintAssignmentRepository assignmentRepository,
                         DepartmentRepository departmentRepository,
                         UserRepository userRepository,
                         MembershipRepository membershipRepository,
                         RoutingStrategyResolver resolver,
                         OutboxPublisher outboxPublisher,
                         ObjectMapper objectMapper) {
        this.assignmentRepository = assignmentRepository;
        this.departmentRepository = departmentRepository;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.resolver = resolver;
        this.outboxPublisher = outboxPublisher;
        this.objectMapper = objectMapper;
    }

    @Override
    public ComplaintAction supportedAction() {
        return ComplaintAction.ASSIGN;
    }

    @Override
    public Set<ComplaintStatus> sourceStatuses() {
        return Set.of(ComplaintStatus.VERIFIED, ComplaintStatus.REOPENED);
    }

    @Override
    public void execute(Complaint complaint, TransitionCommand command, Instant occurredAt) {
        User actor = userRepository.findById(command.actorId())
                .orElseThrow(() -> new ResourceNotFoundException("Actor not found with id: " + command.actorId()));
        if (!Set.of(UserRole.DEPT_OFFICER, UserRole.WARD_COUNCILOR, UserRole.ADMIN).contains(actor.getRole())) {
            throw new AccessDeniedException("Only officers, councilors or admins can assign complaints");
        }

        Department department;
        User officer;
        String strategy;
        String explanation;
        if (command.departmentId() != null) {
            department = manualDepartment(complaint, command.departmentId());
            officer = command.officerId() != null
                    ? manualOfficer(complaint, department, command.officerId()) : null;
            strategy = STRATEGY_MANUAL;
            explanation = officer != null
                    ? "manually assigned to department " + department.getCode()
                    + " (id " + department.getId() + "), officer " + officer.getFullName()
                    + " (id " + officer.getId() + ") by " + actor.getFullName()
                    : "manually assigned to department " + department.getCode()
                    + " (id " + department.getId() + ") by " + actor.getFullName();
        } else {
            RoutingDecision decision = resolver.resolve(complaint);
            department = decision.department();
            officer = decision.officer();
            strategy = decision.strategy();
            explanation = decision.explanation();
        }

        assignmentRepository.findByComplaintIdAndUnassignedAtIsNull(complaint.getId())
                .ifPresent(previous -> previous.close(occurredAt));

        assignTo(complaint, department, officer);
        markFirstAssignedAt(complaint, occurredAt);
        changeStatus(complaint, ComplaintStatus.ASSIGNED);

        assignmentRepository.save(ComplaintAssignment.builder()
                .complaint(complaint)
                .department(department)
                .officer(officer)
                .assignedBy(actor)
                .strategyUsed(strategy)
                .strategyExplanation(explanation)
                .build());

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("complaintId", complaint.getId());
        payload.put("referenceCode", complaint.getReferenceCode());
        payload.put("occurredAt", occurredAt.toString());
        payload.put("action", ComplaintAction.ASSIGN.name());
        payload.put("departmentId", department.getId());
        payload.put("departmentCode", department.getCode());
        if (officer != null) {
            payload.put("officerId", officer.getId());
        } else {
            payload.putNull("officerId");
        }
        payload.put("strategy", strategy);
        payload.put("explanation", explanation);
        payload.put("actorId", actor.getId());
        outboxPublisher.publish(ComplaintLifecycleService.AGGREGATE_TYPE, complaint.getId(),
                "COMPLAINT_ASSIGNED", write(payload));
    }

    @Override
    public String transitionMetadata(Complaint complaint, TransitionCommand command) {
        // The row execute() just saved, read back in the same transaction —
        // handlers are stateless singletons, so per-request state is never kept
        // in a field (that would race under concurrent assigns).
        return assignmentRepository.findByComplaintIdAndUnassignedAtIsNull(complaint.getId())
                .map(assignment -> {
                    ObjectNode metadata = objectMapper.createObjectNode();
                    metadata.put("strategy", assignment.getStrategyUsed());
                    metadata.put("explanation", assignment.getStrategyExplanation());
                    metadata.put("departmentId", assignment.getDepartment().getId());
                    if (assignment.getOfficer() != null) {
                        metadata.put("officerId", assignment.getOfficer().getId());
                    } else {
                        metadata.putNull("officerId");
                    }
                    return write(metadata);
                })
                .orElse(null);
    }

    private Department manualDepartment(Complaint complaint, Long departmentId) {
        Department department = departmentRepository.findById(departmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Department not found: " + departmentId));
        if (!Boolean.TRUE.equals(department.getIsActive())) {
            throw new IllegalArgumentException("Department " + department.getCode() + " is not active");
        }
        if (!department.getMunicipality().getId().equals(complaint.getMunicipality().getId())) {
            throw new IllegalArgumentException("Department " + department.getCode()
                    + " does not serve this complaint's municipality");
        }
        if (complaint.getCategory() == null || department.getHandlesCategories() == null
                || !Arrays.asList(department.getHandlesCategories()).contains(complaint.getCategory().name())) {
            throw new IllegalArgumentException("Department " + department.getCode()
                    + " does not handle category " + complaint.getCategory());
        }
        return department;
    }

    private User manualOfficer(Complaint complaint, Department department, Long officerId) {
        User officer = userRepository.findById(officerId)
                .orElseThrow(() -> new ResourceNotFoundException("Officer not found with id: " + officerId));
        if (!officer.isActive()) {
            throw new IllegalArgumentException("Officer account is not active");
        }
        Long municipalityId = complaint.getMunicipality().getId();
        boolean servesMunicipality = membershipRepository.findByUserIdAndValidUntilIsNull(officerId).stream()
                .anyMatch(membership -> membership.getMunicipality().getId().equals(municipalityId));
        if (!servesMunicipality) {
            throw new AccessDeniedException("Officer does not serve this complaint's municipality");
        }
        boolean inDepartment = membershipRepository.findByUserIdAndValidUntilIsNull(officerId).stream()
                .anyMatch(membership -> membership.getDepartment() != null
                        && membership.getDepartment().getId().equals(department.getId()))
                || (officer.getDepartment() != null && officer.getDepartment().getId().equals(department.getId()));
        if (!inDepartment) {
            throw new IllegalArgumentException("Officer " + officer.getFullName()
                    + " is not posted to department " + department.getCode());
        }
        if (officer.getRole() != UserRole.DEPT_OFFICER && officer.getRole() != UserRole.ADMIN) {
            throw new IllegalArgumentException("Assignee must hold the DEPT_OFFICER role");
        }
        return officer;
    }

    private String write(ObjectNode payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize assignment payload", e);
        }
    }

}
