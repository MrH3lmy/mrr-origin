package com.mrrorigin.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Review fix: the manual send endpoint used to bypass the same {@code isConfigured()} gate the
 * scheduled tick honors, so a deployment with a blank Postmark token/sender/web-base-url could still
 * create and claim delivery rows (and record failures) while returning HTTP 200. A dedicated context
 * with everything left blank -- the real default shape of {@link EmailProperties}' env-var-backed
 * fields -- proves the manual endpoint now rejects before any work is created or claimed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class WeeklySummaryUnconfiguredDeliveryIntegrationTests {

    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:18-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
        // Deliberately left blank -- mirrors a fresh deployment before an operator configures Postmark.
    }

    private static final String OWNER = "user-owner";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcClient db;

    private UUID workspace;
    private UUID project;

    @BeforeEach
    void setUp() {
        db.sql("TRUNCATE workspaces CASCADE").update();
        workspace = UUID.randomUUID();
        project = UUID.randomUUID();
        db.sql("INSERT INTO workspaces (id, name, slug) VALUES (:id, 'w', :slug)")
                .param("id", workspace).param("slug", "w-" + workspace).update();
        db.sql("INSERT INTO workspace_members (workspace_id, subject_id, role, email) VALUES (:w, :s, 'OWNER', :e)")
                .param("w", workspace).param("s", OWNER).param("e", "owner@example.com")
                .update();
        db.sql("INSERT INTO projects (id, workspace_id, name, domain, public_key, timezone) "
                        + "VALUES (:p, :w, 'p', 'one.example', :k, 'UTC')")
                .param("p", project).param("w", workspace).param("k", "pk-" + project).update();
    }

    @Test
    void manualSendRejectsBeforeCreatingOrClaimingAnyWorkWhenNotConfigured() throws Exception {
        mockMvc.perform(post(sendPath()).with(token(OWNER))).andExpect(status().isServiceUnavailable());

        Long rowCount = db.sql("SELECT COUNT(*) FROM weekly_summary_deliveries WHERE project_id = :p")
                .param("p", project)
                .query(Long.class)
                .single();
        assertThat(rowCount).isZero();
    }

    private String sendPath() {
        return "/api/workspaces/" + workspace + "/projects/" + project + "/notifications/weekly-summary/send";
    }

    private RequestPostProcessor token(String subject) {
        return jwt().jwt(jwt -> jwt.subject(subject)
                .issuer("http://localhost:8081/realms/mrr-origin")
                .audience(List.of("mrr-origin-api")));
    }
}
