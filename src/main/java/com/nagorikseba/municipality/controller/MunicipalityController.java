package com.nagorikseba.municipality.controller;

import com.nagorikseba.municipality.dto.DepartmentResponse;
import com.nagorikseba.municipality.dto.MunicipalityResponse;
import com.nagorikseba.municipality.dto.WardResponse;
import com.nagorikseba.municipality.service.MunicipalityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/municipalities")
@RequiredArgsConstructor
public class MunicipalityController {

    private final MunicipalityService municipalityService;

    @GetMapping
    public ResponseEntity<List<MunicipalityResponse>> getAllMunicipalities() {
        return ResponseEntity.ok(municipalityService.findAllActiveMunicipalities());
    }

    @GetMapping("/{slug}")
    public ResponseEntity<MunicipalityResponse> getMunicipality(@PathVariable String slug) {
        return ResponseEntity.ok(municipalityService.findMunicipalityBySlug(slug));
    }

    @GetMapping("/{slug}/wards")
    public ResponseEntity<List<WardResponse>> getWards(@PathVariable String slug) {
        return ResponseEntity.ok(municipalityService.findWardsByMunicipalitySlug(slug));
    }

    @GetMapping("/{slug}/wards/{wardNumber}")
    public ResponseEntity<WardResponse> getWard(@PathVariable String slug, @PathVariable Integer wardNumber) {
        return ResponseEntity.ok(municipalityService.findWardByMunicipalitySlugAndNumber(slug, wardNumber));
    }

    @GetMapping("/{slug}/departments")
    public ResponseEntity<List<DepartmentResponse>> getDepartments(
            @PathVariable String slug,
            @RequestParam(required = false) String category) {
        if (category != null && !category.isBlank()) {
            return ResponseEntity.ok(municipalityService.findDepartmentsByMunicipalitySlugAndCategory(slug, category));
        }
        return ResponseEntity.ok(municipalityService.findDepartmentsByMunicipalitySlug(slug));
    }
}