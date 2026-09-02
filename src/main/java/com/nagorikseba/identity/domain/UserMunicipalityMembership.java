package com.nagorikseba.identity.domain;

import com.nagorikseba.municipality.entity.Department;
import com.nagorikseba.municipality.entity.Municipality;
import com.nagorikseba.municipality.entity.Ward;
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
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Blueprint I3 — a user's posting inside one municipality (§3.2).
 *
 * <p>Officer transfers are modelled as membership history (close the current row
 * by setting {@code validUntil}, open a new one) rather than as a mutable FK on
 * {@link User}, so past complaints stay attributable to who held the post then.
 * A row with {@code validUntil == null} is the current membership; its
 * {@code municipalityId} is what lands in the JWT {@code mids} claim.
 */
@Entity
@Table(name = "user_municipality_memberships")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserMunicipalityMembership {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "municipality_id", nullable = false)
    private Municipality municipality;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ward_id")
    private Ward ward;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    @Column(name = "valid_from", nullable = false, columnDefinition = "timestamptz")
    private Instant validFrom;

    @Column(name = "valid_until", columnDefinition = "timestamptz")
    private Instant validUntil;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    public boolean isCurrent() {
        return validUntil == null;
    }

    /** Closes the membership; the row is kept as history. */
    public void close(Instant now) {
        validUntil = now;
    }
}
