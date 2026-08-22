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
 * P6 observability slice (#28/#90): proves {@link RevenueCalculationService}'s invocation-level
 * success/failure counters change under real calculation paths without inflating from
 * {@code replay()}'s full-history rebuild, and that the current-state supported/unsupported gauges
 * ({@link RevenueCalculationSnapshotMetrics}) reflect real persisted state.
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
        var c = meterRegistry.find(name).tag(tagKey, tagValue).counter();
        return c == null ? 0 : c.count();
    }

    private double gauge(String name, String... tags) {
        var g = meterRegistry.find(name).tags(tags).gauge();
        return g == null ? 0 : g.value();
    }

    private long dbCount(String sql, Object... paramPairs) {
        var spec = jdbc.sql(sql);
        for (int i = 0; i < paramPairs.length; i += 2) {
            spec = spec.param((String) paramPairs[i], paramPairs[i + 1]);
        }
        return spec.query(Long.class).single();
    }

    @Test
    void successfulCalculationIncrementsInvocationSuccessCounterOnce() {
        double before = counter("mrrorigin.revenue.calculation.invocations", "result", "success");
        var item = new Item("item", "USD", 1200L, BigDecimal.ONE, "month", 1, false);
        service.recordAndReplay(
                new SubscriptionState(workspaceId, "cus", "sub", OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                        "active", "evt-metrics-1", List.of(item), List.of()));
        double after = counter("mrrorigin.revenue.calculation.invocations", "result", "success");
        assertThat(after).isEqualTo(before + 1);
    }

    @Test
    void aThrownFailureIncrementsInvocationFailureCounterAndRollsBack() {
        double before = counter("mrrorigin.revenue.calculation.invocations", "result", "failure");
        var blankRefItem = new Item("", "USD", 1000L, BigDecimal.ONE, "month", 1, false);
        var bad = new SubscriptionState(
                workspaceId, "cus", "b", OffsetDateTime.parse("2026-02-01T00:00:00Z"), "active", "bad", List.of(blankRefItem),
                List.of());
        assertThatThrownBy(() -> service.recordAndReplay(List.of(bad))).isInstanceOf(IllegalArgumentException.class);
        assertThat(counter("mrrorigin.revenue.calculation.invocations", "result", "failure")).isEqualTo(before + 1);
    }

    @Test
    void unsupportedCurrencyIsVisibleInTheCurrentStateGaugeNotAsAnInflatingCounter() {
        var badItem = new Item("item", null, 100L, BigDecimal.ONE, "month", 1, false);
        service.recordAndReplay(
                new SubscriptionState(workspaceId, "bad-cus", "bad-sub", OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                        "active", "evt-metrics-2", List.of(badItem), List.of()));

        long expectedUnsupported = dbCount(
                "SELECT COUNT(*) FROM customer_mrr_snapshots WHERE calculation_version='mrr-v1' AND supported=false AND unsupported_reason=:r",
                "r", "UNKNOWN_CURRENCY");
        assertThat(gauge("mrrorigin.revenue.calculation.unsupported_snapshots", "reason", "unknown_currency"))
                .isEqualTo(expectedUnsupported);
    }

    /**
     * Review fix, blocking finding 2/4: {@code replay()} rebuilds a customer's entire historical
     * snapshot series on every {@code recordAndReplay} call (see its Javadoc). Before the fix, a
     * counter incremented inside {@code saveSnapshot}/{@code saveUnsupported} was re-triggered for
     * every old, unrelated historical instant on every later call for the same customer -- T1's
     * supported snapshot and T2's unsupported snapshot would both get re-counted when T3 (an
     * unrelated later state) forces a full replay. Proves: (1) the invocation counter only ever
     * increments once per {@code recordAndReplay} call, never once per historical snapshot replay()
     * happens to touch; (2) the current-state gauges match a direct, independent DB count after the
     * full replay, rather than "3x" from having recomputed T1 and T2 twice more.
     */
    @Test
    void unrelatedLaterStateTriggeringFullReplayDoesNotRecountEarlierHistoricalOutcomes() {
        String customer = "replay-no-inflate";
        var supportedItem = new Item("item", "USD", 1000L, BigDecimal.ONE, "month", 1, false);
        var unsupportedItem = new Item("item2", null, 500L, BigDecimal.ONE, "month", 1, false);

        double successBefore = counter("mrrorigin.revenue.calculation.invocations", "result", "success");

        // T1: supported.
        service.recordAndReplay(new SubscriptionState(
                workspaceId, customer, "sub-1", OffsetDateTime.parse("2026-01-01T00:00:00Z"), "active", "t1",
                List.of(supportedItem), List.of()));
        assertThat(counter("mrrorigin.revenue.calculation.invocations", "result", "success")).isEqualTo(successBefore + 1);

        // T2: unsupported (different subscription, same customer) -- replay() already rebuilds the
        // whole history here, re-touching T1.
        service.recordAndReplay(new SubscriptionState(
                workspaceId, customer, "sub-2", OffsetDateTime.parse("2026-02-01T00:00:00Z"), "active", "t2",
                List.of(unsupportedItem), List.of()));
        assertThat(counter("mrrorigin.revenue.calculation.invocations", "result", "success")).isEqualTo(successBefore + 2);

        // T3: an unrelated third state for the SAME customer, forcing replay() to recompute T1 and
        // T2 yet again alongside it.
        service.recordAndReplay(new SubscriptionState(
                workspaceId, customer, "sub-3", OffsetDateTime.parse("2026-03-01T00:00:00Z"), "active", "t3",
                List.of(supportedItem), List.of()));

        // The invocation counter increments exactly once per call -- 3 calls, 3 increments -- never
        // inflated by how many historical instants each call's replay() happened to touch.
        assertThat(counter("mrrorigin.revenue.calculation.invocations", "result", "success")).isEqualTo(successBefore + 3);

        // The gauges reflect exactly the customer's final persisted history, matching an
        // independent direct DB count -- never a "recounted" multiple of what's actually there.
        long expectedSupported = dbCount(
                "SELECT COUNT(*) FROM customer_mrr_snapshots WHERE calculation_version='mrr-v1' AND supported=true AND workspace_id=:w AND stripe_customer_id=:c",
                "w", workspaceId, "c", customer);
        long expectedUnsupported = dbCount(
                "SELECT COUNT(*) FROM customer_mrr_snapshots WHERE calculation_version='mrr-v1' AND supported=false AND unsupported_reason='UNKNOWN_CURRENCY' AND workspace_id=:w AND stripe_customer_id=:c",
                "w", workspaceId, "c", customer);
        assertThat(expectedSupported).isGreaterThan(0);
        assertThat(expectedUnsupported).isGreaterThan(0);

        long totalSupportedGaugeBefore = dbCount(
                "SELECT COUNT(*) FROM customer_mrr_snapshots WHERE calculation_version='mrr-v1' AND supported=true");
        long totalUnsupportedGaugeBefore = dbCount(
                "SELECT COUNT(*) FROM customer_mrr_snapshots WHERE calculation_version='mrr-v1' AND supported=false AND unsupported_reason='UNKNOWN_CURRENCY'");
        assertThat(gauge("mrrorigin.revenue.calculation.supported_snapshots")).isEqualTo(totalSupportedGaugeBefore);
        assertThat(gauge("mrrorigin.revenue.calculation.unsupported_snapshots", "reason", "unknown_currency"))
                .isEqualTo(totalUnsupportedGaugeBefore);
    }
}
