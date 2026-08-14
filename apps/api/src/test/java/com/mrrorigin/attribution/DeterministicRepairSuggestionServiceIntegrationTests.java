package com.mrrorigin.attribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
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

/**
 * #20's deterministic-only suggestion rule: surface a prior explicit ({@code EXPLICIT_API})
 * identity-to-customer link only when exactly one distinct historical candidate is currently
 * unclaimed. No touchpoint timing, email, or other similarity signal is ever consulted -- see
 * {@link DeterministicRepairSuggestionService}'s class doc for why this is the only signal
 * available under ADR-0005 without inventing new evidence.
 */
@SpringBootTest
@Testcontainers
class DeterministicRepairSuggestionServiceIntegrationTests {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:18-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }

    @Autowired private DeterministicRepairSuggestionService suggestions;
    @Autowired private JdbcClient db;

    private UUID workspace;
    private UUID project;

    @BeforeEach
    void setUp() {
        db.sql("TRUNCATE workspaces CASCADE").update();
        workspace = UUID.randomUUID();
        project = UUID.randomUUID();
        db.sql("INSERT INTO workspaces (id, name, slug) VALUES (:w, 'test', :slug)")
                .param("w", workspace)
                .param("slug", "w-" + workspace)
                .update();
        db.sql("INSERT INTO projects (id, workspace_id, name, domain, public_key) VALUES (:p, :w, 'p', 'one.example', :k)")
                .param("p", project)
                .param("w", workspace)
                .param("k", "pk-" + project)
                .update();
    }

    @Test
    void noHistoryYieldsNoSuggestion() {
        insertBillingCustomer("cus_fresh");
        assertThat(suggestions.suggest(workspace, project, "cus_fresh")).isEmpty();
    }

    @Test
    void aUniqueUnclaimedHistoricalIdentityIsSuggested() {
        // cus_1 was once explicitly linked to identity I; a later customer-side correction reassigned
        // cus_1 to I2, which was itself later reassigned away to cus_2 -- freeing I2 without ever
        // reclaiming I. I remains the sole free historical candidate for cus_1.
        UUID identityI = identify("user_i");
        UUID identityI2 = identify("user_i2");
        insertBillingCustomer("cus_1");
        insertBillingCustomer("cus_2");

        UUID linkC = UUID.randomUUID();
        insertActiveLink(linkC, identityI2, "cus_2");
        UUID linkB = UUID.randomUUID();
        insertSupersededLink(linkB, identityI2, "cus_1", linkC);
        UUID linkA = UUID.randomUUID();
        insertSupersededLink(linkA, identityI, "cus_1", linkB);

        var suggestion = suggestions.suggest(workspace, project, "cus_1");
        assertThat(suggestion).isPresent();
        assertThat(suggestion.get().externalIdentityId()).isEqualTo(identityI);
        assertThat(suggestion.get().externalUserId()).isEqualTo("user_i");
        assertThat(suggestion.get().evidenceLinkId()).isEqualTo(linkA);
    }

    @Test
    void twoFreeHistoricalCandidatesAreAmbiguousAndProduceNoSuggestion() {
        UUID identityI = identify("user_i");
        UUID identityI3 = identify("user_i3");
        UUID identityI4 = identify("user_i4");
        insertBillingCustomer("cus_1");

        UUID linkE = UUID.randomUUID();
        insertActiveLink(linkE, identityI4, "cus_1");
        UUID linkB = UUID.randomUUID();
        insertSupersededLink(linkB, identityI3, "cus_1", linkE);
        UUID linkA = UUID.randomUUID();
        insertSupersededLink(linkA, identityI, "cus_1", linkB);

        assertThat(suggestions.suggest(workspace, project, "cus_1")).isEmpty();
    }

    @Test
    void aHistoricalCandidateCurrentlyClaimedElsewhereProducesNoSuggestion() {
        UUID identityI = identify("user_i");
        insertBillingCustomer("cus_2");
        insertBillingCustomer("cus_3");

        UUID linkH = UUID.randomUUID();
        insertActiveLink(linkH, identityI, "cus_3");
        UUID linkF = UUID.randomUUID();
        insertSupersededLink(linkF, identityI, "cus_2", linkH);

        assertThat(suggestions.suggest(workspace, project, "cus_2")).isEmpty();
    }

    @Test
    void touchpointTimingAloneNeverProducesASuggestion() {
        UUID identity = identify("user_close_timing");
        insertBillingCustomer("cus_no_link_history");
        UUID visitor = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        OffsetDateTime touchAt = OffsetDateTime.parse("2026-03-31T23:59:59Z");
        db.sql("INSERT INTO visitors (id, workspace_id, project_id, external_visitor_id, first_seen_at, last_seen_at) VALUES (:v, :w, :p, :e, :at, :at)")
                .param("v", visitor).param("w", workspace).param("p", project).param("e", visitor.toString()).param("at", touchAt)
                .update();
        db.sql("INSERT INTO visitor_aliases (id, workspace_id, project_id, visitor_id, external_identity_id, identified_at) VALUES (:id, :w, :p, :v, :i, now())")
                .param("id", UUID.randomUUID()).param("w", workspace).param("p", project).param("v", visitor).param("i", identity)
                .update();
        db.sql("INSERT INTO tracking_sessions (id, workspace_id, project_id, visitor_id, external_session_id, started_at) VALUES (:s, :w, :p, :v, :e, :at)")
                .param("s", session).param("w", workspace).param("p", project).param("v", visitor).param("e", session.toString()).param("at", touchAt)
                .update();
        db.sql(
                        "INSERT INTO touchpoints (id, workspace_id, project_id, visitor_id, session_id, occurred_at, landing_url, utm_source, created_at) "
                                + "VALUES (:id, :w, :p, :v, :s, :at, 'https://example.test/', 'google', :created)")
                .param("id", UUID.randomUUID()).param("w", workspace).param("p", project).param("v", visitor).param("s", session)
                .param("at", touchAt).param("created", touchAt.plusSeconds(1))
                .update();
        // No stripe_customer_links row of any kind exists naming this identity/customer pair.

        assertThat(suggestions.suggest(workspace, project, "cus_no_link_history")).isEmpty();
    }

    private UUID identify(String externalUserId) {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO external_identities (id, workspace_id, project_id, external_user_id) VALUES (:id, :w, :p, :u)")
                .param("id", id)
                .param("w", workspace)
                .param("p", project)
                .param("u", externalUserId)
                .update();
        return id;
    }

    private void insertBillingCustomer(String stripeCustomerId) {
        db.sql(
                        """
                        INSERT INTO billing_customers
                            (id, workspace_id, stripe_customer_id, provider_created_at, source, source_version, source_sequence)
                        VALUES (:id, :w, :c, now(), 'BACKFILL', 1, :c)
                        """)
                .param("id", UUID.randomUUID())
                .param("w", workspace)
                .param("c", stripeCustomerId)
                .update();
    }

    private void insertActiveLink(UUID id, UUID externalIdentityId, String stripeCustomerId) {
        db.sql(
                        """
                        INSERT INTO stripe_customer_links
                            (id, workspace_id, project_id, external_identity_id, stripe_customer_id,
                             evidence_source, evidence_reference, linked_by_subject_id)
                        VALUES (:id, :w, :p, :identity, :c, 'EXPLICIT_API', 'seed', 'seed-actor')
                        """)
                .param("id", id)
                .param("w", workspace)
                .param("p", project)
                .param("identity", externalIdentityId)
                .param("c", stripeCustomerId)
                .update();
    }

    private void insertSupersededLink(UUID id, UUID externalIdentityId, String stripeCustomerId, UUID supersededById) {
        db.sql(
                        """
                        INSERT INTO stripe_customer_links
                            (id, workspace_id, project_id, external_identity_id, stripe_customer_id,
                             evidence_source, evidence_reference, linked_by_subject_id, superseded_at, superseded_by_id)
                        VALUES (:id, :w, :p, :identity, :c, 'EXPLICIT_API', 'seed', 'seed-actor', now(), :supersededBy)
                        """)
                .param("id", id)
                .param("w", workspace)
                .param("p", project)
                .param("identity", externalIdentityId)
                .param("c", stripeCustomerId)
                .param("supersededBy", supersededById)
                .update();
    }
}
