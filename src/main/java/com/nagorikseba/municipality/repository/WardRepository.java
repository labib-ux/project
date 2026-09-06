package com.nagorikseba.municipality.repository;

import com.nagorikseba.municipality.entity.Municipality;
import com.nagorikseba.municipality.entity.Ward;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WardRepository extends JpaRepository<Ward, Long> {

    List<Ward> findByMunicipalityIdAndIsActiveTrueOrderByWardNumberAsc(Long municipalityId);

    Optional<Ward> findByMunicipalityIdAndWardNumber(Long municipalityId, Integer wardNumber);

    @Query("SELECT w FROM Ward w WHERE w.municipality.id = :municipalityId AND w.isActive = true AND ST_Contains(w.boundary, :point) = true")
    Optional<Ward> findByPointWithinBoundary(Long municipalityId, Object point);

    @Query("SELECT w FROM Ward w WHERE w.isActive = true AND ST_Contains(w.boundary, :point) = true")
    Optional<Ward> findByPointWithinBoundary(Object point);

    List<Ward> findByMunicipality(Municipality municipality);

    /**
     * Point-in-polygon ward lookup (§4, Phase 4).
     *
     * <p>{@code ST_Covers} (not {@code ST_Contains}) so a pin exactly on a shared
     * ward edge still resolves instead of falling through. The GiST index
     * {@code idx_ward_boundary_gist} serves the bounding-box prefilter; the
     * {@code ORDER BY id LIMIT 1} makes shared-edge resolution deterministic
     * (lowest ward id wins) instead of planner-dependent.
     */
    @Query(value = """
            SELECT * FROM wards w
            WHERE w.municipality_id = :municipalityId
              AND w.is_active = true
              AND ST_Covers(w.boundary, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326))
            ORDER BY w.id ASC
            LIMIT 1
            """, nativeQuery = true)
    Optional<Ward> findWardContaining(@Param("municipalityId") Long municipalityId,
                                      @Param("lng") double lng,
                                      @Param("lat") double lat);

    /**
     * True area overlaps between active wards of one municipality (§3.1).
     *
     * <p>{@code ST_Overlaps} (not {@code ST_Intersects}): merely touching at a
     * shared edge is the intended adjacency of the ward grid, only same-dimension
     * intersection is a data error. Each pair appears once ({@code a.id < b.id}).
     */
    @Query(value = """
            SELECT a.id, b.id FROM wards a
            JOIN wards b ON b.municipality_id = :municipalityId
                AND b.is_active = true
                AND a.id < b.id
                AND ST_Overlaps(a.boundary, b.boundary)
            WHERE a.municipality_id = :municipalityId
              AND a.is_active = true
            ORDER BY a.id, b.id
            """, nativeQuery = true)
    List<Object[]> findOverlappingWardIds(@Param("municipalityId") Long municipalityId);
}