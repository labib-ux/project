package com.nagorikseba.complaint.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttachmentResponse {
    private Long id;
    private String storageKey;
    private String originalFilename;
    private String contentType;
    private long byteSize;
    private boolean workProof;
    private String scanStatus;
    private Instant createdAt;
}