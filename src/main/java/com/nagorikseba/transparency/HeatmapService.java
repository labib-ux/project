package com.nagorikseba.transparency;

import com.nagorikseba.complaint.domain.Attachment;
import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.domain.ComplaintTransition;
import com.nagorikseba.complaint.repo.AttachmentRepository;
import com.nagorikseba.complaint.repo.ComplaintRepository;
import com.nagorikseba.complaint.repo.ComplaintTransitionRepository;
import com.nagorikseba.complaint.service.ComplaintMapper;
import com.nagorikseba.municipality.repository.MunicipalityRepository;
import com.nagorikseba.shared.exception.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Public heatmap query (T3, §9).
 *
 * <p>Only public-visible, APPROVED, non-REJECTED/CANCELLED complaints inside
 * the bbox ever leave the database. Coordinates snap to a 0.001° grid (~100 m
 * at Dhaka): enough for heatmaps, useless for door-stepping. Past 500 points
 * the database clusters per grid cell instead of returning individuals.
 * Citizen identity never appears — the detail branch reuses the mapper's
 * public projection, which nulls it centrally.
 */
@Service
@RequiredArgsConstructor
public class HeatmapService {

    /** Detail mode past this many points switches to grid clustering. */
    public static final int CLUSTER_THRESHOLD = 500;

    /**
     * Cluster grid bounds in degrees. Detail points always snap to
     * {@link #MIN_GRID_SIZE} (~100 m, matching the mapper); clustered cells
     * widen with the viewport so a city-wide bbox returns dozens of cells
     * instead of thousands.
     */
    public static final double MIN_GRID_SIZE = 0.001;
    public static final double MAX_GRID_SIZE = 0.02;

    /** Backwards-compatible fixed grid (the detail snap). */
    public static final double GRID_SIZE = MIN_GRID_SIZE;

    private final ComplaintRepository complaintRepository;
    private final AttachmentRepository attachmentRepository;
    private final ComplaintTransitionRepository transitionRepository;
    private final MunicipalityRepository municipalityRepository;
    private final ComplaintMapper complaintMapper;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * @return map with {@code clustered} flag and {@code points} list.
     *         Detail points: {referenceCode, category, status, lng, lat} —
     *         snapped, no PII. Clustered cells: {lng, lat, count, category}.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> heatmap(String municipalitySlug,
                                       double minLng, double minLat,
                                       double maxLng, double maxLat) {
        Long municipalityId = municipalityRepository.findBySlugAndIsActiveTrue(municipalitySlug)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Municipality not found: " + municipalitySlug)).getId();
        validateBbox(minLng, minLat, maxLng, maxLat);

        long count = countInBbox(municipalityId, minLng, minLat, maxLng, maxLat);
        Map<String, Object> body = new LinkedHashMap<>();
        if (count > CLUSTER_THRESHOLD) {
            double grid = gridForBbox(minLng, minLat, maxLng, maxLat);
            body.put("clustered", true);
            body.put("gridSize", grid);
            body.put("points", clusteredCells(municipalityId, minLng, minLat, maxLng, maxLat, grid));
        } else {
            body.put("clustered", false);
            body.put("points", detailPoints(municipalityId, minLng, minLat, maxLng, maxLat));
        }
        return body;
    }

    /**
     * Viewport-adaptive cluster grid: roughly 100 cells across the wider bbox
     * axis, clamped to {@link #MIN_GRID_SIZE}–{@link #MAX_GRID_SIZE}. A street
     * level bbox keeps ~100 m cells; a city-wide one widens to ~2 km so the
     * response stays small and the frontend draws districts, not noise.
     */
    static double gridForBbox(double minLng, double minLat, double maxLng, double maxLat) {
        double span = Math.max(maxLng - minLng, maxLat - minLat);
        return Math.min(MAX_GRID_SIZE, Math.max(MIN_GRID_SIZE, span / 100.0));
    }

    private void validateBbox(double minLng, double minLat, double maxLng, double maxLat) {
        if (!(minLng < maxLng && minLat < maxLat)) {
            throw new IllegalArgumentException("Invalid bounding box: min must be below max");
        }
    }

