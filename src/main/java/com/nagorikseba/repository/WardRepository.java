package com.nagorikseba.repository;

import com.nagorikseba.entity.Ward;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WardRepository extends JpaRepository<Ward, Long> {

    List<Ward> findByCityCorporationIgnoreCaseOrderByWardNumberAsc(String cityCorporation);

    Optional<Ward> findByWardNumberAndCityCorporationIgnoreCase(Integer wardNumber, String cityCorporation);
}
