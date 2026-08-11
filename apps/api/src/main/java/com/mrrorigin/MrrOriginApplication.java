package com.mrrorigin;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@ConfigurationPropertiesScan
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
