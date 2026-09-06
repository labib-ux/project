package com.nagorikseba.complaint.routing;

import com.nagorikseba.identity.domain.User;
import com.nagorikseba.municipality.entity.Department;

/**
 * One auditable routing answer (§7.2).
 *
 * @param department  the department that must take the complaint, never null
 * @param officer     the chosen officer, or null when the department currently
 *                    has no serving officer (dept-only assignment)
 * @param strategy    which strategy produced this decision (CATEGORY,
 *                    LOAD_BALANCED, DISTANCE, MANUAL) — persisted in
 *                    {@code complaint_assignments.strategy_used}
 * @param explanation human-readable audit trail (matched category / least load
 *                    count / distance km) — persisted in
 *                    {@code complaint_assignments.strategy_explanation} and the
 *                    transition {@code metadata} JSONB
 */
public record RoutingDecision(
        Department department,
        User officer,
        String strategy,
        String explanation
) {
}
