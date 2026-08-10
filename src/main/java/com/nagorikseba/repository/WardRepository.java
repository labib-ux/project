package com.nagorikseba.repository;

import com.nagorikseba.entity.Ward;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WardRepository extends JpaRepository<Ward, Long> {

    List<Ward> findByCityCorporationIgnoreCaseOrderByWardNumberAsc(String cityCorporation);
}
