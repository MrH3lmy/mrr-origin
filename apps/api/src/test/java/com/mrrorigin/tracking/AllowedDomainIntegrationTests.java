package com.mrrorigin.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** #21's onboarding tracker-installation step: authenticated allowed-domain configuration. */
@Testcontainers
class AllowedDomainIntegrationTests extends AbstractTrackingIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    @Test
    void addingAndListingDomainsRoundTrips() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);

        mvc.perform(get(path(workspaceId, projectId)).with(token(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));

        mvc.perform(post(path(workspaceId, projectId)).with(token(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"App.Example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domain").value("app.example.com"));

        mvc.perform(get(path(workspaceId, projectId)).with(token(OWNER)))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].domain").value("app.example.com"));
    }

    @Test
    void invalidDomainIsRejected() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);

        mvc.perform(post(path(workspaceId, projectId)).with(token(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"https://app.example.com/path\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void removingADomainStopsItFromBeingListed() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);

        String body = mvc.perform(post(path(workspaceId, projectId)).with(token(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"app.example.com\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode json = OBJECT_MAPPER.readTree(body);
        String domainId = json.get("id").asText();

        mvc.perform(delete(path(workspaceId, projectId) + "/" + domainId).with(token(OWNER)))
                .andExpect(status().isNoContent());

        mvc.perform(get(path(workspaceId, projectId)).with(token(OWNER)))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void removingAnUnknownDomainReturnsNotFound() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);

        mvc.perform(delete(path(workspaceId, projectId) + "/" + UUID.randomUUID()).with(token(OWNER)))
                .andExpect(status().isNotFound());
    }

    @Test
    void nonMemberCannotReadOrManageDomains() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);

        mvc.perform(get(path(workspaceId, projectId)).with(token(OTHER_OWNER)))
                .andExpect(status().isNotFound());
        mvc.perform(post(path(workspaceId, projectId)).with(token(OTHER_OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"app.example.com\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aDomainAddedInOneProjectIsInvisibleFromAnother() throws Exception {
        UUID workspaceA = createWorkspace(OWNER);
        UUID projectA = createProject(workspaceA);
        UUID workspaceB = createWorkspace(OWNER);
        UUID projectB = createProject(workspaceB);

        mvc.perform(post(path(workspaceA, projectA)).with(token(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"app.example.com\"}"))
                .andExpect(status().isOk());

        mvc.perform(get(path(workspaceB, projectB)).with(token(OWNER)))
                .andExpect(jsonPath("$.length()").value(0));
    }

    private static String path(UUID workspaceId, UUID projectId) {
        return "/api/workspaces/%s/projects/%s/tracking/allowed-domains".formatted(workspaceId, projectId);
    }
}
