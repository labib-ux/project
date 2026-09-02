package com.nagorikseba.complaint.domain;

import com.nagorikseba.complaint.domain.enums.Category;
import com.nagorikseba.complaint.domain.enums.ComplaintStatus;
import com.nagorikseba.complaint.domain.enums.LocationSource;
import com.nagorikseba.complaint.domain.enums.ModerationStatus;
import com.nagorikseba.complaint.domain.enums.Priority;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.municipality.entity.Department;
import com.nagorikseba.municipality.entity.Municipality;
import com.nagorikseba.municipality.entity.Ward;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Point;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The complaint aggregate root (§4.1).
 *
 * <h2>Status invariant</h2>
 * <p>There is deliberately no public {@code setStatus}. Every status change — and
 * every timestamp, moderation flag and assignment that accompanies one — is
 * package-private and reachable only through {@link ComplaintMutator}, the
 * capability base class that {@code TransitionHandler} implementations extend.
 * That keeps §7.1's rule ("the only code that may change status is a transition
 * handler") enforced by the compiler rather than by convention, so a controller
 * or seeder cannot flip a complaint into VERIFIED without going through the
 * lifecycle service's locking, version check and audit write.
 *
 * <p>Fields a citizen legitimately owns after submission ({@code title},
 * {@code description}) keep ordinary setters. Everything else is set once at
 * construction through the builder.
 */
@Entity
@Table(name = "complaints")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Complaint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reference_code", nullable = false, unique = true, length = 20)
    private String referenceCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "municipality_id", nullable = false)
    private Municipality municipality;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ward_id")
    private Ward ward;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "citizen_id")
    private User citizen;

    @Column(name = "anonymous_contact_phone", length = 20)
    private String anonymousContactPhone;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private ComplaintStatus status = ComplaintStatus.SUBMITTED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Priority priority = Priority.NORMAL;

    @JdbcTypeCode(SqlTypes.GEOGRAPHY)
    @Column(name = "location", nullable = false, columnDefinition = "geography(Point,4326)")
    private Point location;

    @Enumerated(EnumType.STRING)
    @Column(name = "location_source", nullable = false, length = 20)
    @Builder.Default
    private LocationSource locationSource = LocationSource.DEVICE;

    @Column(name = "address_text", length = 300)
    private String addressText;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_department_id")
    private Department assignedDepartment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_officer_id")
    private User assignedOfficer;

    /**
     * Set explicitly by the submission template and the seeder — deliberately not
     * {@code @CreationTimestamp}, because a generated value would silently
     * overwrite the historical dates demo and test fixtures depend on.
     */
    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;

    @Column(name = "first_verified_at")
    private Instant firstVerifiedAt;

    @Column(name = "first_assigned_at")
    private Instant firstAssignedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "last_transition_at")
    private Instant lastTransitionAt;

    @Column(name = "reopen_count", nullable = false)
    @Builder.Default
    private int reopenCount = 0;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "cancellation_reason", columnDefinition = "TEXT")
    private String cancellationReason;

    @Column(name = "is_public_visible", nullable = false)
    @Builder.Default
    private boolean publicVisible = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "moderation_status", nullable = false, length = 20)
    @Builder.Default
    private ModerationStatus moderationStatus = ModerationStatus.APPROVED;

    /** R3 — the key that made this complaint, so a replayed submission returns it. */
    @Column(name = "submission_idempotency_key", length = 64)
    private String submissionIdempotencyKey;

    @Version
    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    private Instant updatedAt;

    @OneToMany(mappedBy = "complaint", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ComplaintTransition> transitions = new ArrayList<>();

    @OneToMany(mappedBy = "complaint", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Attachment> attachments = new ArrayList<>();

    // ------------------------------------------------------------------ citizen-owned

    public void setTitle(String title) {
        this.title = title;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Ward is resolved by a spatial lookup just after insert (§7.5), and may be
     * re-derived later if ward boundaries are redrawn. It is deliberately not behind
     * {@link ComplaintMutator}: which ward contains a point is geography, not a state
     * transition, so it needs none of the lifecycle guarantees.
     */
    public void assignWard(Ward ward) {
        this.ward = ward;
    }

    // ------------------------------------------------------------------ collections

    public void addTransition(ComplaintTransition transition) {
        transitions.add(transition);
        transition.setComplaint(this);
    }

    public void addAttachment(Attachment attachment) {
        attachments.add(attachment);
        attachment.setComplaint(this);
    }

    /** Defensive copies — callers must not mutate the audit log through the getter. */
    public List<ComplaintTransition> getTransitions() {
        return Collections.unmodifiableList(transitions);
    }

    public List<Attachment> getAttachments() {
        return Collections.unmodifiableList(attachments);
    }

    public boolean isAnonymous() {
        return citizen == null;
    }

    public boolean canTransitionFrom(ComplaintStatus from) {
        return this.status == from;
    }

    // ------------------------------------------------ lifecycle-only (see ComplaintMutator)

    void setStatus(ComplaintStatus status) {
        this.status = status;
    }

    void setPriority(Priority priority) {
        this.priority = priority;
    }

    void setFirstVerifiedAt(Instant firstVerifiedAt) {
        this.firstVerifiedAt = firstVerifiedAt;
    }

    void setFirstAssignedAt(Instant firstAssignedAt) {
        this.firstAssignedAt = firstAssignedAt;
    }

    void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    void setClosedAt(Instant closedAt) {
        this.closedAt = closedAt;
    }

    void setLastTransitionAt(Instant lastTransitionAt) {
        this.lastTransitionAt = lastTransitionAt;
    }

    void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    void setCancellationReason(String cancellationReason) {
        this.cancellationReason = cancellationReason;
    }

    void setPublicVisible(boolean publicVisible) {
        this.publicVisible = publicVisible;
    }

    void setModerationStatus(ModerationStatus moderationStatus) {
        this.moderationStatus = moderationStatus;
    }

    void setAssignedDepartment(Department assignedDepartment) {
        this.assignedDepartment = assignedDepartment;
    }

    void setAssignedOfficer(User assignedOfficer) {
        this.assignedOfficer = assignedOfficer;
    }

    void incrementReopenCount() {
        this.reopenCount++;
    }
}
