package com.mrrorigin.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest
class TrackingSchemaIntegrationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    @Autowired
    private IngestionKeyService keys;

    private JdbcClient jdbc;

    @Autowired
    void setDataSource(DataSource dataSource) {
        jdbc = JdbcClient.create(dataSource);
    }

    @BeforeEach
    void clearTenantData() {
        jdbc.sql("TRUNCATE TABLE workspaces CASCADE").update();
    }

    @Test
    void keyResolvesToOneProjectWhileOnlyItsHashIsStored() {
        Tenant tenant = tenant("one");

        IngestionKeyService.IssuedKey issued = keys.issue(tenant.workspaceId(), tenant.projectId());

        assertThat(keys.resolve(issued.secret()))
                .contains(new IngestionKeyService.ResolvedProject(tenant.workspaceId(), tenant.projectId()));
        Map<String, Object> stored = jdbc.sql("""
                        SELECT key_prefix, secret_hash FROM project_ingestion_keys WHERE id = :id
                        """)
                .param("id", issued.id())
                .query()
                .singleRow();
        assertThat(stored.get("key_prefix")).isEqualTo(issued.prefix());
        assertThat(stored.get("secret_hash")).asString().hasSize(64).doesNotContain(issued.secret());
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM information_schema.columns
                        WHERE table_name = 'project_ingestion_keys'
                          AND column_name IN ('secret', 'raw_key', 'raw_secret')
                        """)
                .query(Integer.class)
                .single()).isZero();
    }

    @Test
    void rotationRevokesThePreviousKeyAndRevokedKeysNeverResolve() {
        Tenant tenant = tenant("rotation");
        IngestionKeyService.IssuedKey first = keys.issue(tenant.workspaceId(), tenant.projectId());

        IngestionKeyService.IssuedKey second = keys.rotate(tenant.workspaceId(), tenant.projectId());

        assertThat(keys.resolve(first.secret())).isEmpty();
        assertThat(keys.resolve(second.secret())).isPresent();
        assertThat(keys.revoke(tenant.workspaceId(), tenant.projectId(), second.id())).isTrue();
        assertThat(keys.revoke(tenant.workspaceId(), tenant.projectId(), second.id())).isFalse();
        assertThat(keys.resolve(second.secret())).isEmpty();
    }

    @Test
    void crossTenantKeyOperationsAndTrackingRelationshipsAreRejected() {
        Tenant alice = tenant("alice");
        Tenant bob = tenant("bob");

        assertThatThrownBy(() -> keys.issue(alice.workspaceId(), bob.projectId()))
                .isInstanceOf(IllegalArgumentException.class);
        IngestionKeyService.IssuedKey bobKey = keys.issue(bob.workspaceId(), bob.projectId());
        assertThat(keys.revoke(alice.workspaceId(), bob.projectId(), bobKey.id())).isFalse();

        UUID aliceVisitor = visitor(alice, "visitor-alice");
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO tracking_sessions
                            (id, workspace_id, project_id, visitor_id, external_session_id, started_at)
                        VALUES (:id, :workspaceId, :projectId, :visitorId, 'cross-tenant', :now)
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", bob.workspaceId())
                .param("projectId", bob.projectId())
                .param("visitorId", aliceVisitor)
                .param("now", OffsetDateTime.now(ZoneOffset.UTC))
                .update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateExternalEventIdsAreRejectedWithinAProjectButNotAcrossProjects() {
        Tenant alice = tenant("events-a");
        Tenant bob = tenant("events-b");
        UUID aliceVisitor = visitor(alice, "visitor-a");
        UUID bobVisitor = visitor(bob, "visitor-b");

        event(alice, aliceVisitor, "event-123");
        assertThatThrownBy(() -> event(alice, aliceVisitor, "event-123"))
                .isInstanceOf(DataIntegrityViolationException.class);
        event(bob, bobVisitor, "event-123");

        assertThat(jdbc.sql("SELECT COUNT(*) FROM tracking_event_envelopes WHERE external_event_id = 'event-123'")
                .query(Integer.class)
                .single()).isEqualTo(2);
    }

    private Tenant tenant(String suffix) {
        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        jdbc.sql("INSERT INTO workspaces (id, name, slug) VALUES (:workspaceId, :name, :slug)")
                .param("workspaceId", workspaceId)
                .param("name", "Tenant " + suffix)
                .param("slug", "tenant-" + suffix)
                .update();
        jdbc.sql("""
                INSERT INTO projects (id, workspace_id, name, domain, public_key)
                VALUES (:projectId, :workspaceId, :name, :domain, :publicKey)
                """)
                .param("projectId", projectId)
                .param("workspaceId", workspaceId)
                .param("name", "Project " + suffix)
                .param("domain", suffix + ".example.com")
                .param("publicKey", "pk_" + UUID.randomUUID())
                .update();
        return new Tenant(workspaceId, projectId);
    }

    private UUID visitor(Tenant tenant, String externalId) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO visitors
                    (id, workspace_id, project_id, external_visitor_id, first_seen_at, last_seen_at)
                VALUES (:id, :workspaceId, :projectId, :externalId, :now, :now)
                """)
                .param("id", id)
                .param("workspaceId", tenant.workspaceId())
                .param("projectId", tenant.projectId())
                .param("externalId", externalId)
                .param("now", now)
                .update();
        return id;
    }

    private void event(Tenant tenant, UUID visitorId, String externalEventId) {
        jdbc.sql("""
                INSERT INTO tracking_event_envelopes
                    (id, workspace_id, project_id, visitor_id, external_event_id,
                     event_type, occurred_at, payload)
                VALUES (:id, :workspaceId, :projectId, :visitorId, :externalEventId,
                        'page_view', :occurredAt, CAST(:payload AS JSONB))
                """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", tenant.workspaceId())
                .param("projectId", tenant.projectId())
                .param("visitorId", visitorId)
                .param("externalEventId", externalEventId)
                .param("occurredAt", OffsetDateTime.now(ZoneOffset.UTC))
                .param("payload", "{}")
                .update();
    }

    private record Tenant(UUID workspaceId, UUID projectId) {}
}
