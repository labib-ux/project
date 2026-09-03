package com.nagorikseba.complaint.api;

import com.nagorikseba.complaint.api.dto.ComplaintResponse;
import com.nagorikseba.complaint.api.dto.ComplaintSubmissionRequest;
import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.domain.enums.ComplaintAction;
import com.nagorikseba.complaint.lifecycle.ComplaintLifecycleService;
import com.nagorikseba.complaint.lifecycle.TransitionCommand;
import com.nagorikseba.complaint.repo.ComplaintRepository;
import com.nagorikseba.complaint.service.ComplaintQueryService;
import com.nagorikseba.complaint.submission.AnonymousComplaintSubmission;
import com.nagorikseba.complaint.submission.StandardComplaintSubmission;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.identity.repo.UserRepository;
import com.nagorikseba.shared.exception.ResourceNotFoundException;
import com.nagorikseba.shared.security.PrincipalContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The citizen-facing complaint API (§9.1).
 *
 * <p>Complaints are addressed by {@code referenceCode}, never by database id: the
 * reference code is what a citizen is given, what they read out over the phone,
 * and it does not leak how many complaints the platform has received.
 */
@RestController
@RequestMapping("/api/complaints")
@RequiredArgsConstructor
public class CitizenComplaintController {

    private final StandardComplaintSubmission standardSubmission;
    private final AnonymousComplaintSubmission anonymousSubmission;
    private final ComplaintLifecycleService lifecycleService;
    private final ComplaintQueryService queryService;
    private final ComplaintRepository complaintRepository;
    private final UserRepository userRepository;
    private final PrincipalContext principalContext;

    /**
     * Submit a complaint, authenticated or anonymous.
     *
     * <p>The {@code Idempotency-Key} header is what makes a retry safe: replaying a
     * key returns the complaint it originally created rather than filing a second
     * report of the same pothole (R3).
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ComplaintResponse> submit(
            @Valid @ModelAttribute ComplaintSubmissionRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            request.setIdempotencyKey(idempotencyKey);
        }

        User citizen = principalContext.currentUserId()
                .map(id -> userRepository.findById(id)
                        .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id)))
                .orElse(null);

        Complaint complaint = citizen != null
                ? standardSubmission.submit(request, citizen)
                : anonymousSubmission.submit(request, null);

        return ResponseEntity.status(HttpStatus.CREATED).body(queryService.describe(complaint));
    }

    @GetMapping("/my")
    public List<ComplaintResponse> findMyComplaints() {
        return queryService.findMyComplaints();
    }

    /** Owner or serving authority only — the query service enforces it. */
    @GetMapping("/{referenceCode}")
    public ComplaintResponse findByReferenceCode(@PathVariable String referenceCode) {
        return queryService.findByReferenceCode(referenceCode);
    }

    /**
     * Withdraw a complaint the caller filed.
     *
     * <p>Ownership is checked inside {@code CancelHandler}, not here, so the rule
     * lives with the transition it guards and holds for every future caller of the
     * lifecycle service rather than just this endpoint.
     */
    @PostMapping("/{referenceCode}/cancel")
    public ComplaintResponse cancel(
            @PathVariable String referenceCode,
            @RequestParam String reason,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        Complaint complaint = complaintRepository.findByReferenceCode(referenceCode)
                .orElseThrow(() -> new ResourceNotFoundException("Complaint not found: " + referenceCode));

        TransitionCommand command = TransitionCommand.of(
                ComplaintAction.CANCEL,
                complaint.getId(),
                principalContext.requireUserId(),
                reason,
                idempotencyKey,
                complaint.getVersion());

        return queryService.describe(lifecycleService.execute(command));
    }
}
