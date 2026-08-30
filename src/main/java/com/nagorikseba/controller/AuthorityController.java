package com.nagorikseba.controller;

import com.nagorikseba.entity.Complaint;
import com.nagorikseba.entity.Department;
import com.nagorikseba.entity.User;
import com.nagorikseba.exception.ResourceNotFoundException;
import com.nagorikseba.repository.ComplaintRepository;
import com.nagorikseba.repository.DepartmentRepository;
import com.nagorikseba.repository.UserRepository;
import com.nagorikseba.state.ComplaintAction;
import com.nagorikseba.state.ComplaintStateMachine;
import com.nagorikseba.strategy.ComplaintRoutingStrategy;
import com.nagorikseba.strategy.RoutingStrategyResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/authority")
@RequiredArgsConstructor
public class AuthorityController {

    private final ComplaintRepository complaintRepository;
    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final ComplaintStateMachine stateMachine;
    private final RoutingStrategyResolver routingStrategyResolver;

    @GetMapping("/dashboard")
    public ResponseEntity<?> getDashboard(@AuthenticationPrincipal UserDetails userDetails) {
        User authority = userRepository.findByEmailIgnoreCase(userDetails.getUsername())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        
        // Mocked dashboard stats. In a real scenario, this would aggregate data from DB.
        return ResponseEntity.ok(Map.of(
            "role", authority.getRole(),
            "ward", authority.getWard() != null ? authority.getWard().getAreaName() : "N/A",
            "message", "Welcome to Authority Dashboard"
        ));
    }

    @PostMapping("/complaints/{id}/verify")
    public ResponseEntity<?> verifyComplaint(@PathVariable Long id, @AuthenticationPrincipal UserDetails userDetails, @RequestParam(required = false) String note) {
        Complaint complaint = getComplaint(id);
        User authority = getAuthority(userDetails);
        
        stateMachine.process(complaint, ComplaintAction.VERIFY, authority, note != null ? note : "Verified", null, null);
        return ResponseEntity.ok(Map.of("message", "Complaint verified successfully"));
    }

    @PostMapping("/complaints/{id}/assign")
    public ResponseEntity<?> assignComplaint(@PathVariable Long id, 
                                             @AuthenticationPrincipal UserDetails userDetails,
                                             @RequestParam(defaultValue = "CATEGORY_ROUTING") String strategyName,
                                             @RequestParam(required = false) String note) {
        Complaint complaint = getComplaint(id);
        User authority = getAuthority(userDetails);
        
        ComplaintRoutingStrategy strategy = routingStrategyResolver.resolve(strategyName);
        List<Department> departments = departmentRepository.findAll(); // Optimization: filter by ward
        Department assignedDept = strategy.route(complaint, departments);
        
        stateMachine.process(complaint, ComplaintAction.ASSIGN, authority, note != null ? note : "Assigned", assignedDept, null);
        return ResponseEntity.ok(Map.of("message", "Complaint assigned to " + assignedDept.getName()));
    }

    @PostMapping("/complaints/{id}/start")
    public ResponseEntity<?> startWork(@PathVariable Long id, @AuthenticationPrincipal UserDetails userDetails, @RequestParam(required = false) String note) {
        Complaint complaint = getComplaint(id);
        User authority = getAuthority(userDetails);
        
        stateMachine.process(complaint, ComplaintAction.START_WORK, authority, note != null ? note : "Work started", null, null);
        return ResponseEntity.ok(Map.of("message", "Work started"));
    }

    @PostMapping("/complaints/{id}/resolve")
    public ResponseEntity<?> resolveComplaint(@PathVariable Long id, @AuthenticationPrincipal UserDetails userDetails, @RequestParam(required = false) String note) {
        Complaint complaint = getComplaint(id);
        User authority = getAuthority(userDetails);
        
        stateMachine.process(complaint, ComplaintAction.RESOLVE, authority, note != null ? note : "Resolved", null, null);
        return ResponseEntity.ok(Map.of("message", "Complaint resolved"));
    }

    private Complaint getComplaint(Long id) {
        return complaintRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Complaint not found"));
    }

    private User getAuthority(UserDetails userDetails) {
        return userRepository.findByEmailIgnoreCase(userDetails.getUsername()).orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }
}
