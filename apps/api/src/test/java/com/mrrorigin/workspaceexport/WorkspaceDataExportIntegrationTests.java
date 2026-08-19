package com.mrrorigin.workspaceexport;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import tools.jackson.databind.ObjectMapper;

/**
 * #64's workspace data export: manager-only authorization, cross-tenant denial, manifest
 * correctness (including row counts matching the actual NDJSON contents), explicit exclusion of
 * credentials/secret digests/lease/checkpoint tokens, export-on-success auditing, and streaming
 * behavior across a keyset-pagination page boundary.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class WorkspaceDataExportIntegrationTests {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:18-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }

    private static final String OWNER = "user-owner";
    private static final String ADMIN = "user-admin";
    private static final String MEMBER = "user-member";
    private static final String VIEWER = "user-viewer";

    private static final List<String> EXPECTED_NDJSON_FILES = List.of(
            "billing.ndjson", "revenue.ndjson", "attribution.ndjson", "reporting.ndjson", "notification.ndjson",
            "tracking.ndjson");

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcClient db;
    @Autowired private ObjectMapper objectMapper;

    private UUID workspace;
    private UUID project;

    @BeforeEach
    void setUp() {
        db.sql("TRUNCATE workspaces CASCADE").update();
        workspace = UUID.randomUUID();
        project = UUID.randomUUID();
        db.sql("INSERT INTO workspaces (id, name, slug) VALUES (:id, 'w', :slug)")
                .param("id", workspace).param("slug", "w-" + workspace).update();
        db.sql("INSERT INTO workspace_members (workspace_id, subject_id, role) VALUES (:w, :s, 'OWNER')")
                .param("w", workspace).param("s", OWNER).update();
        db.sql("INSERT INTO workspace_members (workspace_id, subject_id, role) VALUES (:w, :s, 'ADMIN')")
                .param("w", workspace).param("s", ADMIN).update();
        db.sql("INSERT INTO workspace_members (workspace_id, subject_id, role) VALUES (:w, :s, 'MEMBER')")
                .param("w", workspace).param("s", MEMBER).update();
        db.sql("INSERT INTO workspace_members (workspace_id, subject_id, role) VALUES (:w, :s, 'VIEWER')")
                .param("w", workspace).param("s", VIEWER).update();
        db.sql("INSERT INTO projects (id, workspace_id, name, domain, public_key) VALUES (:p, :w, 'p', 'one.example', :k)")
                .param("p", project).param("w", workspace).param("k", "pk-" + project).update();
    }

    @Test
    void ownerAndAdminCanExportButMemberAndViewerAreRejected() throws Exception {
        mockMvc.perform(exportRequest(OWNER)).andExpect(status().isOk());
        mockMvc.perform(exportRequest(ADMIN)).andExpect(status().isOk());
        mockMvc.perform(exportRequest(MEMBER)).andExpect(status().isForbidden());
        mockMvc.perform(exportRequest(VIEWER)).andExpect(status().isForbidden());
    }

    @Test
    void exportResponseIsAZipWithSchemaVersionHeader() throws Exception {
        var result = mockMvc.perform(exportRequest(OWNER))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("X-Export-Schema-Version", "workspace-export-v1"))
                .andReturn();
        Assertions.assertTrue(
                result.getResponse().getContentType() != null
                        && result.getResponse().getContentType().contains("application/zip"));
    }

    @Test
    void isCrossTenantIsolated() throws Exception {
        UUID otherWorkspace = UUID.randomUUID();
        db.sql("INSERT INTO workspaces (id, name, slug) VALUES (:id, 'w2', :slug)")
                .param("id", otherWorkspace).param("slug", "w2-" + otherWorkspace).update();
        db.sql("INSERT INTO workspace_members (workspace_id, subject_id, role) VALUES (:w, :s, 'OWNER')")
                .param("w", otherWorkspace).param("s", "other-owner").update();
        insertBillingCustomer("cus_secret_to_workspace");

        // An owner of a different workspace cannot export this one -- 404, not 403, so workspace
        // existence is never leaked to a non-member.
        mockMvc.perform(get("/api/workspaces/{workspaceId}/exports/data", workspace).with(token("other-owner")))
                .andExpect(status().isNotFound());

        // The other workspace's own export must never contain this workspace's data.
        byte[] zipBytes = mockMvc.perform(get("/api/workspaces/{workspaceId}/exports/data", otherWorkspace).with(token("other-owner")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        Map<String, String> entries = readZipEntriesAsText(zipBytes);
        Assertions.assertFalse(entries.get("billing.ndjson").contains("cus_secret_to_workspace"));
    }

    @Test
    void manifestContainsWorkspaceIdTimestampActorAndFileList() throws Exception {
        byte[] zipBytes = mockMvc.perform(exportRequest(OWNER)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        Map<String, String> entries = readZipEntriesAsText(zipBytes);
        Assertions.assertTrue(entries.containsKey("manifest.json"));

        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = objectMapper.readValue(entries.get("manifest.json"), Map.class);
        Assertions.assertEquals("workspace-export-v1", manifest.get("schemaVersion"));
        Assertions.assertEquals(workspace.toString(), manifest.get("workspaceId"));
        Assertions.assertEquals(OWNER, manifest.get("actorSubjectId"));
        Assertions.assertNotNull(manifest.get("exportedAt"));
        OffsetDateTime.parse((String) manifest.get("exportedAt")); // must be a valid timestamp

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files = (List<Map<String, Object>>) manifest.get("files");
        Assertions.assertEquals(EXPECTED_NDJSON_FILES.size(), files.size());
        List<String> fileNames = files.stream().map(f -> (String) f.get("name")).toList();
        for (String expected : EXPECTED_NDJSON_FILES) {
            Assertions.assertTrue(fileNames.contains(expected), "manifest missing " + expected + ": " + fileNames);
        }
    }

    @Test
    void manifestRowCountsMatchActualNdjsonContents() throws Exception {
        insertBillingCustomer("cus_a");
        insertBillingCustomer("cus_b");
        movement("cus_a", 1000, "2026-04-05T00:00:00Z", "NEW");
        movement("cus_a", 700, "2026-04-06T00:00:00Z", "CHURN");

        byte[] zipBytes = mockMvc.perform(exportRequest(OWNER)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        Map<String, byte[]> rawEntries = readZipEntries(zipBytes);
        Map<String, String> entries = new LinkedHashMap<>();
        rawEntries.forEach((name, bytes) -> entries.put(name, new String(bytes, StandardCharsets.UTF_8)));

        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = objectMapper.readValue(rawEntries.get("manifest.json"), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files = (List<Map<String, Object>>) manifest.get("files");

        for (Map<String, Object> file : files) {
            String name = (String) file.get("name");
            long manifestRowCount = ((Number) file.get("rowCount")).longValue();
            long actualRowCount = entries.get(name).lines().filter(l -> !l.isBlank()).count();
            Assertions.assertEquals(manifestRowCount, actualRowCount, name + " manifest row count must match NDJSON line count");
        }
        // Sanity: billing.ndjson actually has the 2 customers + 2 movements-worth of rows we inserted.
        long billingRows = entries.get("billing.ndjson").lines().filter(l -> !l.isBlank()).count();
        Assertions.assertEquals(2, billingRows);
        long revenueRows = entries.get("revenue.ndjson").lines().filter(l -> !l.isBlank()).count();
        Assertions.assertEquals(2, revenueRows);
    }

    @Test
    void exportedBundleNeverContainsCredentialsSecretDigestsOrLeaseCheckpointTokens() throws Exception {
        String ingestionKeySecretHash = "1".repeat(64);
        String oauthStateHash = "2".repeat(64);
        String verificationToken = "3".repeat(43);
        UUID leaseToken = UUID.fromString("44444444-4444-4444-4444-444444444444");
        String syncCheckpoint = "SYNC_CHECKPOINT_SECRET_MARKER_5555";

        // project_ingestion_keys.secret_hash
        db.sql("""
                        INSERT INTO project_ingestion_keys (id, workspace_id, project_id, key_prefix, secret_hash, created_at)
                        VALUES (:id, :w, :p, :prefix, :hash, now())
                        """)
                .param("id", UUID.randomUUID()).param("w", workspace).param("p", project)
                .param("prefix", "pk_" + UUID.randomUUID().toString().substring(0, 18)).param("hash", ingestionKeySecretHash).update();

        // stripe_oauth_states -- excluded entirely.
        db.sql("""
                        INSERT INTO stripe_oauth_states (id, workspace_id, subject_id, mode, state_hash, created_at, expires_at)
                        VALUES (:id, :w, :s, 'TEST', :hash, now(), now() + interval '10 minutes')
                        """)
                .param("id", UUID.randomUUID()).param("w", workspace).param("s", OWNER).param("hash", oauthStateHash).update();

        // tracking_verification_attempts.token
        db.sql("""
                        INSERT INTO tracking_verification_attempts (id, workspace_id, project_id, token, status, created_at, expires_at)
                        VALUES (:id, :w, :p, :token, 'PENDING', now(), now() + interval '10 minutes')
                        """)
                .param("id", UUID.randomUUID()).param("w", workspace).param("p", project).param("token", verificationToken).update();

        // weekly_summary_deliveries.lease_token / lease_until
        db.sql("""
                        INSERT INTO weekly_summary_deliveries
                            (id, workspace_id, project_id, recipient_subject_id, recipient_email, week_start, status,
                             lease_token, lease_until, next_attempt_at)
                        VALUES (:id, :w, :p, :s, :email, '2026-04-01', 'SENDING', :lease, now() + interval '5 minutes', now())
                        """)
                .param("id", UUID.randomUUID()).param("w", workspace).param("p", project).param("s", OWNER)
                .param("email", "owner@example.test")
                .param("lease", leaseToken).update();

        // stripe_connections.sync_checkpoint
        db.sql("""
                        INSERT INTO stripe_connections
                            (id, workspace_id, stripe_account_id, mode, granted_scope, status, verification_status,
                             sync_checkpoint, connected_at)
                        VALUES (:id, :w, :acct, 'TEST', 'read_only', 'ACTIVE', 'VERIFIED', :checkpoint, now())
                        """)
                .param("id", UUID.randomUUID()).param("w", workspace).param("acct", "acct_" + UUID.randomUUID())
                .param("checkpoint", syncCheckpoint).update();

        byte[] zipBytes = mockMvc.perform(exportRequest(OWNER)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        String allText = String.join("\n", readZipEntriesAsText(zipBytes).values());

        Assertions.assertFalse(allText.contains(ingestionKeySecretHash), "ingestion key secret_hash must never be exported");
        Assertions.assertFalse(allText.contains(oauthStateHash), "oauth CSRF state_hash must never be exported");
        Assertions.assertFalse(allText.contains(verificationToken), "verification token must never be exported");
        Assertions.assertFalse(allText.contains(leaseToken.toString()), "lease_token must never be exported");
        Assertions.assertFalse(allText.contains(syncCheckpoint), "sync_checkpoint must never be exported");
        // stripe_oauth_states is excluded entirely -- its non-secret columns must not leak either.
        Assertions.assertFalse(allText.contains("stripe_oauth_states"));
    }

    @Test
    void recordsAnExportAuditEntryOnSuccessWithoutExportedRowContent() throws Exception {
        insertBillingCustomer("cus_audited");

        mockMvc.perform(exportRequest(OWNER)).andExpect(status().isOk());

        List<String> auditRows = db.sql(
                        "SELECT schema_version, actor_subject_id, total_row_count, row_counts::text AS row_counts "
                                + "FROM workspace_export_audit_log WHERE workspace_id = :w")
                .param("w", workspace)
                .query((rs, n) -> rs.getString("schema_version") + "|" + rs.getString("actor_subject_id") + "|"
                        + rs.getLong("total_row_count") + "|" + rs.getString("row_counts"))
                .list();
        Assertions.assertEquals(1, auditRows.size());
        String audit = auditRows.get(0);
        Assertions.assertTrue(audit.startsWith("workspace-export-v1|" + OWNER + "|"), "audit row: " + audit);
        Assertions.assertFalse(audit.contains("cus_audited"), "audit must never contain exported row content: " + audit);
        Assertions.assertTrue(audit.contains("\"billing\""), "audit row_counts must be keyed by domain: " + audit);
    }

    @Test
    void streamsMoreRowsThanOnePageWithoutTruncating() throws Exception {
        // Larger than WorkspaceExportStreaming's page size (500), so this crosses at least one
        // keyset-pagination page boundary; truncation at the first page would under-count here.
        int total = 520;
        for (int i = 0; i < total; i++) {
            insertBillingCustomer("cus_bulk_" + i);
        }

        byte[] zipBytes = mockMvc.perform(exportRequest(OWNER)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        Map<String, String> entries = readZipEntriesAsText(zipBytes);
        long billingRows = entries.get("billing.ndjson").lines().filter(l -> !l.isBlank()).count();
        Assertions.assertEquals(total, billingRows);

        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = objectMapper.readValue(entries.get("manifest.json"), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files = (List<Map<String, Object>>) manifest.get("files");
        long manifestBillingRowCount = files.stream()
                .filter(f -> "billing.ndjson".equals(f.get("name")))
                .findFirst()
                .map(f -> ((Number) f.get("rowCount")).longValue())
                .orElseThrow();
        Assertions.assertEquals(total, manifestBillingRowCount);
    }

    // -- fixtures --

    private void insertBillingCustomer(String stripeCustomerId) {
        db.sql(
                        """
                        INSERT INTO billing_customers
                            (id, workspace_id, stripe_customer_id, provider_created_at, source, source_version, source_sequence)
                        VALUES (:id, :w, :c, now(), 'BACKFILL', 1, :c)
                        """)
                .param("id", UUID.randomUUID()).param("w", workspace).param("c", stripeCustomerId).update();
    }

    private void movement(String stripeCustomerId, long amountMinor, String effectiveAt, String movementType) {
        db.sql(
                        """
                        INSERT INTO customer_mrr_movements
                            (id, workspace_id, stripe_customer_id, currency, amount_minor, movement_type,
                             effective_at, calculation_version, source_billing_references)
                        VALUES (:id, :w, :c, 'USD', :amt, :type, :at, 'mrr-v1', ARRAY['billing:test'])
                        """)
                .param("id", UUID.randomUUID())
                .param("w", workspace)
                .param("c", stripeCustomerId)
                .param("amt", amountMinor)
                .param("type", movementType)
                .param("at", OffsetDateTime.parse(effectiveAt))
                .update();
    }

    private MockHttpServletRequestBuilder exportRequest(String actor) {
        return get("/api/workspaces/{workspaceId}/exports/data", workspace).with(token(actor));
    }

    private RequestPostProcessor token(String subject) {
        return jwt().jwt(jwt -> jwt.subject(subject)
                .issuer("http://localhost:8081/realms/mrr-origin")
                .audience(List.of("mrr-origin-api")));
    }

    private static Map<String, byte[]> readZipEntries(byte[] zipBytes) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), zis.readAllBytes());
            }
        }
        return entries;
    }

    private static Map<String, String> readZipEntriesAsText(byte[] zipBytes) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        readZipEntries(zipBytes).forEach((name, bytes) -> entries.put(name, new String(bytes, StandardCharsets.UTF_8)));
        return entries;
    }
}
