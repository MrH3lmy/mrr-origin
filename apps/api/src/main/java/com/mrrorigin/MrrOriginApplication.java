package com.mrrorigin;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @EnableScheduling} drives every in-process scheduled job in this codebase: #59's
 * weekly-summary dispatch/retention ticks, workspace tombstone purge, and #92's bounded Stripe
 * webhook normalization and attribution recalculation drivers. Per ARCHITECTURE.md, horizontal safety
 * across multiple instances comes from each job's own DB-backed lease/claim/checkpoint design, never
 * from anything scheduling-framework-level.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class MrrOriginApplication {

    public static void main(String[] args) {
        SpringApplication.run(MrrOriginApplication.class, args);
    }

    /** Injectable system clock, so time-sensitive checks (e.g. webhook signature freshness) are testable. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
