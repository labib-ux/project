package com.nagorikseba.municipality.api;

import com.nagorikseba.municipality.dto.WardResponse;
import com.nagorikseba.municipality.entity.Municipality;
import com.nagorikseba.municipality.repository.MunicipalityRepository;
import com.nagorikseba.municipality.service.WardBoundaryService;
import com.nagorikseba.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Public ward-boundary lookups (Phase 4).
 *
 * <p>Read-only reference data under {@code /api/municipalities/**}, hence public
 * per the existing {@code GET /api/municipalities/** → permitAll} chain rule —
 * no credentials needed, matching the sibling municipality endpoints.
 */
@RestController
@RequestMapping("/api/municipalities")
@RequiredArgsConstructor
public class WardBoundaryController {

    private final WardBoundaryService wardBoundaryService;
    private final MunicipalityRepository municipalityRepository;

    /** Point-in-polygon: which ward of this municipality contains the pin. */
    @GetMapping("/{slug}/wards/containing")
    public ResponseEntity<WardResponse> containingWard(
            @PathVariable String slug,
            @RequestParam double lat,
            @RequestParam double lng) {
        Municipality municipality = municipalityRepository.findBySlugAndIsActiveTrue(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Municipality not found: " + slug));
        return ResponseEntity.ok(WardResponse.from(
                wardBoundaryService.resolvePoint(municipality.getId(), lat, lng)));
    }

    /** Admin validation: true area overlaps between this municipality's wards. */
    @GetMapping("/{slug}/boundary-validation")
    public ResponseEntity<Map<String, Object>> boundaryValidation(@PathVariable String slug) {
        Municipality municipality = municipalityRepository.findBySlugAndIsActiveTrue(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Municipality not found: " + slug));
        List<WardBoundaryService.OverlapPair> overlaps =
                wardBoundaryService.validateBoundaryOverlap(municipality.getId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("municipality", slug);
        body.put("valid", overlaps.isEmpty());
        body.put("overlaps", overlaps.stream()
                .map(pair -> Map.of("wardA", pair.firstWardId(), "wardB", pair.secondWardId()))
                .toList());
        return ResponseEntity.ok(body);
    }
}
