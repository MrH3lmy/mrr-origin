package com.mrrorigin.notification;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Builds the {@link RestClient} {@link PostmarkEmailSender} sends through, with the configured
 * connect/read timeout applied. Kept separate from {@link PostmarkEmailSender} itself so the timeout
 * customization happens exactly once, at production wiring time -- tests build their own {@link
 * RestClient} directly from a builder bound to {@code MockRestServiceServer} and never go through this
 * class, avoiding the ordering trap where a {@code requestFactory(...)} call after {@code
 * MockRestServiceServer.bindTo(builder)} would silently discard the mock.
 */
@Configuration(proxyBeanMethods = false)
class EmailClientConfiguration {

    @Bean
    RestClient postmarkRestClient(EmailProperties properties) {
        // No RestClient.Builder bean is autoconfigured in this project (no Spring MVC client
        // autoconfiguration dependency pulls one in) -- RestClient.builder(), matching how every other
        // provider client in this codebase (StripeBackfillClient, StripeConnectClient) constructs its
        // own client directly rather than relying on an injected builder.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.requestTimeout());
        requestFactory.setReadTimeout(properties.requestTimeout());
        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
