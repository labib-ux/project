package com.nagorikseba.complaint.routing;

import com.nagorikseba.complaint.domain.Complaint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Ordered routing resolution (C29–C30, §7.2).
 *
 * <p>Strategies are tried in {@code app.routing.strategy-order} sequence
 * (default {@code CATEGORY,LOAD_BALANCED,DISTANCE}); the first whose
 * {@code supports()} answers true produces the decision. Unknown names in the
 * config are ignored so a typo degrades to the remaining strategies instead of
 * breaking assignment. When nothing supports the complaint, the resolver throws
 * {@link NoEligibleDepartmentException} and the complaint stays VERIFIED.
 */
@Service
public class RoutingStrategyResolver {

    private final List<ComplaintRoutingStrategy> orderedStrategies;

    public RoutingStrategyResolver(
            List<ComplaintRoutingStrategy> strategies,
            @Value("${app.routing.strategy-order:CATEGORY,LOAD_BALANCED,DISTANCE}") String strategyOrder) {
        List<String> order = List.of(strategyOrder.split(",")).stream()
                .map(String::trim)
                .map(String::toUpperCase)
                .filter(name -> !name.isBlank())
                .toList();
        List<ComplaintRoutingStrategy> ordered = new ArrayList<>();
        for (String name : order) {
            strategies.stream()
                    .filter(strategy -> strategy.type().equalsIgnoreCase(name))
                    .findFirst()
                    .ifPresent(ordered::add);
        }
        this.orderedStrategies = List.copyOf(ordered);
    }

    /** Strategy type names in resolution order — asserted by tests. */
    public List<String> strategyOrder() {
        return orderedStrategies.stream().map(ComplaintRoutingStrategy::type).toList();
    }

    public RoutingDecision resolve(Complaint complaint) {
        for (ComplaintRoutingStrategy strategy : orderedStrategies) {
            if (strategy.supports(complaint)) {
                return strategy.route(complaint);
            }
        }
        throw new NoEligibleDepartmentException(
                "No routing strategy supports this complaint (no handling department, no posted officers, no office locations)");
    }
}
