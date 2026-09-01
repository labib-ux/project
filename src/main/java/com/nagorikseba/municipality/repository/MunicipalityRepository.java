package com.nagorikseba.municipality.repository;

import com.nagorikseba.municipality.entity.Municipality;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MunicipalityRepository extends JpaRepository<Municipality, Long> {

    Optional<Municipality> findBySlug(String slug);

    Optional<Municipality> findBySlugAndIsActiveTrue(String slug);

    boolean existsBySlug(String slug);
}