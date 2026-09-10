package com.example.notification.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class DeliveryExecutorConfiguration {

    @Bean(name = "deliveryExecutor")
    public ThreadPoolTaskExecutor deliveryExecutor(NotificationProperties properties) {
        int concurrency = Math.max(1, properties.worker().concurrency());
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(concurrency * 2);
        executor.setThreadNamePrefix("notification-delivery-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds((int) properties.worker().leaseSeconds());
        executor.initialize();
        return executor;
    }
}
