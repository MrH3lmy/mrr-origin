package com.mrrorigin.tracking;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class TrackingConfiguration {
    @Bean
    Clock trackingClock() {
        return Clock.systemUTC();
    }
}