    private long countInBbox(Long municipalityId, double minLng, double minLat,
                             double maxLng, double maxLat) {
        return (long) entityManager.createNativeQuery(
                "SELECT count(*) FROM complaints"
                        + " WHERE municipality_id = :municipalityId"
                        + " AND is_public_visible"
                        + " AND moderation_status = 'APPROVED'"
                        + " AND status NOT IN ('REJECTED','CANCELLED')"
                        + " AND location && ST_MakeEnvelope(:minLng, :minLat, :maxLng, :maxLat, 4326)")
                .setParameter("municipalityId", municipalityId)
                .setParameter("minLng", minLng).setParameter("minLat", minLat)
                .setParameter("maxLng", maxLng).setParameter("maxLat", maxLat)
                .getSingleResult();
    }

    private List<Map<String, Object>> clusteredCells(Long municipalityId, double minLng, double minLat,
                                                      double maxLng, double maxLat, double grid) {
        List<Object[]> rows = entityManager.createNativeQuery(
                "SELECT ST_X(ST_Centroid(ST_Collect(ST_SnapToGrid(location::geometry, :grid)))) AS lng,"
                        + " ST_Y(ST_Centroid(ST_Collect(ST_SnapToGrid(location::geometry, :grid)))) AS lat,"
                        + " category, count(*) AS cnt"
                        + " FROM complaints"
                        + " WHERE municipality_id = :municipalityId"
                        + " AND is_public_visible"
                        + " AND moderation_status = 'APPROVED'"
                        + " AND status NOT IN ('REJECTED','CANCELLED')"
                        + " AND location && ST_MakeEnvelope(:minLng, :minLat, :maxLng, :maxLat, 4326)"
                        + " GROUP BY ST_SnapToGrid(location::geometry, :grid), category"
                        + " ORDER BY cnt DESC")
                .setParameter("grid", grid)
                .setParameter("municipalityId", municipalityId)
                .setParameter("minLng", minLng).setParameter("minLat", minLat)
                .setParameter("maxLng", maxLng).setParameter("maxLat", maxLat)
                .getResultList();
        List<Map<String, Object>> cells = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            Map<String, Object> cell = new LinkedHashMap<>();
            cell.put("lng", ((Number) row[0]).doubleValue());
            cell.put("lat", ((Number) row[1]).doubleValue());
            cell.put("category", row[2]);
            cell.put("count", ((Number) row[3]).longValue());
            cells.add(cell);
        }
        return cells;
    }

    private List<Map<String, Object>> detailPoints(Long municipalityId, double minLng, double minLat,
                                                   double maxLng, double maxLat) {
        List<Number> ids = entityManager.createNativeQuery(
                "SELECT id FROM complaints"
                        + " WHERE municipality_id = :municipalityId"
                        + " AND is_public_visible"
                        + " AND moderation_status = 'APPROVED'"
                        + " AND status NOT IN ('REJECTED','CANCELLED')"
                        + " AND location && ST_MakeEnvelope(:minLng, :minLat, :maxLng, :maxLat, 4326)"
                        + " ORDER BY id ASC")
                .setParameter("municipalityId", municipalityId)
                .setParameter("minLng", minLng).setParameter("minLat", minLat)
                .setParameter("maxLng", maxLng).setParameter("maxLat", maxLat)
                .getResultList();
        List<Map<String, Object>> points = new ArrayList<>(ids.size());
        for (Number id : ids) {
            Complaint complaint = complaintRepository.findById(id.longValue()).orElse(null);
            if (complaint == null) {
                continue;
            }
            List<Attachment> attachments = attachmentRepository
                    .findByComplaintIdAndDeletedAtIsNullOrderByCreatedAtAsc(complaint.getId());
            List<ComplaintTransition> transitions = transitionRepository
                    .findByComplaintIdOrderByCreatedAtAsc(complaint.getId());
            var projected = complaintMapper.toPublicResponse(complaint, attachments, transitions);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("referenceCode", projected.getReferenceCode());
            point.put("category", projected.getCategory() != null
                    ? projected.getCategory().name() : null);
            point.put("status", projected.getStatus() != null
                    ? projected.getStatus().name() : null);
            point.put("lng", projected.getLongitude());
            point.put("lat", projected.getLatitude());
            points.add(point);
        }
        return points;
    }
}
