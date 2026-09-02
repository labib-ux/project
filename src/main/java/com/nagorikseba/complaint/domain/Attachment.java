package com.nagorikseba.complaint.domain;

import com.nagorikseba.identity.domain.User;
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

import java.time.Instant;

/**
 * An uploaded photo, hardened per §4.3.
 *
 * <p>{@code storageKey} is the only handle to the bytes — it is unique, and the
 * bytes themselves live under it in whichever provider {@code storageProvider}
 * names, so swapping local disk for S3 later needs no schema change.
 * {@code checksumSha256} is computed on the way in for integrity and duplicate
 * detection; {@code contentType} is the <em>sniffed</em> type, never the
 * client-declared one.
 *
 * <p>Deletion is soft ({@code deletedAt}): evidence attached to a civic complaint
 * should not vanish from the audit trail on a citizen's whim, so queries filter
 * on {@code deleted_at IS NULL} instead.
 */
@Entity
@Table(name = "attachments")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Attachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "complaint_id", nullable = false)
    private Complaint complaint;

    /** Set only for work-proof photos uploaded alongside a RESOLVE (Phase 5). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transition_id")
    private ComplaintTransition transition;

    @Column(name = "storage_key", nullable = false, unique = true, length = 300)
    private String storageKey;

    @Column(name = "storage_provider", nullable = false, length = 20)
    @Builder.Default
    private String storageProvider = "LOCAL";

    @Column(name = "original_filename", length = 255)
    private String originalFilename;

    /** Magic-byte detected, not the client's Content-Type header. */
    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "byte_size", nullable = false)
    private long byteSize;

    @Column(name = "checksum_sha256", nullable = false, length = 64)
    private String checksumSha256;

    @Column(name = "is_work_proof", nullable = false)
    @Builder.Default
    private boolean workProof = false;

    /**
     * Free-text rather than an enum: the antivirus pipeline is Phase 5+ and may
     * report scanner-specific verdicts we do not want to model yet. Values in use
     * today are PENDING, CLEAN and INFECTED.
     */
    @Column(name = "scan_status", nullable = false, length = 20)
    @Builder.Default
    private String scanStatus = "PENDING";

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by")
    private User uploadedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /** Takes the instant from the caller's {@code Clock} — no wall-clock reads in entities. */
    public void softDelete(Instant at) {
        this.deletedAt = at;
    }

    public void markScanned(String status) {
        this.scanStatus = status;
    }

    void setComplaint(Complaint complaint) {
        this.complaint = complaint;
    }
}
