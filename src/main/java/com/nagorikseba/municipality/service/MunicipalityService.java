package com.nagorikseba.municipality.service;

import com.nagorikseba.municipality.dto.DepartmentResponse;
import com.nagorikseba.municipality.dto.MunicipalityResponse;
import com.nagorikseba.municipality.dto.WardResponse;
import com.nagorikseba.municipality.entity.Department;
import com.nagorikseba.municipality.entity.Municipality;
import com.nagorikseba.municipality.entity.Ward;
import com.nagorikseba.municipality.repository.DepartmentRepository;
import com.nagorikseba.municipality.repository.MunicipalityRepository;
import com.nagorikseba.municipality.repository.WardRepository;
import com.nagorikseba.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MunicipalityService {

    private final MunicipalityRepository municipalityRepository;
    private final WardRepository wardRepository;
    private final DepartmentRepository departmentRepository;

    @Transactional(readOnly = true)
    public List<MunicipalityResponse> findAllActiveMunicipalities() {
        return municipalityRepository.findAll().stream()
                .filter(Municipality::getIsActive)
                .map(MunicipalityResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public MunicipalityResponse findMunicipalityBySlug(String slug) {
        Municipality municipality = municipalityRepository.findBySlugAndIsActiveTrue(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Municipality not found: " + slug));
        return MunicipalityResponse.from(municipality);
    }

    @Transactional(readOnly = true)
    public List<WardResponse> findWardsByMunicipalitySlug(String slug) {
        Municipality municipality = municipalityRepository.findBySlugAndIsActiveTrue(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Municipality not found: " + slug));
        return wardRepository.findByMunicipalityIdAndIsActiveTrueOrderByWardNumberAsc(municipality.getId())
                .stream()
                .map(WardResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public WardResponse findWardByMunicipalitySlugAndNumber(String slug, Integer wardNumber) {
        Municipality municipality = municipalityRepository.findBySlugAndIsActiveTrue(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Municipality not found: " + slug));
        Ward ward = wardRepository.findByMunicipalityIdAndWardNumber(municipality.getId(), wardNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Ward not found: " + wardNumber + " in municipality " + slug));
        return WardResponse.from(ward);
    }

    @Transactional(readOnly = true)
    public List<DepartmentResponse> findDepartmentsByMunicipalitySlug(String slug) {
        Municipality municipality = municipalityRepository.findBySlugAndIsActiveTrue(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Municipality not found: " + slug));
        return departmentRepository.findByMunicipalityIdAndIsActiveTrue(municipality.getId())
                .stream()
                .map(DepartmentResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DepartmentResponse> findDepartmentsByMunicipalitySlugAndCategory(String slug, String category) {
        Municipality municipality = municipalityRepository.findBySlugAndIsActiveTrue(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Municipality not found: " + slug));
        return departmentRepository.findByMunicipalityIdAndHandlesCategory(municipality.getId(), category)
                .stream()
                .map(DepartmentResponse::from)
                .toList();
    }
}