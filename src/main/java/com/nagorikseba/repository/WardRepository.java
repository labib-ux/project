package com.nagorikseba.repository;

import com.nagorikseba.entity.Ward;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WardRepository extends JpaRepository<Ward, Long> {

    List<Ward> findByCityCorporationIgnoreCaseOrderByWardNumberAsc(String cityCorporation);

    Optional<Ward> findByWardNumberAndCityCorporationIgnoreCase(Integer wardNumber, String cityCorporation);

    @org.springframework.data.jpa.repository.Query("SELECT w FROM Ward w WHERE :lat BETWEEN w.minLatitude AND w.maxLatitude AND :lon BETWEEN w.minLongitude AND w.maxLongitude")
    Optional<Ward> findWardByCoordinates(
            @org.springframework.data.repository.query.Param("lat") java.math.BigDecimal lat,
            @org.springframework.data.repository.query.Param("lon") java.math.BigDecimal lon);
}
