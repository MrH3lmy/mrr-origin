package com.mrrorigin.tracking;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the pre-body-parsing rate-limit guard for the public ingestion endpoint only. */
@Configuration(proxyBeanMethods = false)
class IngestionRateLimitWebMvcConfiguration implements WebMvcConfigurer {

    private final IngestionRateLimitInterceptor rateLimitInterceptor;

    IngestionRateLimitWebMvcConfiguration(IngestionRateLimitInterceptor rateLimitInterceptor) {
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor).addPathPatterns("/api/public/v1/events");
    }
}
