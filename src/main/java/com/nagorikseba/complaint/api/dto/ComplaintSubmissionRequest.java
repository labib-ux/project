package com.nagorikseba.complaint.api.dto;

import com.nagorikseba.complaint.domain.enums.Category;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
public class ComplaintSubmissionRequest {

    @NotBlank(message = "A complaint title is required")
    @Size(max = 200, message = "Title must be 200 characters or fewer")
    private String title;

    @NotBlank(message = "Please describe the issue")
    @Size(max = 5000, message = "Description must be 5,000 characters or fewer")
    private String description;

    @NotNull(message = "Please select a complaint category")
    private Category category;

    @NotNull(message = "Location is required")
    @DecimalMin(value = "20.0", message = "Enter a valid Bangladesh latitude")
    @DecimalMax(value = "27.0", message = "Enter a valid Bangladesh latitude")
    private BigDecimal latitude;

    @NotNull(message = "Location is required")
    @DecimalMin(value = "88.0", message = "Enter a valid Bangladesh longitude")
    @DecimalMax(value = "93.0", message = "Enter a valid Bangladesh longitude")
    private BigDecimal longitude;

    @NotEmpty(message = "Attach at least one photo of the issue")
    @Size(max = 5, message = "You can upload up to 5 photos")
    private List<MultipartFile> photos;

    private String idempotencyKey;

    private String phone;
}