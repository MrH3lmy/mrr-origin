package com.mrrorigin.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class EventIngestionIntegrationTests {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    @Autowired private MockMvc mvc;
    @Autowired private IngestionKeyService keys;
    @Autowired private AllowedDomainService domains;
    private JdbcClient jdbc;

    @Autowired
    void setDataSource(DataSource dataSource) {
        jdbc = JdbcClient.create(dataSource);
    }

    @BeforeEach
    void clearData() {
        jdbc.sql("TRUNCATE TABLE workspaces CASCADE").update();
    }

    @Test
    void acceptsVersionedBatchAndReturnsRetrySafeResults() throws Exception {
        Fixture fixture = fixture("accepted", "app.example");

        mvc.perform(request(fixture.key(), "https://app.example", batch("batch-1", "event-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value("batch-1"))
                .andExpect(jsonPath("$.events[0].eventId").value("event-1"))
                .andExpect(jsonPath("$.events[0].status").value("ACCEPTED"));

        mvc.perform(request(fixture.key(), "https://app.example", batch("batch-1", "event-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].status").value("DUPLICATE"));
        mvc.perform(request(fixture.key(), "https://app.example", batch("batch-2", "event-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].status").value("DUPLICATE"));

        assertThat(count("tracking_ingestion_batches")).isEqualTo(2);
        assertThat(count("tracking_event_envelopes")).isOne();
        assertThat(count("visitors")).isOne();
        assertThat(count("tracking_sessions")).isOne();
    }

    @Test
    void rejectsAReusedBatchIdWithDifferentContentWithoutWriting() throws Exception {
        Fixture fixture = fixture("batch-conflict", "app.example");
        mvc.perform(request(fixture.key(), "https://app.example", batch("batch-1", "event-1")))
                .andExpect(status().isOk());

        mvc.perform(request(fixture.key(), "https://app.example", batch("batch-1", "event-2")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("batch_id_conflict"));

        assertThat(count("tracking_ingestion_batches")).isOne();
        assertThat(count("tracking_event_envelopes")).isOne();
    }

    @Test
    void keyResolutionPreventsGuessedCrossProjectIdentifiers() throws Exception {
        Fixture alice = fixture("alice", "alice.example");
        Fixture bob = fixture("bob", "bob.example");
        String guessedProject = batch("batch-guess", "event-guess")
                .replace("\"events\"", "\"projectId\":\"" + bob.projectId() + "\",\"events\"");

        mvc.perform(request(alice.key(), "https://alice.example", guessedProject))
                .andExpect(status().isBadRequest());
        mvc.perform(request(alice.key(), "https://bob.example", batch("batch-guess", "event-guess")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("origin_not_allowed"));

        assertThat(count("tracking_ingestion_batches")).isZero();
    }

    @Test
    void rejectsRevokedAndUnknownKeysBeforePersistence() throws Exception {
        Fixture fixture = fixture("revoked", "app.example");
        keys.revoke(fixture.workspaceId(), fixture.projectId(), fixture.keyId());

        mvc.perform(request(fixture.key(), "https://app.example", batch("batch-1", "event-1")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_ingestion_key"));
        mvc.perform(request("guessed", "https://app.example", batch("batch-2", "event-2")))
                .andExpect(status().isUnauthorized());
        assertThat(count("tracking_ingestion_batches")).isZero();
    }

    @Test
    void rejectsMissingMalformedAndUnlistedOrigins() throws Exception {
        Fixture fixture = fixture("origins", "app.example");

        mvc.perform(post("/api/public/v1/events")
                        .header("X-Ingestion-Key", fixture.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batch("batch-1", "event-1")))
                .andExpect(status().isBadRequest());
        mvc.perform(request(fixture.key(), "not an origin", batch("batch-2", "event-2")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("invalid_origin"));
        mvc.perform(request(fixture.key(), "https://evil.example", batch("batch-3", "event-3")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("origin_not_allowed"));
        assertThat(count("tracking_ingestion_batches")).isZero();
    }

    @Test
    void malformedTimestampsAndOversizedBatchesFailWithoutWrites() throws Exception {
        Fixture fixture = fixture("validation", "app.example");
        String malformed = batch("bad-time", "event-1").replace("2026-08-11T12:00:00Z", "yesterday");

        mvc.perform(request(fixture.key(), "https://app.example", malformed))
                .andExpect(status().isBadRequest());
        String event = event("event-%d");
        StringBuilder oversized = new StringBuilder("{\"version\":1,\"batchId\":\"too-big\",\"events\":[");
        for (int i = 0; i < 101; i++) {
            if (i > 0) oversized.append(',');
            oversized.append(event.formatted(i));
        }
        oversized.append("]}");
        mvc.perform(request(fixture.key(), "https://app.example", oversized.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_envelope"));
        assertThat(count("tracking_ingestion_batches")).isZero();
        assertThat(count("tracking_event_envelopes")).isZero();
    }

    @Test
    void aLateEventFailureRollsBackTheEntireBatch() throws Exception {
        Fixture fixture = fixture("atomic", "app.example");
        mvc.perform(request(fixture.key(), "https://app.example", batch("seed", "seed-event")))
                .andExpect(status().isOk());
        String conflicting = """
                {"version":1,"batchId":"atomic-failure","events":[
                  %s,
                  {"eventId":"second","visitorId":"other-visitor","sessionId":"session-1",\
                   "type":"page_view","occurredAt":"2026-08-11T12:00:01Z","payload":{}}
                ]}
                """.formatted(event("first"));

        mvc.perform(request(fixture.key(), "https://app.example", conflicting))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("session_visitor_conflict"));

        assertThat(count("tracking_ingestion_batches")).isOne();
        assertThat(count("tracking_event_envelopes")).isOne();
        assertThat(count("visitors")).isOne();
    }

    private Fixture fixture(String suffix, String domain) {
        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        jdbc.sql("INSERT INTO workspaces (id, name, slug) VALUES (:id, :name, :slug)")
                .param("id", workspaceId).param("name", "Tenant " + suffix).param("slug", "tenant-" + suffix).update();
        jdbc.sql("""
                        INSERT INTO projects (id, workspace_id, name, domain, public_key)
                        VALUES (:id, :workspaceId, :name, :domain, :publicKey)
                        """)
                .param("id", projectId).param("workspaceId", workspaceId).param("name", "Project " + suffix)
                .param("domain", domain).param("publicKey", "pk_" + UUID.randomUUID()).update();
        IngestionKeyService.IssuedKey key = keys.issue(workspaceId, projectId);
        domains.add(workspaceId, projectId, domain);
        return new Fixture(workspaceId, projectId, key.id(), key.secret());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(
            String key, String origin, String content) {
        return post("/api/public/v1/events")
                .header("X-Ingestion-Key", key)
                .header("Origin", origin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(content);
    }

    private static String batch(String batchId, String eventId) {
        return "{\"version\":1,\"batchId\":\"" + batchId + "\",\"events\":[" + event(eventId) + "]}";
    }

    private static String event(String eventId) {
        return """
                {"eventId":"%s","visitorId":"visitor-1","sessionId":"session-1",\
                 "type":"page_view","occurredAt":"2026-08-11T12:00:00Z","payload":{"path":"/pricing"}}
                """.formatted(eventId);
    }

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    private record Fixture(UUID workspaceId, UUID projectId, UUID keyId, String key) {}
}
