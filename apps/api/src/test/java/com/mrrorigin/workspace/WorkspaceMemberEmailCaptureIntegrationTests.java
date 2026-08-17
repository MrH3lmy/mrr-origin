package com.mrrorigin.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * #59's best-effort email capture ({@link WorkspaceContext}/{@link WorkspaceMemberEmailCaptureService}):
 * a member's email is filled in from their own JWT {@code email} claim on their next authenticated
 * request, exactly once, and never required.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class WorkspaceMemberEmailCaptureIntegrationTests {

    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:18-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }

    private static final String SUBJECT = "user-1";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcClient db;

    private UUID workspace;

    @BeforeEach
    void setUp() {
        db.sql("TRUNCATE workspaces CASCADE").update();
        workspace = UUID.randomUUID();
        db.sql("INSERT INTO workspaces (id, name, slug) VALUES (:id, 'w', :slug)")
                .param("id", workspace).param("slug", "w-" + workspace).update();
        db.sql("INSERT INTO workspace_members (workspace_id, subject_id, role) VALUES (:w, :s, 'OWNER')")
                .param("w", workspace).param("s", SUBJECT).update();
    }

    @Test
    void capturesEmailFromJwtClaimOnFirstAuthenticatedRequest() throws Exception {
        assertThat(emailInDb()).isNull();

        mockMvc.perform(get("/api/workspaces/{id}", workspace).with(token(SUBJECT, "owner@example.com")))
                .andExpect(status().isOk());

        assertThat(emailInDb()).isEqualTo("owner@example.com");
    }

    @Test
    void doesNotOverwriteAnAlreadyCapturedEmail() throws Exception {
        mockMvc.perform(get("/api/workspaces/{id}", workspace).with(token(SUBJECT, "first@example.com")))
                .andExpect(status().isOk());
        assertThat(emailInDb()).isEqualTo("first@example.com");

        mockMvc.perform(get("/api/workspaces/{id}", workspace).with(token(SUBJECT, "second@example.com")))
                .andExpect(status().isOk());

        assertThat(emailInDb()).isEqualTo("first@example.com");
    }

    @Test
    void missingEmailClaimLeavesEmailNull() throws Exception {
        mockMvc.perform(get("/api/workspaces/{id}", workspace).with(token(SUBJECT, null)))
                .andExpect(status().isOk());

        assertThat(emailInDb()).isNull();
    }

    private String emailInDb() {
        return db.sql("SELECT email FROM workspace_members WHERE workspace_id = :w AND subject_id = :s")
                .param("w", workspace).param("s", SUBJECT)
                .query((rs, rowNum) -> rs.getString("email"))
                .list()
                .get(0);
    }

    private RequestPostProcessor token(String subject, String email) {
        return jwt().jwt(jwt -> {
            jwt.subject(subject)
                    .issuer("http://localhost:8081/realms/mrr-origin")
                    .audience(List.of("mrr-origin-api"));
            if (email != null) {
                jwt.claim("email", email);
            }
        });
    }
}
