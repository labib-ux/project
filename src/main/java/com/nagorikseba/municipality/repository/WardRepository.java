package com.nagorikseba.municipality.repository;

import com.nagorikseba.municipality.entity.Municipality;
import com.nagorikseba.municipality.entity.Ward;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
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
}