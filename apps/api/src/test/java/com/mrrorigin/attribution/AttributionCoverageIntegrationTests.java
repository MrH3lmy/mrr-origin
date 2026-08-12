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
 * Covers #19's coverage numerator/denominator contract described on {@link AttributionCoverage}:
 * the denominator counts every recalculated NEW movement, the numerator counts the STRONG subset,
 * and the remainder is broken down by ADR-0005's two unattributed reason codes.
 */
@SpringBootTest
@Testcontainers
class AttributionCoverageIntegrationTests {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:18-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }

    @Autowired AttributionApplicationService attribution;
    @Autowired JdbcClient db;
    UUID workspace;
    UUID project;

    @BeforeEach void setUp() {
        db.sql("TRUNCATE workspaces CASCADE").update();
        workspace = UUID.randomUUID();
        project = UUID.randomUUID();
        db.sql("INSERT INTO workspaces(id,name,slug) VALUES(:w,'test',:slug)").param("w", workspace).param("slug", "w-" + workspace).update();
        db.sql("INSERT INTO projects(id,workspace_id,name,domain,public_key) VALUES(:p,:w,'p','one.example',:k)")
                .param("p", project).param("w", workspace).param("k", "pk-" + project).update();
    }

    @Test void computesNumeratorDenominatorAndExclusionReasonBreakdown() {
        movement("strong-customer");
        link("strong-customer");
        touchpoint("strong-customer", "2026-03-15T00:00:00Z");
        attribution.recalculate(workspace, project, "strong-customer");

        movement("unlinked-customer");
        attribution.recalculate(workspace, project, "unlinked-customer");

        movement("linked-but-no-touchpoint");
        link("linked-but-no-touchpoint");
        attribution.recalculate(workspace, project, "linked-but-no-touchpoint");

        AttributionCoverage coverage = attribution.coverage(workspace, project, AttributionV1Engine.MODEL_VERSION);

        assertThat(coverage.eligibleNewCustomers()).isEqualTo(3);
        assertThat(coverage.attributedNewCustomers()).isEqualTo(1);
        assertThat(coverage.coverageRatio()).isEqualTo(1.0 / 3.0);
        assertThat(coverage.exclusionReasonCounts()).containsExactlyInAnyOrderEntriesOf(
                java.util.Map.of("NO_ACTIVE_LINK", 1L, "NO_ELIGIBLE_TOUCHPOINT", 1L));
    }

    @Test void excludesCustomersThatHaveNotBeenRecalculatedYet() {
        movement("never-recalculated");
        AttributionCoverage coverage = attribution.coverage(workspace, project, AttributionV1Engine.MODEL_VERSION);
        assertThat(coverage.eligibleNewCustomers()).isZero();
        assertThat(coverage.attributedNewCustomers()).isZero();
        assertThat(coverage.coverageRatio()).isZero();
        assertThat(coverage.exclusionReasonCounts()).isEmpty();
    }

    @Test void scopesCoverageByWorkspaceProjectAndModelVersion() {
        movement("cus");
        link("cus");
        touchpoint("cus", "2026-03-15T00:00:00Z");
        attribution.recalculate(workspace, project, "cus");

        assertThat(attribution.coverage(workspace, project, "attribution-v0").eligibleNewCustomers()).isZero();

        UUID otherProject = UUID.randomUUID();
        db.sql("INSERT INTO projects(id,workspace_id,name,domain,public_key) VALUES(:p,:w,'p','two.example',:k)")
                .param("p", otherProject).param("w", workspace).param("k", "pk-" + otherProject).update();
        assertThat(attribution.coverage(workspace, otherProject, AttributionV1Engine.MODEL_VERSION).eligibleNewCustomers()).isZero();
    }

    private void movement(String customerId) {
        db.sql("INSERT INTO customer_mrr_movements(id,workspace_id,stripe_customer_id,currency,amount_minor,movement_type,effective_at,calculation_version,source_billing_references) VALUES(:id,:w,:c,'USD',100,'NEW',:at,'mrr-v1',ARRAY['billing:test'])")
                .param("id", UUID.randomUUID()).param("w", workspace).param("c", customerId).param("at", OffsetDateTime.parse("2026-04-01T00:00:00Z")).update();
    }

    private void link(String customerId) {
        UUID identity = UUID.randomUUID();
        db.sql("INSERT INTO external_identities(id,workspace_id,project_id,external_user_id) VALUES(:i,:w,:p,:u)")
                .param("i", identity).param("w", workspace).param("p", project).param("u", "user-" + customerId).update();
        db.sql("INSERT INTO billing_customers(id,workspace_id,stripe_customer_id,provider_created_at,source,source_version,source_sequence) VALUES(:id,:w,:c,now(),'BACKFILL',1,:c)")
                .param("id", UUID.randomUUID()).param("w", workspace).param("c", customerId).update();
        db.sql("INSERT INTO stripe_customer_links(id,workspace_id,project_id,external_identity_id,stripe_customer_id,evidence_source,evidence_reference,linked_by_subject_id) VALUES(:id,:w,:p,:i,:c,'EXPLICIT_API','evidence','owner')")
                .param("id", UUID.randomUUID()).param("w", workspace).param("p", project).param("i", identity).param("c", customerId).update();
    }

    private void touchpoint(String customerId, String at) {
        UUID identity = db.sql("SELECT external_identity_id FROM stripe_customer_links WHERE workspace_id=:w AND stripe_customer_id=:c")
                .param("w", workspace).param("c", customerId).query(UUID.class).single();
        UUID visitor = UUID.randomUUID(), session = UUID.randomUUID(), touchpoint = UUID.randomUUID();
        OffsetDateTime time = OffsetDateTime.parse(at);
        db.sql("INSERT INTO visitors(id,workspace_id,project_id,external_visitor_id,first_seen_at,last_seen_at) VALUES(:v,:w,:p,:e,:at,:at)")
                .param("v", visitor).param("w", workspace).param("p", project).param("e", visitor.toString()).param("at", time).update();
        db.sql("INSERT INTO visitor_aliases(id,workspace_id,project_id,visitor_id,external_identity_id,identified_at) VALUES(:id,:w,:p,:v,:i,now())")
                .param("id", UUID.randomUUID()).param("w", workspace).param("p", project).param("v", visitor).param("i", identity).update();
        db.sql("INSERT INTO tracking_sessions(id,workspace_id,project_id,visitor_id,external_session_id,started_at) VALUES(:s,:w,:p,:v,:e,:at)")
                .param("s", session).param("w", workspace).param("p", project).param("v", visitor).param("e", session.toString()).param("at", time).update();
        db.sql("INSERT INTO touchpoints(id,workspace_id,project_id,visitor_id,session_id,occurred_at,landing_url,utm_source,created_at) VALUES(:id,:w,:p,:v,:s,:at,'https://example.test/','google',:created)")
                .param("id", touchpoint).param("w", workspace).param("p", project).param("v", visitor).param("s", session).param("at", time).param("created", time.plusSeconds(1)).update();
    }
}
