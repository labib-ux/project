package com.nagorikseba.complaint.service;

import com.nagorikseba.complaint.domain.Attachment;
import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.domain.ComplaintTransition;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.shared.exception.FileStorageException;
import com.nagorikseba.shared.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Validates, fingerprints and stores complaint photos (§4.3, R6).
 *
 * <h2>Type checking</h2>
 * <p>The content type is <em>sniffed from the leading bytes</em> with Tika and the
 * client's own {@code Content-Type} is ignored entirely. A caller can claim
 * anything; only the magic bytes decide, and only JPEG/PNG/WebP get through. The
 * stored extension is derived from the sniffed type too, so a {@code .php} upload
 * cannot land on disk with an executable name.
 *
 * <h2>Two-phase write</h2>
 * <p>Bytes go to temp storage during the transaction and are promoted to their
 * final key only after it commits ({@link #promoteStagedFiles}). A rollback
 * therefore leaves nothing behind the web server would serve, and the daily
 * sweeper clears temp files whose transaction died before the commit hook ran.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AttachmentService {

    private final FileStorageService fileStorageService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    private final Tika tika = new Tika();

    private static final long MAX_FILE_SIZE = 10_485_760L; // 10 MB, matches the byte_size CHECK
    private static final List<String> ALLOWED_MIME_TYPES = List.of("image/jpeg", "image/png", "image/webp");
    private static final DateTimeFormatter KEY_DATE = DateTimeFormatter.ofPattern("yyyy/MM");

    /**
     * Stage every photo for a new complaint and arrange for promotion on commit.
     *
     * <p>Runs in the caller's transaction — it never opens its own, or the
     * after-commit promotion would fire against the wrong boundary.
     */
    public List<Attachment> saveAttachments(Complaint complaint, List<MultipartFile> files, User uploader) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        List<Attachment> attachments = new ArrayList<>(files.size());
        List<String> stagedKeys = new ArrayList<>(files.size());
        for (MultipartFile file : files) {
            Attachment attachment = stage(complaint, file, uploader, null);
            attachments.add(attachment);
            stagedKeys.add(attachment.getStorageKey());
        }
        eventPublisher.publishEvent(new AttachmentsStoredEvent(List.copyOf(stagedKeys)));
        return attachments;
    }

    /** Work-proof photo attached to a RESOLVE transition (used from Phase 5). */
    public Attachment saveWorkProofAttachment(Complaint complaint, MultipartFile file,
                                             User uploader, ComplaintTransition transition) {
        Attachment attachment = stage(complaint, file, uploader, transition);
        eventPublisher.publishEvent(new AttachmentsStoredEvent(List.of(attachment.getStorageKey())));
        return attachment;
    }

    private Attachment stage(Complaint complaint, MultipartFile file, User uploader, ComplaintTransition transition) {
        validateFile(file);

        String contentType = detectContentType(file);
        String checksum = computeChecksum(file);
        String storageKey = generateStorageKey(complaint.getReferenceCode(), extensionFor(contentType));

        stageBytes(file, storageKey);

        return Attachment.builder()
                .complaint(complaint)
                .transition(transition)
                .storageKey(storageKey)
                .storageProvider("LOCAL")
                .originalFilename(file.getOriginalFilename())
                .contentType(contentType)
                .byteSize(file.getSize())
                .checksumSha256(checksum)
                .workProof(transition != null)
                .scanStatus("PENDING")
                .uploadedBy(uploader)
                .createdAt(clock.instant())
                .build();
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new FileStorageException("Each photo must contain image data");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new FileStorageException("Each photo must be 10MB or smaller");
        }
    }

    private String detectContentType(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            // Tika needs only the header to identify these formats; reading the whole
            // upload into memory to decide whether to reject it would be the wrong trade.
            byte[] header = inputStream.readNBytes(64);
            String detected = tika.detect(header, file.getOriginalFilename());
            if (!ALLOWED_MIME_TYPES.contains(detected)) {
                throw new FileStorageException("Only valid JPEG, PNG, and WebP images are allowed");
            }
            return detected;
        } catch (IOException exception) {
            throw new FileStorageException("Could not read the uploaded image", exception);
        }
    }

    private String computeChecksum(MultipartFile file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream inputStream = file.getInputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = inputStream.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new FileStorageException("Could not fingerprint the uploaded image", exception);
        }
    }

    private String extensionFor(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            // Unreachable: detectContentType already rejected anything else.
            default -> throw new FileStorageException("Unsupported image type: " + contentType);
        };
    }

    /**
     * {@code complaints/{yyyy}/{MM}/{reference-code}/{uuid}.{ext}}.
     *
     * <p>Date-partitioned so a directory never grows unbounded, grouped by reference
     * code so an operator can find one complaint's evidence by eye, and named with a
     * UUID rather than the user's filename — which is untrusted, may collide, and
     * may carry a path.
     */
    private String generateStorageKey(String referenceCode, String extension) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        return "complaints/%s/%s/%s.%s".formatted(
                today.format(KEY_DATE), referenceCode.toLowerCase(), UUID.randomUUID(), extension);
    }

    private void stageBytes(MultipartFile file, String storageKey) {
        Path scratch = null;
        try {
            scratch = Files.createTempFile("nagorik-seba-", ".upload");
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, scratch, StandardCopyOption.REPLACE_EXISTING);
            }
            fileStorageService.storeTemp(storageKey, scratch);
            scratch = null; // storeTemp moved it
        } catch (IOException exception) {
            throw new FileStorageException("Could not stage the uploaded image", exception);
        } finally {
            if (scratch != null) {
                try {
                    Files.deleteIfExists(scratch);
                } catch (IOException ignored) {
                    // Best effort: the sweeper will collect it.
                }
            }
        }
    }

    /**
     * Promote staged bytes once the owning transaction has committed (R6).
     *
     * <p>Failures are logged, not rethrown: the complaint is already durable and
     * throwing here would neither undo it nor help the caller, who has long since
     * received a response. The row's {@code storage_key} records what should exist,
     * so a missed promotion is recoverable from temp storage.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void promoteStagedFiles(AttachmentsStoredEvent event) {
        for (String storageKey : event.storageKeys()) {
            try {
                fileStorageService.moveFromTemp(storageKey);
                log.debug("Promoted attachment {} to final storage", storageKey);
            } catch (IOException exception) {
                log.error("Could not promote attachment {} to final storage", storageKey, exception);
            }
        }
    }

    /**
     * Sweep temp files whose transaction never committed.
     *
     * <p>The 24-hour cutoff is deliberately generous — deleting a file that a
     * still-running transaction is about to promote would lose a citizen's evidence,
     * and stale bytes are cheap by comparison.
     */
    @Scheduled(cron = "0 15 3 * * *")
    public void cleanupOrphanTempFiles() {
        try {
            Instant cutoff = clock.instant().minusSeconds(24 * 60 * 60);
            List<Path> orphans = fileStorageService.listTempFilesOlderThan(cutoff);
            for (Path orphan : orphans) {
                Files.deleteIfExists(orphan);
                log.info("Deleted orphaned temp upload {}", orphan);
            }
            if (!orphans.isEmpty()) {
                log.info("Orphan temp sweep removed {} file(s)", orphans.size());
            }
        } catch (IOException exception) {
            log.error("Orphan temp sweep failed", exception);
        }
    }
}
