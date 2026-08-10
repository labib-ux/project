package com.nagorikseba.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "wards")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Ward {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ward_number", nullable = false)
    private Integer wardNumber;

    @Column(name = "area_name", nullable = false, length = 100)
    private String areaName;

    @Column(name = "city_corporation", nullable = false, length = 50)
    private String cityCorporation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "councilor_id")
    private User councilor;
}
