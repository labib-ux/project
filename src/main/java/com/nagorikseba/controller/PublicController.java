package com.nagorikseba.controller;

import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.domain.enums.ComplaintStatus;
import com.nagorikseba.complaint.repo.ComplaintRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicController {

    private final ComplaintRepository complaintRepository;

    @GetMapping("/complaints/map")
    public ResponseEntity<?> getPublicMap() {
        List<Map<String, Object>> mapData = complaintRepository.findAll().stream()
                .filter(c -> c.getLocation() != null)
                .filter(c -> c.isPublicVisible() && c.getModerationStatus() == com.nagorikseba.complaint.domain.enums.ModerationStatus.APPROVED)
                .filter(c -> c.getStatus() != ComplaintStatus.REJECTED && c.getStatus() != ComplaintStatus.CANCELLED)
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", c.getId());
                    m.put("referenceCode", c.getReferenceCode());
                    m.put("latitude", c.getLocation().getY());
                    m.put("longitude", c.getLocation().getX());
                    m.put("category", c.getCategory().name());
                    m.put("status", c.getStatus().name());
                    return m;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(mapData);
    }

    @GetMapping("/wards/{id}/performance")
    public ResponseEntity<?> getWardPerformance(@PathVariable Long id) {
        List<Complaint> complaints = complaintRepository.findByWardIdAndStatusNotIn(id, List.of(ComplaintStatus.CLOSED, ComplaintStatus.REJECTED, ComplaintStatus.CANCELLED));
        
        long total = complaints.size();
        long resolved = complaints.stream()
                .filter(c -> c.getStatus() == ComplaintStatus.RESOLVED || c.getStatus() == ComplaintStatus.CLOSED)
                .count();
        
        double resolutionRate = total == 0 ? 0 : (double) resolved / total * 100;
        
        double avgRating = 0.0; // Rating is now in resolution_attempts (Phase 5)

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("wardId", id);
        result.put("totalComplaints", total);
        result.put("resolvedComplaints", resolved);
        result.put("resolutionRate", resolutionRate);
        result.put("avgRating", avgRating);
        return ResponseEntity.ok(result);
    }
}