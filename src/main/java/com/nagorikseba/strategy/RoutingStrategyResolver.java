package com.nagorikseba.strategy;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class RoutingStrategyResolver {
    private final Map<String, ComplaintRoutingStrategy> strategies;

    public RoutingStrategyResolver(List<ComplaintRoutingStrategy> strategyList) {
        this.strategies = strategyList.stream()
                .collect(Collectors.toMap(ComplaintRoutingStrategy::getStrategyName, Function.identity()));
    }

    public ComplaintRoutingStrategy resolve(String strategyName) {
        ComplaintRoutingStrategy strategy = strategies.get(strategyName);
        if (strategy == null) {
            throw new IllegalArgumentException("Unknown routing strategy: " + strategyName);
        }
        return strategy;
    }
}
