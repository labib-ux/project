package com.nagorikseba.controller;

import com.nagorikseba.entity.Complaint;
import com.nagorikseba.repository.ComplaintRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
        // Only return lightweight data necessary for rendering pins on the map
        List<Map<String, Object>> mapData = complaintRepository.findAll().stream()
                .filter(c -> c.getLatitude() != null && c.getLongitude() != null)
                .map(c -> Map.of(
                        "id", c.getId(),
                        "latitude", c.getLatitude(),
                        "longitude", c.getLongitude(),
                        "category", c.getCategory().name(),
                        "status", c.getStatus().name()
                ))
                .collect(Collectors.toList());
        return ResponseEntity.ok(mapData);
    }

    @GetMapping("/wards/{id}/performance")
    public ResponseEntity<?> getWardPerformance(@PathVariable Long id) {
        List<Complaint> complaints = complaintRepository.findByWardIdOrderBySubmittedAtDesc(id);
        
        long total = complaints.size();
        long resolved = complaints.stream()
                .filter(c -> "RESOLVED".equals(c.getStatus().name()) || "CLOSED".equals(c.getStatus().name()))
                .count();
        
        double resolutionRate = total == 0 ? 0 : (double) resolved / total * 100;
        
        double avgRating = complaints.stream()
                .filter(c -> c.getRating() != null)
                .mapToInt(Complaint::getRating)
                .average()
                .orElse(0.0);

        return ResponseEntity.ok(Map.of(
                "wardId", id,
                "totalComplaints", total,
                "resolvedComplaints", resolved,
                "resolutionRate", resolutionRate,
                "avgRating", avgRating
        ));
    }
}
