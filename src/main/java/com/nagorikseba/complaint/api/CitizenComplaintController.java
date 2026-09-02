package com.nagorikseba.complaint.api;

import com.nagorikseba.complaint.api.dto.ComplaintResponse;
import com.nagorikseba.complaint.api.dto.ComplaintSubmissionRequest;
import com.nagorikseba.complaint.service.ComplaintQueryService;
import com.nagorikseba.complaint.submission.StandardComplaintSubmission;
import com.nagorikseba.complaint.submission.AnonymousComplaintSubmission;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.identity.repo.UserRepository;
import com.nagorikseba.shared.exception.ResourceNotFoundException;
import com.nagorikseba.shared.security.PrincipalContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/complaints")
@RequiredArgsConstructor
public class CitizenComplaintController {

    private final StandardComplaintSubmission standardSubmission;
    private final AnonymousComplaintSubmission anonymousSubmission;
    private final ComplaintQueryService queryService;
    private final UserRepository userRepository;
    private final PrincipalContext principalContext;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ComplaintResponse> submit(
            @Valid @ModelAttribute ComplaintSubmissionRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal UserDetails userDetails) {
        
        if (idempotencyKey != null) {
            request.setIdempotencyKey(idempotencyKey);
        }

        User citizen = null;
        if (userDetails != null) {
            citizen = userRepository.findByEmailIgnoreCase(userDetails.getUsername())
                    .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        }

        ComplaintResponse response;
        if (citizen != null) {
            response = standardSubmission.submit(request, citizen);
        } else {
            response = anonymousSubmission.submit(request, citizen);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/my")
    public List<ComplaintResponse> findMyComplaints(@AuthenticationPrincipal UserDetails userDetails) {
        return queryService.findMyComplaints();
    }

    @GetMapping("/{referenceCode}")
    public ComplaintResponse findByReferenceCode(
            @PathVariable String referenceCode,
            @AuthenticationPrincipal UserDetails userDetails) {
        return queryService.findByReferenceCode(referenceCode);
    }

    @PostMapping("/{referenceCode}/cancel")
    public ResponseEntity<ComplaintResponse> cancel(
            @PathVariable String referenceCode,
            @RequestParam String reason,
            @AuthenticationPrincipal UserDetails userDetails) {
        // Implementation will use ComplaintLifecycleService with CancelHandler
        // For now, this is a placeholder - the lifecycle service will handle it
        throw new UnsupportedOperationException("Cancel will be implemented via lifecycle service in Phase 3 completion");
    }
}