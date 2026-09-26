package com.shortify.config;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableScheduling
public class BackgroundConfiguration {

    @Bean
    ThreadPoolTaskExecutor analyticsExecutor(@Value("${shortify.analytics.workers}") int workers,
                                            @Value("${shortify.analytics.queue-capacity}") int capacity) {
        if (workers < 1 || workers > 32 || capacity < 1 || capacity > 100_000) {
            throw new IllegalArgumentException("Analytics requires 1-32 workers and queue capacity 1-100000.");
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(workers);
        executor.setMaxPoolSize(workers);
        executor.setQueueCapacity(capacity);
        executor.setThreadNamePrefix("analytics-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
