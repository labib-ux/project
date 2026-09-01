package com.nagorikseba.municipality.dto;

import com.nagorikseba.municipality.entity.Municipality;
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
public class MunicipalityResponse {

    private Long id;
    private String slug;
    private String name;
    private String nameBn;
    private Boolean isActive;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public static MunicipalityResponse from(Municipality municipality) {
        return MunicipalityResponse.builder()
                .id(municipality.getId())
                .slug(municipality.getSlug())
                .name(municipality.getName())
                .nameBn(municipality.getNameBn())
                .isActive(municipality.getIsActive())
                .createdAt(municipality.getCreatedAt())
                .updatedAt(municipality.getUpdatedAt())
                .build();
    }
}