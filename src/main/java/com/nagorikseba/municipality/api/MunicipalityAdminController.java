package com.nagorikseba.municipality.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nagorikseba.municipality.dto.DepartmentResponse;
import com.nagorikseba.municipality.dto.MunicipalityResponse;
import com.nagorikseba.municipality.dto.WardResponse;
import com.nagorikseba.municipality.entity.Department;
import com.nagorikseba.municipality.entity.Municipality;
import com.nagorikseba.municipality.entity.Ward;
import com.nagorikseba.municipality.repository.DepartmentRepository;
import com.nagorikseba.municipality.repository.MunicipalityRepository;
import com.nagorikseba.municipality.repository.WardRepository;
import com.nagorikseba.municipality.service.WardBoundaryService;
import com.nagorikseba.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * Municipality administration: JSON APIs under {@code /api/admin} (ADMIN role
 * via the security chain) plus the Thymeleaf page under {@code /admin} served
 * by the same class, so no extra view controller is needed.
 *
 * <p>Ward boundaries arrive as GeoJSON ({@code Polygon} or
 * {@code MultiPolygon}, lng/lat order) and are validated for true overlaps
 * after insert: a ward that overlaps existing ones is removed again and the
 * call answers 400 — bad map data must never land silently.
 */
@Controller
@RequiredArgsConstructor
public class MunicipalityAdminController {

    private static final GeometryFactory GEOMETRY_FACTORY =
            new GeometryFactory(new PrecisionModel(), 4326);

    private final MunicipalityRepository municipalityRepository;
    private final WardRepository wardRepository;
    private final DepartmentRepository departmentRepository;
    private final WardBoundaryService wardBoundaryService;
    private final ObjectMapper objectMapper;

    /** Admin page: municipalities, wards and departments tables. */
    @GetMapping("/admin/municipalities")
    public String municipalitiesPage(Model model) {
        model.addAttribute("municipalities", municipalityRepository.findAll());
        return "admin/municipalities";
    }

    @GetMapping("/api/admin/municipalities")
    @ResponseBody
    public List<MunicipalityResponse> listMunicipalities() {
        return municipalityRepository.findAll().stream().map(MunicipalityResponse::from).toList();
    }

    public record MunicipalityBody(String slug, String name, String nameBn, Boolean isActive) {
    }

    @PostMapping("/api/admin/municipalities")
    @ResponseBody
    @Transactional
    public MunicipalityResponse createMunicipality(@RequestBody MunicipalityBody body) {
        if (body.slug() == null || body.slug().isBlank() || body.name() == null || body.name().isBlank()) {
            throw new IllegalArgumentException("slug and name are required");
        }
        Municipality saved = municipalityRepository.save(Municipality.builder()
                .slug(body.slug().trim())
                .name(body.name().trim())
                .nameBn(body.nameBn())
                .isActive(body.isActive() == null || body.isActive())
                .build());
        return MunicipalityResponse.from(saved);
    }

    @PutMapping("/api/admin/municipalities/{id}")
    @ResponseBody
    @Transactional
    public MunicipalityResponse updateMunicipality(@PathVariable Long id,
                                                   @RequestBody MunicipalityBody body) {
        Municipality municipality = municipalityRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Municipality not found: " + id));
        if (body.name() != null && !body.name().isBlank()) {
            municipality.setName(body.name().trim());
        }
        if (body.nameBn() != null) {
            municipality.setNameBn(body.nameBn());
        }
        if (body.isActive() != null) {
            municipality.setIsActive(body.isActive());
        }
        return MunicipalityResponse.from(municipalityRepository.save(municipality));
    }

    @GetMapping("/api/admin/wards")
    @ResponseBody
    public List<WardResponse> listWards() {
        return wardRepository.findAll().stream().map(WardResponse::from).toList();
    }

    public record WardGeoJsonBody(Long municipalityId, Integer wardNumber, String areaName,
                                  JsonNode geojson) {
    }

