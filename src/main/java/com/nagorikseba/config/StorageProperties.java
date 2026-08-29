package com.nagorikseba.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.storage")
public class StorageProperties {

    @NotBlank
    private String uploadDir = "uploads";

    @Min(1)
    @Max(25)
    private int maxPhotosPerComplaint = 5;
}
