package com.nagorikseba.transparency.api;

import com.nagorikseba.municipality.dto.WardResponse;
import com.nagorikseba.municipality.repository.MunicipalityRepository;
import com.nagorikseba.municipality.service.MunicipalityService;
import com.nagorikseba.shared.exception.ResourceNotFoundException;
import com.nagorikseba.transparency.HeatmapService;
import com.nagorikseba.transparency.ScoreboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Public transparency API (T6, §9): no credentials, municipality-scoped,
 * rate-limited to 60/min/IP, and PII-free by construction.
 *
 * <p>{@code GET /api/public/wards} already exists on the municipality
 * controller family and is intentionally not duplicated here.
 */
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicTransparencyController {

    private final HeatmapService heatmapService;
    private final ScoreboardService scoreboardService;
    private final MunicipalityRepository municipalityRepository;
    private final MunicipalityService municipalityService;

    /**
     * Public heatmap bbox: snapped coordinates, no citizen fields.
     * {@code municipality} is the municipality slug (e.g. dhaka-north).
     */
    @GetMapping("/heatmap")
    public ResponseEntity<Map<String, Object>> heatmap(
            @RequestParam String municipality,
            @RequestParam double minLng,
            @RequestParam double minLat,
            @RequestParam double maxLng,
            @RequestParam double maxLat) {
        return ResponseEntity.ok(heatmapService.heatmap(municipality, minLng, minLat, maxLng, maxLat));
    }

    /** Monthly per-ward ranking; {@code period} is {@code yyyy-MM}. */
    @GetMapping("/wards/scoreboard")
    public ResponseEntity<List<Map<String, Object>>> scoreboard(
            @RequestParam String municipality,
            @RequestParam(required = false) String period) {
        Long municipalityId = municipalityRepository.findBySlugAndIsActiveTrue(municipality)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Municipality not found: " + municipality)).getId();
        return ResponseEntity.ok(scoreboardService.scoreboard(municipalityId, period));
    }

    /**
     * Point-in-polygon ward lookup for the complaint wizard / map picker.
     *
     * <p>Frontend calls {@code /api/public/wards/lookup?lat=&lng=}; this is the
     * canonical public alias. A municipality-scoped variant also exists at
     * {@code /api/municipalities/{slug}/wards/containing} and
     * {@code /api/municipalities/public/wards/lookup} — all three are permitAll.
     */
    @GetMapping("/wards/lookup")
    public ResponseEntity<WardResponse> lookupWardByPoint(
            @RequestParam double lat,
            @RequestParam double lng) {
        return municipalityService.findWardContainingPoint(lat, lng)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
