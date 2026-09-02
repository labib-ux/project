package com.nagorikseba.complaint.service;

import com.nagorikseba.complaint.domain.Attachment;
import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.shared.config.StorageProperties;
import com.nagorikseba.shared.exception.FileStorageException;
import com.nagorikseba.shared.service.FileStorageService;
import com.nagorikseba.shared.time.TimeProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AttachmentService {

    private final StorageProperties storageProperties;
    private final FileStorageService fileStorageService;
    private final TimeProvider timeProvider;
    private final Tika tika = new Tika();

    private static final long MAX_FILE_SIZE = 10_485_760; // 10MB
    private static final String[] ALLOWED_MIME_TYPES = {
            "image/jpeg", "image/png", "image/webp"
    };
    private static final Path TEMP_DIR = Path.of(System.getProperty("java.io.tmpdir"), "nagorik-seba-uploads");

    @Transactional
    public List<Attachment> saveAttachments(Complaint complaint, List<MultipartFile> files) {
        List<Attachment> attachments = new ArrayList<>();
        for (MultipartFile file : files) {
            Attachment attachment = saveAttachment(complaint, file, null);
            attachments.add(attachment);
        }
        return attachments;
    }

    @Transactional
    public Attachment saveWorkProofAttachment(Complaint complaint, MultipartFile file, Long transitionId) {
        return saveAttachment(complaint, file, transitionId);
    }

    private Attachment saveAttachment(Complaint complaint, MultipartFile file, Long transitionId) {
        validateFile(file);

        String contentType = detectContentType(file);
        String checksum = computeChecksum(file);
        String extension = getExtension(contentType);
        String storageKey = generateStorageKey(complaint.getReferenceCode(), extension);

        Path tempPath = writeToTemp(file, storageKey);

        Attachment attachment = Attachment.builder()
                .complaint(complaint)
                .transitionId(transitionId != null ? Attachment.builder().id(transitionId).build() : null)
                .storageKey(storageKey)
                .originalFilename(file.getOriginalFilename())
                .contentType(contentType)
                .byteSize(file.getSize())
                .checksumSha256(checksum)
                .workProof(transitionId != null)
                .scanStatus("PENDING")
                .uploadedBy(complaint.getCitizen())
                .build();

        storeTempPath(attachment, tempPath);

        return attachment;
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new FileStorageException("File is empty");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new FileStorageException("File size exceeds 10MB limit");
        }
    }

    private String detectContentType(MultipartFile file) {
        try (InputStream is = file.getInputStream()) {
            byte[] header = is.readNBytes(12);
            String detected = tika.detect(header, file.getOriginalFilename());
            if (!isAllowedMimeType(detected)) {
                throw new FileStorageException("File type not allowed: " + detected);
            }
            return detected;
        } catch (IOException e) {
            throw new FileStorageException("Could not read file for type detection", e);
        }
    }

    private boolean isAllowedMimeType(String mimeType) {
        for (String allowed : ALLOWED_MIME_TYPES) {
            if (allowed.equalsIgnoreCase(mimeType)) {
                return true;
            }
        }
        return false;
    }

    private String computeChecksum(MultipartFile file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream is = file.getInputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new FileStorageException("Could not compute checksum", e);
        }
    }

    private String getExtension(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> "bin";
        };
    }

    private String generateStorageKey(String referenceCode, String extension) {
        LocalDate now = LocalDate.now(timeProvider.instant().atZone(java.time.ZoneOffset.UTC));
        String datePath = now.format(DateTimeFormatter.ofPattern("yyyy/MM"));
        return String.format("complaints/%s/%s/%s.%s", datePath, referenceCode.toLowerCase(), UUID.randomUUID(), extension);
    }

    private Path writeToTemp(MultipartFile file, String storageKey) {
        try {
            Files.createDirectories(TEMP_DIR);
            Path tempPath = TEMP_DIR.resolve(UUID.randomUUID() + ".tmp");
            try (InputStream is = file.getInputStream()) {
                Files.copy(is, tempPath, StandardCopyOption.REPLACE_EXISTING);
            }
            return tempPath;
        } catch (IOException e) {
            throw new FileStorageException("Could not write file to temp directory", e);
        }
    }

    private void storeTempPath(Attachment attachment, Path tempPath) {
        try {
            fileStorageService.storeTemp(attachment.getStorageKey(), tempPath);
        } catch (IOException e) {
            throw new FileStorageException("Could not store temp file reference", e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void moveTempToFinal(Attachment attachment) {
        try {
            fileStorageService.moveFromTemp(attachment.getStorageKey());
            log.info("Moved attachment {} from temp to final storage", attachment.getStorageKey());
        } catch (IOException e) {
            log.error("Failed to move attachment {} from temp to final storage", attachment.getStorageKey(), e);
        }
    }

    @Scheduled(cron = "0 0 3 * * *") // Daily at 3 AM
    @Transactional
    public void cleanupOrphanTempFiles() {
        try {
            Instant cutoff = timeProvider.instant().minusSeconds(24 * 60 * 60); // 24 hours old
            List<Path> orphanFiles = fileStorageService.listTempFilesOlderThan(cutoff);
            for (Path file : orphanFiles) {
                Files.deleteIfExists(file);
                log.info("Deleted orphan temp file: {}", file);
            }
        } catch (IOException e) {
            log.error("Error during orphan temp file cleanup", e);
        }
    }
}