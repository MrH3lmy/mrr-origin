package com.mrrorigin.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringWriter;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest
class BillingWorkspaceExportUsageTypeIntegrationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    @Autowired private JdbcClient jdbc;
    @Autowired private BillingLedgerUpsertService ledger;
    @Autowired private BillingWorkspaceExportService exportService;

    private UUID workspaceId;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE TABLE workspaces CASCADE").update();
        workspaceId = UUID.randomUUID();
        jdbc.sql("INSERT INTO workspaces (id, name, slug) VALUES (:id, 'export-test', :slug)")
                .param("id", workspaceId)
                .param("slug", "export-test-" + workspaceId)
                .update();
    }

    @Test
    void billingExportIncludesPriceUsageType() throws Exception {
        var sourceVersion = new BillingSourceVersion.SourceVersion(1_800_000_000L, "W:evt_price_export");
        var price = new StripeBillingObjects.ParsedPrice(
                "price_export_metered",
                "prod_export_metered",
                "usd",
                500L,
                "per_unit",
                "recurring",
                "month",
                1,
                "metered",
                true);
        ledger.upsertPrice(workspaceId, price, sourceVersion, BillingLedgerSource.WEBHOOK);

        var out = new StringWriter();
        exportService.writeNdjson(workspaceId, out);

        assertThat(out.toString())
                .contains("\"table\":\"billing_prices\"")
                .contains("\"stripe_price_id\":\"price_export_metered\"")
                .contains("\"usage_type\":\"metered\"");
    }
}
