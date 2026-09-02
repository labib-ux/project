package com.nagorikseba.identity.domain;

import com.nagorikseba.enums.UserRole;
import com.nagorikseba.municipality.entity.Department;
import com.nagorikseba.municipality.entity.Ward;
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
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Duration;
import java.time.Instant;

/**
 * Blueprint I1 — hardened identity root (§3.2).
 *
 * <p>Either {@code email} (CITEXT, case-insensitive) or {@code phone} (canonical
 * {@code 01XXXXXXXXX}) identifies the account; the database enforces that at
 * least one is present. Lockout counters and {@code version} live here so the
 * login path can harden without a second table.
 *
 * <p>{@code ward}/{@code department} are retained from the V1 schema for the
 * Phase 1 authority screens. {@link UserMunicipalityMembership} is the
 * authoritative tenancy record from Phase 3 onwards.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** CITEXT: unique and comparable case-insensitively at the index level. */
    @Column(name = "email", unique = true, columnDefinition = "citext")
    private String email;

    @Column(name = "phone", unique = true, length = 20)
    private String phone;

    /** BCrypt hash (strength 12). Null only for password-less CITIZEN records. */
    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private UserRole role;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ward_id")
    private Ward ward;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "failed_login_count", nullable = false)
    @Builder.Default
    private int failedLoginCount = 0;

    @Column(name = "locked_until", columnDefinition = "timestamptz")
    private Instant lockedUntil;

    @Column(name = "last_login_at", columnDefinition = "timestamptz")
    private Instant lastLoginAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private int version;

    /** The value that goes into the JWT {@code sub} claim: email when present, else phone. */
    public String canonicalIdentifier() {
        return email != null ? email : phone;
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /**
     * Records a failed password attempt and locks the account once the threshold
     * is reached. Returns {@code true} when this attempt caused the lock.
     *
     * <p>An expired lock resets the counter first, so a user who waited out a lock
     * gets a full fresh window instead of being re-locked by a single typo.
     */
    public boolean registerFailedLogin(int maxAttempts, Duration lockDuration, Instant now) {
        if (lockedUntil != null && !lockedUntil.isAfter(now)) {
            lockedUntil = null;
            failedLoginCount = 0;
        }
        failedLoginCount = failedLoginCount + 1;
        if (failedLoginCount >= maxAttempts) {
            lockedUntil = now.plus(lockDuration);
            return true;
        }
        return false;
    }

    /** Clears lockout state after a successful password check. */
    public void registerSuccessfulLogin(Instant now) {
        failedLoginCount = 0;
        lockedUntil = null;
        lastLoginAt = now;
    }
}
