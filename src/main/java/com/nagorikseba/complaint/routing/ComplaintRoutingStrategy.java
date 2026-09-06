package com.nagorikseba.complaint.routing;

import com.nagorikseba.complaint.domain.Complaint;

/**
 * One routing rule (§7.2, typed registry with {@code supports()}).
 *
 * <p>{@code RoutingStrategyResolver} orders all such beans by
 * {@code app.routing.strategy-order} and uses the first whose
 * {@code supports()} answers true, so routing stays auditable instead of a
 * black box: every decision carries the strategy name and an explanation that
 * lands in the assignment row and the transition metadata.
 */
public interface ComplaintRoutingStrategy {

    /** CATEGORY, LOAD_BALANCED or DISTANCE — must be unique across strategies. */
    String type();

    /** Whether this strategy can answer for the complaint right now. */
    boolean supports(Complaint complaint);

    /**
     * Answer for the complaint. Only called after {@code supports()} was true;
     * throws {@link NoEligibleDepartmentException} when the answer evaporated
     * between the check and the call.
     */
    RoutingDecision route(Complaint complaint);
}
