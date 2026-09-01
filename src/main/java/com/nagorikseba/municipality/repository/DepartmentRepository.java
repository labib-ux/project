package com.nagorikseba.municipality.repository;

import com.nagorikseba.municipality.entity.Department;
import com.nagorikseba.municipality.entity.Municipality;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DepartmentRepository extends JpaRepository<Department, Long> {

    List<Department> findByMunicipalityIdAndIsActiveTrue(Long municipalityId);

    Optional<Department> findByMunicipalityIdAndCode(Long municipalityId, String code);

    @Query(value = "SELECT * FROM departments WHERE municipality_id = :municipalityId AND is_active = true AND :category = ANY(handles_categories)", nativeQuery = true)
    List<Department> findByMunicipalityIdAndHandlesCategory(Long municipalityId, String category);

    List<Department> findByMunicipality(Municipality municipality);
}