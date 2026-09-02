package com.nagorikseba.controller;

import com.nagorikseba.dto.complaint.ComplaintResponse;
import com.nagorikseba.dto.complaint.ComplaintSubmissionRequest;
import com.nagorikseba.service.ComplaintService;
import com.nagorikseba.shared.exception.ResourceNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/complaints")
@RequiredArgsConstructor
public class ComplaintController {

    private final ComplaintService complaintService;
    private final com.nagorikseba.identity.repo.UserRepository userRepository;
    private final com.nagorikseba.repository.ComplaintRepository complaintRepository;
    private final com.nagorikseba.state.ComplaintStateMachine stateMachine;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ComplaintResponse> submit(
            @Valid @ModelAttribute ComplaintSubmissionRequest request,
            @AuthenticationPrincipal UserDetails citizen) {
        return ResponseEntity.status(HttpStatus.CREATED).body(complaintService.submit(request, citizen.getUsername()));
    }

    @GetMapping("/my")
    public List<ComplaintResponse> findMyComplaints(@AuthenticationPrincipal UserDetails citizen) {
        return complaintService.findMyComplaints(citizen.getUsername());
    }

    @GetMapping("/{complaintId}")
    public ComplaintResponse findMyComplaint(
            @PathVariable Long complaintId,
            @AuthenticationPrincipal UserDetails citizen) {
        return complaintService.findMyComplaint(complaintId, citizen.getUsername());
    }

    @PostMapping("/{id}/rate")
    public ResponseEntity<?> rateComplaint(@PathVariable Long id, @RequestParam int rating,
            @RequestParam(required = false) String feedback, @AuthenticationPrincipal UserDetails citizenDetails) {
        com.nagorikseba.identity.domain.User citizen = userRepository.findByEmailIgnoreCase(citizenDetails.getUsername())
                .orElseThrow(() -> new ResourceNotFoundException("Citizen not found"));
        com.nagorikseba.entity.Complaint complaint = complaintRepository.findById(id).orElseThrow();

        stateMachine.process(complaint, com.nagorikseba.state.ComplaintAction.CLOSE, citizen, feedback, null, rating);
        return ResponseEntity.ok(java.util.Map.of("message", "Complaint rated and closed"));
    }

    @PostMapping("/{id}/reopen")
    public ResponseEntity<?> reopenComplaint(@PathVariable Long id, @RequestParam String reason,
            @AuthenticationPrincipal UserDetails citizenDetails) {
        com.nagorikseba.identity.domain.User citizen = userRepository.findByEmailIgnoreCase(citizenDetails.getUsername())
                .orElseThrow(() -> new ResourceNotFoundException("Citizen not found"));
        com.nagorikseba.entity.Complaint complaint = complaintRepository.findById(id).orElseThrow();

        stateMachine.process(complaint, com.nagorikseba.state.ComplaintAction.REOPEN, citizen, reason, null, null);
        return ResponseEntity.ok(java.util.Map.of("message", "Complaint reopened successfully"));
    }
}
