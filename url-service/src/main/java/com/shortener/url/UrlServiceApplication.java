package com.shortener.url;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * URL Service — core URL shortening, redirect, and analytics entrypoint.
 *
 * Architecture layers (enforced by ArchUnit tests):
 *   domain       → no Spring/infra dependencies
 *   application  → depends on domain + ports
 *   port         → interfaces only
 *   infrastructure → implements ports, uses Spring/Redis/Kafka/JPA
 *   api          → HTTP controllers, uses application layer only
 */
@SpringBootApplication(scanBasePackages = "com.shortener")
@EnableJpaAuditing
@EnableCaching
@EnableAsync
@EnableScheduling
public class UrlServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(UrlServiceApplication.class, args);
    }

    /**
     * Async executor for fire-and-forget tasks (cache warming, event publishing).
     * Separate pool prevents slow async work from starving HTTP threads.
     */
    @Bean("asyncExecutor")
    public Executor asyncExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(10);
        exec.setMaxPoolSize(50);
        exec.setQueueCapacity(200);
        exec.setThreadNamePrefix("async-");
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(30);
        exec.initialize();
        return exec;
    }
}
