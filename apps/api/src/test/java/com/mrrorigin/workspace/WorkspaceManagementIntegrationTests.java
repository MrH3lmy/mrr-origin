package com.mrrorigin.workspace;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class WorkspaceManagementIntegrationTests {

    private static final String ALICE = "user-alice";
    private static final String BOB = "user-bob";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void clearTenantData() {
        new JdbcTemplate(dataSource).execute("TRUNCATE TABLE projects, workspace_members, workspaces CASCADE");
    }

    @Test
    void anonymousManagementRequestsAreRejectedWhileHealthRemainsPublic() throws Exception {
        mockMvc.perform(get("/api/workspaces")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/workspaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void ownerCanCreateAndReadWorkspaceMembersAndProjects() throws Exception {
        UUID workspaceId = createWorkspace(ALICE, "Acme Analytics", "acme-analytics");

        mockMvc.perform(get("/api/workspaces").with(token(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(workspaceId.toString()))
                .andExpect(jsonPath("$[0].role").value("OWNER"));

        mockMvc.perform(get("/api/workspaces/{workspaceId}", workspaceId).with(token(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("acme-analytics"))
                .andExpect(jsonPath("$.reportingCurrency").value("USD"));

        addMember(workspaceId, ALICE, BOB, "MEMBER");

        mockMvc.perform(get("/api/workspaces/{workspaceId}/members", workspaceId).with(token(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        mockMvc.perform(get("/api/workspaces/{workspaceId}/members/{subjectId}", workspaceId, BOB)
                        .with(token(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MEMBER"));

        UUID projectId = createProject(workspaceId, ALICE, "Marketing Site", "WWW.Example.COM", "Africa/Cairo");

        mockMvc.perform(get("/api/workspaces/{workspaceId}/projects", workspaceId).with(token(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].domain").value("www.example.com"));

        mockMvc.perform(get("/api/workspaces/{workspaceId}/projects/{projectId}", workspaceId, projectId)
                        .with(token(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value("Africa/Cairo"))
                .andExpect(jsonPath("$.publicKey").isString());
    }

    @Test
    void crossWorkspaceAccessIsHiddenAndDenied() throws Exception {
        UUID workspaceId = createWorkspace(ALICE, "Alice Workspace", "alice-workspace");
        UUID projectId = createProject(workspaceId, ALICE, "Alice Site", "alice.example.com", "UTC");

        mockMvc.perform(get("/api/workspaces/{workspaceId}", workspaceId).with(token(BOB)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/workspaces/{workspaceId}/projects", workspaceId).with(token(BOB)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/workspaces/{workspaceId}/projects/{projectId}", workspaceId, projectId)
                        .with(token(BOB)))
                .andExpect(status().isNotFound());
    }

    @Test
    void memberCanReadButCannotManageAndDuplicatesConflict() throws Exception {
        UUID workspaceId = createWorkspace(ALICE, "Shared Workspace", "shared-workspace");
        addMember(workspaceId, ALICE, BOB, "MEMBER");
        UUID projectId = createProject(workspaceId, ALICE, "Shared Site", "shared.example.com", null);

        mockMvc.perform(get("/api/workspaces/{workspaceId}/projects/{projectId}", workspaceId, projectId)
                        .with(token(BOB)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/workspaces/{workspaceId}/projects", workspaceId)
                        .with(token(BOB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Denied Site", "domain", "denied.example.com"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/workspaces/{workspaceId}/members", workspaceId)
                        .with(token(ALICE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("subjectId", BOB, "role", "MEMBER"))))
                .andExpect(status().isConflict());
    }

    private UUID createWorkspace(String subject, String name, String slug) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/workspaces")
                        .with(token(subject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", name, "slug", slug, "reportingCurrency", "USD"))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(json(result).get("id").asText());
    }

    private void addMember(UUID workspaceId, String actingSubject, String subjectId, String role)
            throws Exception {
        mockMvc.perform(post("/api/workspaces/{workspaceId}/members", workspaceId)
                        .with(token(actingSubject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("subjectId", subjectId, "role", role))))
                .andExpect(status().isCreated());
    }

    private UUID createProject(
            UUID workspaceId, String subject, String name, String domain, String timezone)
            throws Exception {
        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("name", name);
        request.put("domain", domain);
        if (timezone != null) {
            request.put("timezone", timezone);
        }

        MvcResult result = mockMvc.perform(post("/api/workspaces/{workspaceId}/projects", workspaceId)
                        .with(token(subject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(json(result).get("id").asText());
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private RequestPostProcessor token(String subject) {
        return jwt().jwt(jwt -> jwt.subject(subject)
                .issuer("http://localhost:8081/realms/mrr-origin")
                .audience(List.of("mrr-origin-api")));
    }
}