    /** Create a ward from an uploaded GeoJSON polygon, overlap-checked. */
    @PostMapping("/api/admin/wards/geojson")
    @ResponseBody
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public WardResponse createWardFromGeoJson(@RequestBody WardGeoJsonBody body) {
        Municipality municipality = municipalityRepository.findById(body.municipalityId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Municipality not found: " + body.municipalityId()));
        if (body.wardNumber() == null || body.wardNumber() <= 0
                || body.areaName() == null || body.areaName().isBlank() || body.geojson() == null) {
            throw new IllegalArgumentException("municipalityId, positive wardNumber, areaName and geojson are required");
        }
        Geometry boundary = parseGeoJson(body.geojson());
        Ward saved = wardRepository.save(Ward.builder()
                .municipality(municipality)
                .wardNumber(body.wardNumber())
                .areaName(body.areaName().trim())
                .boundary(boundary)
                .isActive(true)
                .build());
        boolean overlaps = wardBoundaryService.validateBoundaryOverlap(municipality.getId()).stream()
                .anyMatch(pair -> pair.firstWardId().equals(saved.getId())
                        || pair.secondWardId().equals(saved.getId()));
        if (overlaps) {
            wardRepository.delete(saved);
            throw new IllegalArgumentException(
                    "Ward boundary overlaps existing wards and was not saved");
        }
        return WardResponse.from(saved);
    }

    public record DepartmentBody(Long municipalityId, String code, String name,
                                 List<String> handlesCategories, Boolean isActive) {
    }

    @GetMapping("/api/admin/departments")
    @ResponseBody
    public List<DepartmentResponse> listDepartments() {
        return departmentRepository.findAll().stream().map(DepartmentResponse::from).toList();
    }

    @PostMapping("/api/admin/departments")
    @ResponseBody
    @Transactional
    public DepartmentResponse createDepartment(@RequestBody DepartmentBody body) {
        Municipality municipality = municipalityRepository.findById(body.municipalityId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Municipality not found: " + body.municipalityId()));
        if (body.code() == null || body.code().isBlank() || body.name() == null || body.name().isBlank()) {
            throw new IllegalArgumentException("municipalityId, code and name are required");
        }
        Department saved = departmentRepository.save(Department.builder()
                .municipality(municipality)
                .code(body.code().trim().toUpperCase())
                .name(body.name().trim())
                .handlesCategories(body.handlesCategories() != null
                        ? body.handlesCategories().toArray(new String[0]) : new String[0])
                .isActive(body.isActive() == null || body.isActive())
                .build());
        return DepartmentResponse.from(saved);
    }

    /** Handles-categories editor: replace the category set and active flag. */
    @PutMapping("/api/admin/departments/{id}")
    @ResponseBody
    @Transactional
    public DepartmentResponse updateDepartment(@PathVariable Long id,
                                               @RequestBody DepartmentBody body) {
        Department department = departmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Department not found: " + id));
        if (body.name() != null && !body.name().isBlank()) {
            department.setName(body.name().trim());
        }
        if (body.handlesCategories() != null) {
            department.setHandlesCategories(body.handlesCategories().toArray(new String[0]));
        }
        if (body.isActive() != null) {
            department.setIsActive(body.isActive());
        }
        return DepartmentResponse.from(departmentRepository.save(department));
    }

    private Geometry parseGeoJson(JsonNode geojson) {
        try {
            String type = geojson.path("type").asText("");
            JsonNode coordinates = geojson.path("coordinates");
            if ("Polygon".equalsIgnoreCase(type)) {
                return GEOMETRY_FACTORY.createMultiPolygon(
                        new Polygon[]{toPolygon(coordinates.get(0))});
            }
            if ("MultiPolygon".equalsIgnoreCase(type)) {
                List<Polygon> polygons = new ArrayList<>();
                coordinates.forEach(polygon -> polygons.add(toPolygon(polygon.get(0))));
                return GEOMETRY_FACTORY.createMultiPolygon(polygons.toArray(new Polygon[0]));
            }
            throw new IllegalArgumentException("GeoJSON type must be Polygon or MultiPolygon");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid GeoJSON boundary: " + e.getMessage());
        }
    }

    private Polygon toPolygon(JsonNode ring) {
        List<Coordinate> coords = new ArrayList<>();
        ring.forEach(point -> coords.add(new Coordinate(point.get(0).asDouble(), point.get(1).asDouble())));
        if (coords.size() < 4) {
            throw new IllegalArgumentException("Polygon ring needs at least 4 positions");
        }
        if (!coords.get(0).equals2D(coords.get(coords.size() - 1))) {
            coords.add(new Coordinate(coords.get(0)));
        }
        LinearRing shell = GEOMETRY_FACTORY.createLinearRing(coords.toArray(new Coordinate[0]));
        return GEOMETRY_FACTORY.createPolygon(shell);
    }

}
