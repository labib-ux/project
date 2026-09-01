package com.nagorikseba.municipality.dto;

import com.nagorikseba.municipality.entity.Ward;
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
public class WardResponse {

    private Long id;
    private Long municipalityId;
    private Integer wardNumber;
    private String areaName;
    private String areaNameBn;
    private Boolean isActive;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public static WardResponse from(Ward ward) {
        return WardResponse.builder()
                .id(ward.getId())
                .municipalityId(ward.getMunicipality() != null ? ward.getMunicipality().getId() : null)
                .wardNumber(ward.getWardNumber())
                .areaName(ward.getAreaName())
                .areaNameBn(ward.getAreaNameBn())
                .isActive(ward.getIsActive())
                .createdAt(ward.getCreatedAt())
                .updatedAt(ward.getUpdatedAt())
                .build();
    }
}