package com.storesync.shared.config;

import com.storesync.outbox.application.Outbox;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class CoreConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    NewTopic salesTopic() {
        return TopicBuilder.name(Outbox.SALES).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic stockTopic() {
        return TopicBuilder.name(Outbox.STOCK).partitions(3).replicas(1).build();
    }
}
