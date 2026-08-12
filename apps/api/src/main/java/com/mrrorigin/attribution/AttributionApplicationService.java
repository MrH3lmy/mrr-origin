package com.mrrorigin.attribution;

import com.mrrorigin.revenue.RevenueCalculationService;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AttributionApplicationService {
    private final JdbcClient db;
    private final Clock clock;
    private final AttributionV1Engine engine = new AttributionV1Engine();

    public AttributionApplicationService(JdbcClient db, Clock clock) { this.db = db; this.clock = clock; }

    /** Recalculates all movements from the original NEW movement in one atomic operation. */
    @Transactional
    public List<CustomerAttributionExplanation> recalculate(UUID workspaceId, UUID projectId, String customerId) {
        require(workspaceId, projectId, customerId);
        List<Movement> movements = movements(workspaceId, customerId);
        if (movements.isEmpty()) return List.of();
        Movement acquisition = movements.stream().filter(m -> m.type().equals("NEW")).findFirst()
                .orElseThrow(() -> new IllegalStateException("customer has no New MRR movement"));
        Link link = activeLink(workspaceId, customerId);
        if (link != null && !projectId.equals(link.projectId())) {
            throw new IllegalArgumentException("customer is linked in a different project");
        }
        Result result = link == null ? Result.unattributed("NO_ACTIVE_LINK") : calculate(link, acquisition.at());
        OffsetDateTime calculatedAt = OffsetDateTime.now(clock);
        for (Movement movement : movements) upsert(workspaceId, projectId, movement, acquisition, result, calculatedAt);
        return explanations(workspaceId, projectId, customerId, AttributionV1Engine.MODEL_VERSION);
    }

    public List<CustomerAttributionExplanation> explanations(UUID workspaceId, UUID projectId,
            String customerId, String modelVersion) {
        require(workspaceId, projectId, customerId);
        return db.sql("""
                SELECT r.movement_id,r.acquisition_movement_id,m.movement_type,m.effective_at,r.model_version,
                  r.first_touchpoint_id,r.first_source,r.first_campaign,r.first_landing_page,
                  r.last_touchpoint_id,r.last_source,r.last_campaign,r.last_landing_page,
                  r.customer_link_evidence_id,r.confidence,r.unattributed_reason,r.source_references,r.calculated_at
                FROM customer_attribution_results r JOIN customer_mrr_movements m ON m.workspace_id=r.workspace_id AND m.id=r.movement_id
                WHERE r.workspace_id=:w AND r.project_id=:p AND m.stripe_customer_id=:c AND r.model_version=:v
                ORDER BY m.effective_at,m.id
                """).param("w", workspaceId).param("p", projectId).param("c", customerId).param("v", modelVersion)
                .query((rs, n) -> new CustomerAttributionExplanation(workspaceId, projectId, customerId,
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getObject(4, OffsetDateTime.class), rs.getString(5),
                        evidence(rs.getObject(6, UUID.class), rs.getString(7), rs.getString(8), rs.getString(9)),
                        evidence(rs.getObject(10, UUID.class), rs.getString(11), rs.getString(12), rs.getString(13)),
                        rs.getObject(14, UUID.class), rs.getString(15), rs.getString(16),
                        List.of((String[]) rs.getArray(17).getArray()), rs.getObject(18, OffsetDateTime.class))).list();
    }

    private Result calculate(Link link, OffsetDateTime anchor) {
        // Only EXPLICIT_API is an approved production writer today. Reserved tiers are not fabricated.
        if (!"EXPLICIT_API".equals(link.source())) throw new IllegalStateException("disabled attribution evidence source");
        List<AttributionV1Engine.Touchpoint> pool = db.sql("""
                SELECT t.id,t.occurred_at,t.created_at,t.utm_source,t.utm_campaign,t.landing_url,
                       t.referrer_url,t.utm_medium,t.utm_term,t.utm_content
                FROM visitor_aliases a JOIN touchpoints t ON t.workspace_id=a.workspace_id
                  AND t.project_id=a.project_id AND t.visitor_id=a.visitor_id
                WHERE a.workspace_id=:w AND a.project_id=:p AND a.external_identity_id=:i
                """).param("w", link.workspaceId()).param("p", link.projectId()).param("i", link.identityId())
                .query((r, n) -> new AttributionV1Engine.Touchpoint(r.getObject(1, UUID.class).toString(),
                        r.getObject(2, OffsetDateTime.class), r.getObject(3, OffsetDateTime.class), r.getString(4),
                        r.getString(5), r.getString(6), r.getString(7), r.getString(8), r.getString(9), r.getString(10))).list();
        var selected = engine.select(anchor, pool);
        if (selected.first() == null) return Result.unattributed("NO_ELIGIBLE_TOUCHPOINT", link.id());
        return new Result(selected.first(), selected.last(), link.id(), "STRONG", null,
                List.of("stripe_customer_links:" + link.id(), "touchpoints:" + selected.first().id(),
                        "touchpoints:" + selected.last().id()));
    }

    private void upsert(UUID w, UUID p, Movement movement, Movement acquisition, Result r, OffsetDateTime at) {
        db.sql("""
                INSERT INTO customer_attribution_results(id,workspace_id,project_id,movement_id,acquisition_movement_id,model_version,
                  first_touchpoint_id,last_touchpoint_id,customer_link_evidence_id,first_source,first_campaign,first_landing_page,
                  last_source,last_campaign,last_landing_page,confidence,unattributed_reason,source_references,calculated_at)
                VALUES(:id,:w,:p,:m,:a,:v,:ft,:lt,:link,:fs,:fc,:fl,:ls,:lc,:ll,:confidence,:reason,:refs,:at)
                ON CONFLICT(workspace_id,project_id,movement_id,model_version) DO UPDATE SET
                  acquisition_movement_id=EXCLUDED.acquisition_movement_id,first_touchpoint_id=EXCLUDED.first_touchpoint_id,
                  last_touchpoint_id=EXCLUDED.last_touchpoint_id,customer_link_evidence_id=EXCLUDED.customer_link_evidence_id,
                  first_source=EXCLUDED.first_source,first_campaign=EXCLUDED.first_campaign,first_landing_page=EXCLUDED.first_landing_page,
                  last_source=EXCLUDED.last_source,last_campaign=EXCLUDED.last_campaign,last_landing_page=EXCLUDED.last_landing_page,
                  confidence=EXCLUDED.confidence,unattributed_reason=EXCLUDED.unattributed_reason,
                  source_references=EXCLUDED.source_references,calculated_at=EXCLUDED.calculated_at
                """).param("id", UUID.randomUUID()).param("w", w).param("p", p).param("m", movement.id()).param("a", acquisition.id())
                .param("v", AttributionV1Engine.MODEL_VERSION).param("ft", id(r.first())).param("lt", id(r.last())).param("link", r.linkId())
                .param("fs", source(r.first())).param("fc", campaign(r.first())).param("fl", landing(r.first()))
                .param("ls", source(r.last())).param("lc", campaign(r.last())).param("ll", landing(r.last()))
                .param("confidence", r.confidence()).param("reason", r.reason()).param("refs", r.references().toArray(String[]::new)).param("at", at).update();
    }

    private List<Movement> movements(UUID w, String c) { return db.sql("SELECT id,movement_type,effective_at FROM customer_mrr_movements WHERE workspace_id=:w AND stripe_customer_id=:c AND calculation_version=:v ORDER BY effective_at,id").param("w",w).param("c",c).param("v",RevenueCalculationService.CALCULATION_VERSION).query((r,n)->new Movement(r.getObject(1,UUID.class),r.getString(2),r.getObject(3,OffsetDateTime.class))).list(); }
    private Link activeLink(UUID w, String c) { return db.sql("SELECT id,workspace_id,project_id,external_identity_id,evidence_source FROM stripe_customer_links WHERE workspace_id=:w AND stripe_customer_id=:c AND superseded_at IS NULL").param("w",w).param("c",c).query((r,n)->new Link(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class),r.getObject(4,UUID.class),r.getString(5))).optional().orElse(null); }
    private static CustomerAttributionExplanation.Evidence evidence(UUID id,String s,String c,String l){return id==null?null:new CustomerAttributionExplanation.Evidence(id,s,c,l);}
    private static UUID id(AttributionV1Engine.Touchpoint t){return t==null?null:UUID.fromString(t.id());} private static String source(AttributionV1Engine.Touchpoint t){return t==null?null:t.source();} private static String campaign(AttributionV1Engine.Touchpoint t){return t==null?null:t.campaign();} private static String landing(AttributionV1Engine.Touchpoint t){return t==null?null:t.landingPage();}
    private static void require(UUID w,UUID p,String c){if(w==null||p==null||c==null||c.isBlank())throw new IllegalArgumentException("workspace, project and customer are required");}
    private record Movement(UUID id,String type,OffsetDateTime at){} private record Link(UUID id,UUID workspaceId,UUID projectId,UUID identityId,String source){}
    private record Result(AttributionV1Engine.Touchpoint first,AttributionV1Engine.Touchpoint last,UUID linkId,String confidence,String reason,List<String> references){static Result unattributed(String reason){return unattributed(reason,null);} static Result unattributed(String reason,UUID linkId){return new Result(null,null,linkId,"UNATTRIBUTED",reason,linkId==null?List.of():List.of("stripe_customer_links:"+linkId));}}
}
