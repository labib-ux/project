package com.nagorikseba.controller;

import com.nagorikseba.entity.Complaint;
import com.nagorikseba.repository.ComplaintRepository;
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
        // Only return lightweight data necessary for rendering pins on the map
        List<Map<String, Object>> mapData = complaintRepository.findAll().stream()
                .filter(c -> c.getLatitude() != null && c.getLongitude() != null)
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", c.getId());
                    m.put("latitude", c.getLatitude());
                    m.put("longitude", c.getLongitude());
                    m.put("category", c.getCategory().name());
                    m.put("status", c.getStatus().name());
                    return m;
                })
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

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("wardId", id);
        result.put("totalComplaints", total);
        result.put("resolvedComplaints", resolved);
        result.put("resolutionRate", resolutionRate);
        result.put("avgRating", avgRating);
        return ResponseEntity.ok(result);
    }
}
