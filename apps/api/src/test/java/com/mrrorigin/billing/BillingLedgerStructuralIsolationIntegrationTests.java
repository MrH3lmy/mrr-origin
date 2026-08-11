package com.mrrorigin.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * #12's structural (schema-enforced, not merely convention-enforced) tenant isolation:
 * {@code billing_subscription_items} and {@code billing_subscription_status_events} carry a
 * composite {@code (workspace_id, subscription_id)} foreign key to {@code
 * billing_subscriptions(workspace_id, id)}, so a row claiming workspace A cannot reference a
 * subscription that actually belongs to workspace B -- the database rejects it outright, not just
 * application code that happens to always pass matching values.
 */
@Testcontainers
class BillingLedgerStructuralIsolationIntegrationTests extends AbstractBillingLedgerIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    @Test
    void subscriptionItemCannotReferenceAnotherWorkspacesSubscription() {
        UUID workspaceA = createWorkspace();
        UUID workspaceB = createWorkspace();
        UUID subscriptionAId = insertSubscription(workspaceA, "sub_a");

        assertThatThrownBy(() -> insertSubscriptionItem(workspaceB, subscriptionAId, "si_cross_tenant"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void subscriptionStatusEventCannotReferenceAnotherWorkspacesSubscription() {
        UUID workspaceA = createWorkspace();
        UUID workspaceB = createWorkspace();
        UUID subscriptionAId = insertSubscription(workspaceA, "sub_a2");

        assertThatThrownBy(() -> insertSubscriptionStatusEvent(workspaceB, subscriptionAId, "sub_a2", 1L))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void subscriptionItemReferencingItsOwnWorkspacesSubscriptionSucceeds() {
        UUID workspaceA = createWorkspace();
        UUID subscriptionAId = insertSubscription(workspaceA, "sub_a3");

        insertSubscriptionItem(workspaceA, subscriptionAId, "si_same_tenant");

        assertThat(jdbc().sql("SELECT COUNT(*) FROM billing_subscription_items WHERE subscription_id = :id")
                        .param("id", subscriptionAId)
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    private UUID insertSubscription(UUID workspaceId, String stripeSubscriptionId) {
        UUID id = UUID.randomUUID();
        jdbc().sql(
                        """
                        INSERT INTO billing_subscriptions
                            (id, workspace_id, stripe_subscription_id, stripe_customer_id, status, currency,
                             source, source_version, source_sequence, updated_at)
                        VALUES (:id, :workspaceId, :stripeSubscriptionId, 'cus_x', 'active', 'usd', 'BACKFILL', 1, 'test', :now)
                        """)
                .param("id", id)
                .param("workspaceId", workspaceId)
                .param("stripeSubscriptionId", stripeSubscriptionId)
                .param("now", OffsetDateTime.now())
                .update();
        return id;
    }

    private void insertSubscriptionItem(UUID workspaceId, UUID subscriptionId, String stripeItemId) {
        jdbc().sql(
                        """
                        INSERT INTO billing_subscription_items
                            (id, workspace_id, subscription_id, stripe_subscription_item_id, stripe_price_id,
                             quantity, source_version, source_sequence, updated_at)
                        VALUES (:id, :workspaceId, :subscriptionId, :stripeItemId, 'price_x', 1, 1, 'test', :now)
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", workspaceId)
                .param("subscriptionId", subscriptionId)
                .param("stripeItemId", stripeItemId)
                .param("now", OffsetDateTime.now())
                .update();
    }

    private void insertSubscriptionStatusEvent(UUID workspaceId, UUID subscriptionId, String stripeSubscriptionId, long sourceVersion) {
        jdbc().sql(
                        """
                        INSERT INTO billing_subscription_status_events
                            (id, workspace_id, subscription_id, stripe_subscription_id, previous_status, new_status,
                             source, source_version, source_sequence)
                        VALUES (:id, :workspaceId, :subscriptionId, :stripeSubscriptionId, NULL, 'active', 'BACKFILL', :sourceVersion, 'test')
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", workspaceId)
                .param("subscriptionId", subscriptionId)
                .param("stripeSubscriptionId", stripeSubscriptionId)
                .param("sourceVersion", sourceVersion)
                .update();
    }
}
