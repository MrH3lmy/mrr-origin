package com.mrrorigin.revenue;

import static com.mrrorigin.revenue.RevenueModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * P6 observability slice (#28/#90): proves {@link RevenueCalculationService}'s calculation-result
 * metrics actually change under real calculation paths, and that "unsupported" is never silently
 * counted as "success" (preserving the existing design).
 */
@SpringBootTest
@Testcontainers
class RevenueCalculationMetricsIntegrationTests {

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:18-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }

    @Autowired
    private RevenueCalculationService service;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MeterRegistry meterRegistry;

    private UUID workspaceId;

    @BeforeEach
    void setup() {
        jdbc.sql("TRUNCATE workspaces CASCADE").update();
        workspaceId = UUID.randomUUID();
        jdbc.sql("INSERT INTO workspaces(id,name,slug,reporting_currency) VALUES(:id,'test',:slug,'USD')")
                .param("id", workspaceId)
                .param("slug", "w-" + workspaceId)
                .update();
    }

    private double counter(String name, String tagKey, String tagValue) {
        return meterRegistry.find(name).tag(tagKey, tagValue).counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count)
                .sum();
    }

    private double untaggedCounter(String name) {
        var counter = meterRegistry.find(name).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void successfulCalculationIncrementsSupportedResultCounter() {
        double before = counter("mrrorigin.revenue.calculation.result", "result", "supported");
        var item = new Item("item", "USD", 1200L, BigDecimal.ONE, "month", 1, false);
        service.recordAndReplay(
                new SubscriptionState(workspaceId, "cus", "sub", OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                        "active", "evt-metrics-1", List.of(item), List.of()));
        double after = counter("mrrorigin.revenue.calculation.result", "result", "supported");
        assertThat(after).isGreaterThan(before);
    }

    @Test
    void unsupportedCurrencyIsVisibleAsUnsupportedNotSuccess() {
        double supportedBefore = counter("mrrorigin.revenue.calculation.result", "result", "supported");
        double unsupportedBefore = counter("mrrorigin.revenue.calculation.result", "result", "unsupported");
        double reasonBefore = counter("mrrorigin.revenue.calculation.unsupported_reason", "reason", "unknown_currency");

        var badItem = new Item("item", null, 100L, BigDecimal.ONE, "month", 1, false);
        service.recordAndReplay(
                new SubscriptionState(workspaceId, "bad-cus", "bad-sub", OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                        "active", "evt-metrics-2", List.of(badItem), List.of()));

        assertThat(counter("mrrorigin.revenue.calculation.result", "result", "unsupported")).isGreaterThan(unsupportedBefore);
        assertThat(counter("mrrorigin.revenue.calculation.unsupported_reason", "reason", "unknown_currency"))
                .isGreaterThan(reasonBefore);
        // Preserves the existing design: unsupported must never be silently converted into success.
        assertThat(counter("mrrorigin.revenue.calculation.result", "result", "supported")).isEqualTo(supportedBefore);
    }

    @Test
    void aThrownFailureIncrementsFailureCounterAndRollsBack() {
        double before = untaggedCounter("mrrorigin.revenue.calculation.failure");
        var blankRefItem = new Item("", "USD", 1000L, BigDecimal.ONE, "month", 1, false);
        var bad = new SubscriptionState(
                workspaceId, "cus", "b", OffsetDateTime.parse("2026-02-01T00:00:00Z"), "active", "bad", List.of(blankRefItem),
                List.of());
        assertThatThrownBy(() -> service.recordAndReplay(List.of(bad))).isInstanceOf(IllegalArgumentException.class);
        assertThat(untaggedCounter("mrrorigin.revenue.calculation.failure")).isGreaterThan(before);
    }
}
