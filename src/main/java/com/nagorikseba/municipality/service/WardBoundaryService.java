package com.nagorikseba.municipality.service;

import com.nagorikseba.municipality.entity.Ward;
import com.nagorikseba.municipality.repository.WardRepository;
import com.nagorikseba.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Point-in-polygon ward resolution over PostGIS boundaries (§3.1, Phase 4).
 *
 * <p>Read-only and side-effect free: it answers "which ward contains this pin?"
 * for submissions, the public lookup endpoint and the overlap validator. Inactive
 * wards never match — they keep history but reject new complaints.
 */
@Service
@RequiredArgsConstructor
public class WardBoundaryService {

    /**
     * No ward contains the point (§3.1 edge case). Extends the existing 404
     * mapper so the lookup endpoint answers 404 without touching
     * {@code GlobalExceptionHandler}.
     */
    public static class WardNotCoveredException extends ResourceNotFoundException {
        public WardNotCoveredException(double lat, double lng) {
            super("No active ward covers location " + lat + ", " + lng);
        }
    }

    /** One overlapping pair of active ward ids, lowest first. */
    public record OverlapPair(Long firstWardId, Long secondWardId) {
    }

    private final WardRepository wardRepository;

    /**
     * Resolve a pin to its ward, scoped to one municipality.
     *
     * @throws WardNotCoveredException when no active ward of the municipality
     *         covers the point (→ 404 on the REST lookup)
     */
    @Transactional(readOnly = true)
    public Ward resolvePoint(Long municipalityId, double lat, double lng) {
        return wardRepository.findWardContaining(municipalityId, lng, lat)
                .orElseThrow(() -> new WardNotCoveredException(lat, lng));
    }

    /**
     * True area overlaps between active wards (§3.1 admin validation).
     * Wards sharing only an edge are adjacent, not overlapping, and are not
     * reported — see the {@code ST_Overlaps} note on the repository query.
     */
    @Transactional(readOnly = true)
    public List<OverlapPair> validateBoundaryOverlap(Long municipalityId) {
        return wardRepository.findOverlappingWardIds(municipalityId).stream()
                .map(row -> new OverlapPair(((Number) row[0]).longValue(), ((Number) row[1]).longValue()))
                .toList();
    }
}
