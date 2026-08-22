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
 * ({@link RevenueCalculationSnapshotMetrics}) reflect each customer's *latest* persisted state --
 * never a monotonically-accumulating historical inventory.
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

    private void recordState(String customer, String subscription, String at, Item item) {
        service.recordAndReplay(new SubscriptionState(
                workspaceId, customer, subscription, OffsetDateTime.parse(at), "active", at + "-" + subscription,
                List.of(item), List.of()));
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
    void unsupportedCurrencyIsVisibleInTheCurrentStateGauge() {
        var badItem = new Item("item", null, 100L, BigDecimal.ONE, "month", 1, false);
        service.recordAndReplay(
                new SubscriptionState(workspaceId, "bad-cus", "bad-sub", OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                        "active", "evt-metrics-2", List.of(badItem), List.of()));

        // A fresh customer with a single unsupported instant: exactly one customer is currently
        // unsupported for this reason, and none are currently supported.
        assertThat(gauge("mrrorigin.revenue.calculation.unsupported_snapshots", "reason", "unknown_currency")).isEqualTo(1);
        assertThat(gauge("mrrorigin.revenue.calculation.supported_snapshots")).isEqualTo(0);
    }

    /**
     * Review fix, blocking finding 2/4: {@code replay()} rebuilds a customer's entire historical
     * snapshot series on every {@code recordAndReplay} call (see its Javadoc) -- a counter
     * incremented inside {@code saveSnapshot}/{@code saveUnsupported} would re-trigger for every old,
     * unrelated historical instant every time a later state for the same customer forces a fresh
     * replay. Proves the invocation counter increments exactly once per call, never once per
     * historical snapshot {@code replay()} happens to touch: 3 calls for the same customer/
     * subscription (each one replaying everything before it) still produce exactly 3 increments.
     */
    @Test
    void repeatedReplayForTheSameCustomerNeverInflatesTheInvocationCounter() {
        String customer = "replay-no-inflate";
        var supported = new Item("item", "USD", 1000L, BigDecimal.ONE, "month", 1, false);
        var unsupported = new Item("item", null, 500L, BigDecimal.ONE, "month", 1, false);
        var supportedAgain = new Item("item", "USD", 2000L, BigDecimal.ONE, "month", 1, false);

        double successBefore = counter("mrrorigin.revenue.calculation.invocations", "result", "success");

        recordState(customer, "sub-1", "2026-01-01T00:00:00Z", supported); // T1
        assertThat(counter("mrrorigin.revenue.calculation.invocations", "result", "success")).isEqualTo(successBefore + 1);

        // T2 replaces sub-1's state -- replay() rebuilds T1 too, alongside it.
        recordState(customer, "sub-1", "2026-02-01T00:00:00Z", unsupported);
        assertThat(counter("mrrorigin.revenue.calculation.invocations", "result", "success")).isEqualTo(successBefore + 2);

        // T3 is a further, unrelated change to the same subscription -- replay() rebuilds T1 and T2
        // yet again alongside it.
        recordState(customer, "sub-1", "2026-03-01T00:00:00Z", supportedAgain);

        // Exactly 3 calls, exactly 3 increments -- never inflated by how many historical instants
        // each call's replay() happened to touch.
        assertThat(counter("mrrorigin.revenue.calculation.invocations", "result", "success")).isEqualTo(successBefore + 3);
    }

    /**
     * Review fix (second round): {@code customer_mrr_snapshots} is itself an append-only history, so
     * a naive {@code COUNT(*) WHERE supported = false} would accumulate a customer's old unsupported
     * row forever, even after they recover -- keeping an operational "is anything unsupported right
     * now" alert firing long after the fact. Proves the gauge instead reflects only each customer's
     * *latest* persisted state: T1 unsupported, T2 (same customer/subscription) supported, the
     * historical T1 row still exists untouched, but the current-state gauge has moved to 0.
     */
    @Test
    void aCustomerRecoveringFromUnsupportedIsNoLongerCountedInTheCurrentStateGauge() {
        String customer = "recovers-from-unsupported";
        var badItem = new Item("item", null, 100L, BigDecimal.ONE, "month", 1, false);
        var goodItem = new Item("item", "USD", 1000L, BigDecimal.ONE, "month", 1, false);

        // T1: unsupported.
        recordState(customer, "sub-1", "2026-01-01T00:00:00Z", badItem);
        assertThat(gauge("mrrorigin.revenue.calculation.unsupported_snapshots", "reason", "unknown_currency")).isEqualTo(1);
        assertThat(gauge("mrrorigin.revenue.calculation.supported_snapshots")).isEqualTo(0);

        long historicalUnsupportedRows = dbCount(
                "SELECT COUNT(*) FROM customer_mrr_snapshots WHERE workspace_id=:w AND stripe_customer_id=:c AND supported=false",
                "w", workspaceId, "c", customer);
        assertThat(historicalUnsupportedRows).isEqualTo(1);

        // T2: the SAME customer/subscription later becomes supported.
        recordState(customer, "sub-1", "2026-02-01T00:00:00Z", goodItem);

        // The historical T1 row is untouched by the recovery.
        long historicalUnsupportedRowsAfterRecovery = dbCount(
                "SELECT COUNT(*) FROM customer_mrr_snapshots WHERE workspace_id=:w AND stripe_customer_id=:c AND supported=false",
                "w", workspaceId, "c", customer);
        assertThat(historicalUnsupportedRowsAfterRecovery).isEqualTo(1);

        // But the operational current-state gauge reflects the recovery, not the stale historical row.
        assertThat(gauge("mrrorigin.revenue.calculation.unsupported_snapshots", "reason", "unknown_currency")).isEqualTo(0);
        assertThat(gauge("mrrorigin.revenue.calculation.supported_snapshots")).isEqualTo(1);
    }
}
