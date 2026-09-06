package com.nagorikseba.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Shared executors (Phase 5).
 *
 * <p>{@code outboxExecutor} backs the outbox relay's parallel dispatch: each
 * claimed row is delivered on this pool inside its own transaction while the
 * batch method waits for all of them.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("outboxExecutor")
    public Executor outboxExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("outbox-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }
}
