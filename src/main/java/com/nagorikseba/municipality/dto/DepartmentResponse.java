package com.nagorikseba.municipality.dto;

import com.nagorikseba.municipality.entity.Department;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DepartmentResponse {

    private Long id;
    private Long municipalityId;
    private String code;
    private String name;
    private String[] handlesCategories;
    private Boolean isActive;
    private OffsetDateTime createdAt;

    public static DepartmentResponse from(Department department) {
        return DepartmentResponse.builder()
                .id(department.getId())
                .municipalityId(department.getMunicipality() != null ? department.getMunicipality().getId() : null)
                .code(department.getCode())
                .name(department.getName())
                .handlesCategories(department.getHandlesCategories())
                .isActive(department.getIsActive())
                .createdAt(department.getCreatedAt())
                .build();
    }
}