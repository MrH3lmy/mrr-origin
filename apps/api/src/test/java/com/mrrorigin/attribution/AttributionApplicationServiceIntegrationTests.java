package com.mrrorigin.attribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

@SpringBootTest
@Testcontainers
class AttributionApplicationServiceIntegrationTests {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:18-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }

    @Autowired AttributionApplicationService service;
    @Autowired JdbcClient db;
    UUID workspace;
    UUID project;

    @BeforeEach void setUp() {
        db.sql("TRUNCATE workspaces CASCADE").update();
        workspace = UUID.randomUUID(); project = UUID.randomUUID();
        db.sql("INSERT INTO workspaces(id,name,slug) VALUES(:w,'test',:slug)").param("w",workspace).param("slug","w-"+workspace).update();
        project(project, workspace, "one.example");
    }

    @Test void storesStrongExplanationAndAllMovementsInheritItIdempotently() {
        movement("new", "NEW", "2026-04-01T00:00:00Z");
        movement("expansion", "EXPANSION", "2026-05-01T00:00:00Z");
        UUID link = identityLink("cus", "EXPLICIT_API");
        UUID first = touchpoint("2026-01-01T00:00:00Z", "google", null);
        touchpoint("2026-03-20T00:00:00Z", null, null); // direct return is not last touch

        var firstRun = service.recalculate(workspace, project, "cus");
        var secondRun = service.recalculate(workspace, project, "cus");

        assertThat(firstRun).hasSize(2).allSatisfy(result -> {
            assertThat(result.acquisitionMovementId()).isEqualTo(firstRun.getFirst().acquisitionMovementId());
            assertThat(result.firstTouch().touchpointId()).isEqualTo(first);
            assertThat(result.lastTouch().touchpointId()).isEqualTo(first);
            assertThat(result.customerLinkEvidenceId()).isEqualTo(link);
            assertThat(result.confidence()).isEqualTo("STRONG");
        });
        assertThat(secondRun).extracting(CustomerAttributionExplanation::movementId)
                .containsExactlyElementsOf(firstRun.stream().map(CustomerAttributionExplanation::movementId).toList());
        assertThat(countResults()).isEqualTo(2);
    }

    @Test void distinguishesMissingLinkFromEmptyPoolAndKeepsStrongIdentityEvidence() {
        movement("new", "NEW", "2026-04-01T00:00:00Z");
        assertThat(service.recalculate(workspace, project, "cus")).singleElement().satisfies(result -> {
            assertThat(result.unattributedReason()).isEqualTo("NO_ACTIVE_LINK");
            assertThat(result.customerLinkEvidenceId()).isNull();
        });
        UUID link = identityLink("cus", "EXPLICIT_API");
        assertThat(service.recalculate(workspace, project, "cus")).singleElement().satisfies(result -> {
            assertThat(result.unattributedReason()).isEqualTo("NO_ELIGIBLE_TOUCHPOINT");
            assertThat(result.customerLinkEvidenceId()).isEqualTo(link);
            assertThat(result.sourceReferences()).containsExactly("stripe_customer_links:" + link);
        });
    }

    @Test void rejectsWrongProjectRatherThanPersistingFalseNoActiveLink() {
        movement("new", "NEW", "2026-04-01T00:00:00Z"); identityLink("cus", "EXPLICIT_API");
        UUID other = UUID.randomUUID(); project(other, workspace, "two.example");
        assertThatThrownBy(() -> service.recalculate(workspace, other, "cus"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("different project");
        assertThat(countResults()).isZero();
    }

    @Test void disabledVerifiedWriterRollsBackTheWholeRecalculation() {
        movement("new", "NEW", "2026-04-01T00:00:00Z"); identityLink("cus", "STRIPE_METADATA");
        assertThatThrownBy(() -> service.recalculate(workspace, project, "cus"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("disabled");
        assertThat(countResults()).isZero();
    }

    private void project(UUID id, UUID owner, String domain) { db.sql("INSERT INTO projects(id,workspace_id,name,domain,public_key) VALUES(:p,:w,'p',:d,:k)").param("p",id).param("w",owner).param("d",domain).param("k","pk-"+id).update(); }
    private UUID movement(String seed,String type,String at) { UUID id=UUID.nameUUIDFromBytes(seed.getBytes()); db.sql("INSERT INTO customer_mrr_movements(id,workspace_id,stripe_customer_id,currency,amount_minor,movement_type,effective_at,calculation_version,source_billing_references) VALUES(:id,:w,'cus','USD',100,:t,:at,'mrr-v1',ARRAY['billing:test'])").param("id",id).param("w",workspace).param("t",type).param("at",OffsetDateTime.parse(at)).update(); return id; }
    private UUID identityLink(String customer,String source) { UUID identity=UUID.randomUUID(),link=UUID.randomUUID(); db.sql("INSERT INTO external_identities(id,workspace_id,project_id,external_user_id) VALUES(:i,:w,:p,'user')").param("i",identity).param("w",workspace).param("p",project).update(); db.sql("INSERT INTO billing_customers(id,workspace_id,stripe_customer_id,provider_created_at,source,source_version,source_sequence) VALUES(:id,:w,:c,now(),'BACKFILL',1,'customer')").param("id",UUID.randomUUID()).param("w",workspace).param("c",customer).update(); db.sql("INSERT INTO stripe_customer_links(id,workspace_id,project_id,external_identity_id,stripe_customer_id,evidence_source,evidence_reference,linked_by_subject_id) VALUES(:id,:w,:p,:i,:c,:s,'evidence','owner')").param("id",link).param("w",workspace).param("p",project).param("i",identity).param("c",customer).param("s",source).update(); return link; }
    private UUID touchpoint(String at,String source,String campaign) { UUID identity=db.sql("SELECT external_identity_id FROM stripe_customer_links WHERE workspace_id=:w").param("w",workspace).query(UUID.class).single(),visitor=UUID.randomUUID(),session=UUID.randomUUID(),touchpoint=UUID.randomUUID(); OffsetDateTime time=OffsetDateTime.parse(at); db.sql("INSERT INTO visitors(id,workspace_id,project_id,external_visitor_id,first_seen_at,last_seen_at) VALUES(:v,:w,:p,:e,:at,:at)").param("v",visitor).param("w",workspace).param("p",project).param("e",visitor.toString()).param("at",time).update(); db.sql("INSERT INTO visitor_aliases(id,workspace_id,project_id,visitor_id,external_identity_id,identified_at) VALUES(:id,:w,:p,:v,:i,now())").param("id",UUID.randomUUID()).param("w",workspace).param("p",project).param("v",visitor).param("i",identity).update(); db.sql("INSERT INTO tracking_sessions(id,workspace_id,project_id,visitor_id,external_session_id,started_at) VALUES(:s,:w,:p,:v,:e,:at)").param("s",session).param("w",workspace).param("p",project).param("v",visitor).param("e",session.toString()).param("at",time).update(); db.sql("INSERT INTO touchpoints(id,workspace_id,project_id,visitor_id,session_id,occurred_at,landing_url,utm_source,utm_campaign,created_at) VALUES(:id,:w,:p,:v,:s,:at,'https://example.test/',:source,:campaign,:created)").param("id",touchpoint).param("w",workspace).param("p",project).param("v",visitor).param("s",session).param("at",time).param("source",source).param("campaign",campaign).param("created",time.plusSeconds(1)).update(); return touchpoint; }
    private long countResults(){return db.sql("SELECT count(*) FROM customer_attribution_results").query(Long.class).single();}
}
