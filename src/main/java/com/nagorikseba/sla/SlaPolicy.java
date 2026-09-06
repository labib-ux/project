package com.nagorikseba.sla;

import com.nagorikseba.complaint.domain.enums.Category;
import com.nagorikseba.complaint.domain.enums.Priority;
import com.nagorikseba.municipality.entity.Municipality;
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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * One active SLA policy per municipality/category/priority (§3.4, L1).
 *
 * <p>Policy edits never rewrite history: instances snapshot their deadline at
 * creation, so changing a policy only affects complaints submitted afterwards.
 */
@Entity
@Table(name = "sla_policies")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SlaPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "municipality_id", nullable = false)
    private Municipality municipality;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Priority priority;

    @Column(name = "max_hours", nullable = false)
    private int maxHours;

    @Column(name = "escalation_level_1_hours")
    private Integer escalationLevel1Hours;

    @Column(name = "escalation_level_2_hours")
    private Integer escalationLevel2Hours;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
