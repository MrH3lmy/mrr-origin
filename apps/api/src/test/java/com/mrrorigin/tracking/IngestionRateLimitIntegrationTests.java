package com.mrrorigin.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * #65: the public ingestion endpoint's DB-backed, per-ingestion-key rate limiter. Runs against a
 * lowered {@code requests-per-minute} so tests stay fast and deterministic without waiting on a
 * real 60-second window; {@link MutableClock} drives the window-reset test explicitly instead.
 */
@Testcontainers
@SpringBootTest(properties = "mrrorigin.tracking.rate-limit.requests-per-minute=5")
@AutoConfigureMockMvc
@Import(IngestionRateLimitIntegrationTests.MutableClockConfiguration.class)
class IngestionRateLimitIntegrationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    private static final int LIMIT = 5;
    private static final Instant WINDOW_START = Instant.parse("2026-08-11T12:00:00Z");

    @Autowired private MockMvc mvc;
    @Autowired private IngestionKeyService keys;
    @Autowired private AllowedDomainService domains;
    @Autowired private MutableClock clock;
    private JdbcClient jdbc;

    @Autowired
    void setDataSource(DataSource dataSource) {
        jdbc = JdbcClient.create(dataSource);
    }

    @BeforeEach
    void resetState() {
        jdbc.sql("TRUNCATE TABLE workspaces CASCADE").update();
        clock.set(WINDOW_START);
    }

    @Test
    void requestsWithinTheConfiguredLimitSucceed() throws Exception {
        Fixture fixture = fixture("within-limit", "app.example");

        for (int i = 0; i < LIMIT; i++) {
            mvc.perform(request(fixture.key(), "https://app.example", "batch-" + i, "event-" + i))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void requestsOverTheLimitReturn429WithRetryAfter() throws Exception {
        Fixture fixture = fixture("over-limit", "app.example");

        for (int i = 0; i < LIMIT; i++) {
            mvc.perform(request(fixture.key(), "https://app.example", "batch-" + i, "event-" + i))
                    .andExpect(status().isOk());
        }

        mvc.perform(request(fixture.key(), "https://app.example", "batch-over", "event-over"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.code").value("rate_limit_exceeded"));
    }

    @Test
    void malformedPayloadsCannotBypassTheRateLimit() throws Exception {
        Fixture fixture = fixture("malformed", "app.example");

        for (int i = 0; i < LIMIT; i++) {
            mvc.perform(rawRequest(fixture.key(), "https://app.example", "{not-json-" + i))
                    .andExpect(status().isBadRequest());
        }

        mvc.perform(request(fixture.key(), "https://app.example", "batch-after-malformed", "event-after-malformed"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"));

        assertThat(jdbc.sql("SELECT request_count FROM tracking_ingestion_rate_limit_windows WHERE ingestion_key_id = :keyId")
                .param("keyId", fixture.keyId())
                .query(Integer.class)
                .single()).isEqualTo(LIMIT + 1);
    }

    @Test
    void theLimitIsIsolatedPerIngestionKeyEvenForTheSameProjectAndWorkspace() throws Exception {
        Fixture a = fixture("isolation", "app.example");

        for (int i = 0; i < LIMIT; i++) {
            mvc.perform(request(a.key(), "https://app.example", "batch-a-" + i, "event-a-" + i))
                    .andExpect(status().isOk());
        }
        mvc.perform(request(a.key(), "https://app.example", "batch-a-over", "event-a-over"))
                .andExpect(status().isTooManyRequests());

        IngestionKeyService.IssuedKey b = keys.rotate(a.workspaceId(), a.projectId());
        mvc.perform(request(b.secret(), "https://app.example", "batch-b", "event-b"))
                .andExpect(status().isOk());

        assertThat(jdbc.sql("SELECT request_count FROM tracking_ingestion_rate_limit_windows WHERE ingestion_key_id = :keyId")
                .param("keyId", a.keyId())
                .query(Integer.class)
                .single()).isEqualTo(LIMIT + 1);
        assertThat(jdbc.sql("SELECT request_count FROM tracking_ingestion_rate_limit_windows WHERE ingestion_key_id = :keyId")
                .param("keyId", b.id())
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    @Test
    void theWindowResetsOnceItElapses() throws Exception {
        Fixture fixture = fixture("window-reset", "app.example");

        for (int i = 0; i < LIMIT; i++) {
            mvc.perform(request(fixture.key(), "https://app.example", "batch-" + i, "event-" + i))
                    .andExpect(status().isOk());
        }
        mvc.perform(request(fixture.key(), "https://app.example", "batch-over", "event-over"))
                .andExpect(status().isTooManyRequests());

        clock.set(WINDOW_START.plusSeconds(61));

        mvc.perform(request(fixture.key(), "https://app.example", "batch-after-reset", "event-after-reset"))
                .andExpect(status().isOk());
    }

    @Test
    void concurrentBurstsNearTheThresholdNeitherOverNorUnderCount() throws Exception {
        Fixture fixture = fixture("concurrency", "app.example");
        int burst = LIMIT + 5;
        CountDownLatch ready = new CountDownLatch(burst);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<Integer>> results;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            results = IntStream.range(0, burst)
                    .mapToObj(i -> executor.submit(() -> concurrentRequest(fixture, i, ready, start)))
                    .collect(Collectors.toList());
            ready.await();
            start.countDown();
        }

        List<Integer> statuses = results.stream().map(this::get).collect(Collectors.toList());
        long accepted = statuses.stream().filter(s -> s == 200).count();
        long rejected = statuses.stream().filter(s -> s == 429).count();

        assertThat(accepted).isEqualTo(LIMIT);
        assertThat(rejected).isEqualTo(burst - LIMIT);
        assertThat(jdbc.sql("SELECT request_count FROM tracking_ingestion_rate_limit_windows WHERE ingestion_key_id = :keyId")
                .param("keyId", fixture.keyId())
                .query(Integer.class)
                .single()).isEqualTo(burst);
    }

    private int concurrentRequest(Fixture fixture, int index, CountDownLatch ready, CountDownLatch start) {
        try {
            ready.countDown();
            start.await();
            return mvc.perform(request(fixture.key(), "https://app.example", "batch-" + index, "event-" + index))
                    .andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private int get(Future<Integer> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Fixture fixture(String suffix, String domain) throws Exception {
        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        String normalizedDomain = AllowedDomainService.normalize(domain);
        jdbc.sql("INSERT INTO workspaces (id, name, slug) VALUES (:id, :name, :slug)")
                .param("id", workspaceId).param("name", "Tenant " + suffix).param("slug", "tenant-" + suffix).update();
        jdbc.sql("""
                        INSERT INTO projects (id, workspace_id, name, domain, public_key)
                        VALUES (:id, :workspaceId, :name, :domain, :publicKey)
                """)
                .param("id", projectId).param("workspaceId", workspaceId).param("name", "Project " + suffix)
                .param("domain", normalizedDomain).param("publicKey", "pk_" + UUID.randomUUID()).update();
        IngestionKeyService.IssuedKey key = keys.issue(workspaceId, projectId);
        domains.add(workspaceId, projectId, domain);
        return new Fixture(workspaceId, projectId, key.id(), key.secret());
    }

    private static MockHttpServletRequestBuilder request(String key, String origin, String batchId, String eventId) {
        return post("/api/public/v1/events")
                .header("X-Ingestion-Key", key)
                .header("Origin", origin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(batch(batchId, eventId));
    }

    private static MockHttpServletRequestBuilder rawRequest(String key, String origin, String body) {
        return post("/api/public/v1/events")
                .header("X-Ingestion-Key", key)
                .header("Origin", origin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static String batch(String batchId, String eventId) {
        return """
                {"version":1,"batchId":"%s","events":[
                  {"eventId":"%s","visitorId":"visitor-1","sessionId":"session-1",\
                   "type":"page_view","occurredAt":"2026-08-11T12:00:00Z","payload":{"path":"/pricing"}}
                ]}
                """.formatted(batchId, eventId);
    }

    private record Fixture(UUID workspaceId, UUID projectId, UUID keyId, String key) {}

    static final class MutableClock extends Clock {
        private volatile Instant instant = WINDOW_START;

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MutableClockConfiguration {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }
}
