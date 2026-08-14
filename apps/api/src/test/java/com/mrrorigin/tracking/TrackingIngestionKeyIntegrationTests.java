package com.mrrorigin.tracking;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** #21's onboarding tracker-installation step: authenticated issue/rotate/lookup of a project's ingestion key. */
@Testcontainers
class TrackingIngestionKeyIntegrationTests extends AbstractTrackingIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    @Autowired
    private MockMvc mvc;

    @Test
    void noKeyExistsUntilOneIsIssued() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);

        mvc.perform(get(path(workspaceId, projectId)).with(token(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.present").value(false));
    }

    @Test
    void issuingAKeyReturnsTheSecretExactlyOnceAndNeverAgain() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);

        mvc.perform(post(path(workspaceId, projectId)).with(token(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secret").isNotEmpty())
                .andExpect(jsonPath("$.rotated").value(false));

        mvc.perform(get(path(workspaceId, projectId)).with(token(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.present").value(true))
                .andExpect(jsonPath("$.secret").doesNotExist());
    }

    @Test
    void issuingAgainRotatesAndRevokesThePriorKey() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);
        String firstSecret = issueKey(workspaceId, projectId);

        String secondBody = mvc.perform(post(path(workspaceId, projectId)).with(token(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rotated").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();

        org.assertj.core.api.Assertions.assertThat(secondBody).doesNotContain(firstSecret);
    }

    @Test
    void nonMemberCannotReadOrIssueAKey() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);

        mvc.perform(get(path(workspaceId, projectId)).with(token(OTHER_OWNER)))
                .andExpect(status().isNotFound());
        mvc.perform(post(path(workspaceId, projectId)).with(token(OTHER_OWNER)))
                .andExpect(status().isNotFound());
    }

    @Test
    void nonManagerCannotIssueButCanRead() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);
        addMember(workspaceId, VIEWER, "VIEWER");

        mvc.perform(get(path(workspaceId, projectId)).with(token(VIEWER)))
                .andExpect(status().isOk());
        mvc.perform(post(path(workspaceId, projectId)).with(token(VIEWER)))
                .andExpect(status().isForbidden());
    }

    private static String path(UUID workspaceId, UUID projectId) {
        return "/api/workspaces/%s/projects/%s/tracking/ingestion-key".formatted(workspaceId, projectId);
    }
}
