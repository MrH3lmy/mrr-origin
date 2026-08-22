package com.mrrorigin.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * P6 observability slice (#28/#90): automated structural validation for the checked-in Prometheus
 * alert rules and Grafana dashboard definition, since {@code promtool} is not available as a build
 * dependency here (see the PR description for why: no bundled Prometheus binary/plugin in this
 * repository's toolchain). This does not validate PromQL semantics the way {@code promtool check
 * rules} would -- it validates the file is well-formed YAML/JSON with the required shape every rule
 * and panel must have, which is what a review can't easily catch by eye across ~20 rules.
 */
class AlertRulesAndDashboardValidationTests {

    private static final Set<String> ALLOWED_SEVERITIES = Set.of("warning", "critical");

    @Test
    @SuppressWarnings("unchecked")
    void alertRulesFileIsWellFormedAndEveryRuleHasTheRequiredFields() throws IOException {
        Path path = repoRoot().resolve("docs/observability/alerts.yml");
        assertThat(Files.exists(path)).as("docs/observability/alerts.yml must exist").isTrue();

        Map<String, Object> document;
        try (var input = Files.newInputStream(path)) {
            document = new Yaml().load(input);
        }

        assertThat(document).containsKey("groups");
        List<Map<String, Object>> groups = (List<Map<String, Object>>) document.get("groups");
        assertThat(groups).isNotEmpty();

        int totalRules = 0;
        for (Map<String, Object> group : groups) {
            assertThat(group.get("name")).as("every group needs a name").isInstanceOf(String.class);
            List<Map<String, Object>> rules = (List<Map<String, Object>>) group.get("rules");
            assertThat(rules).as("group " + group.get("name") + " must have rules").isNotEmpty();
            for (Map<String, Object> rule : rules) {
                totalRules++;
                assertThat(rule.get("alert")).as("alert name").isInstanceOf(String.class);
                assertThat((String) rule.get("expr")).as("PromQL expr").isNotBlank();
                assertThat(rule.get("for")).as("for-duration").isNotNull();

                Map<String, Object> labels = (Map<String, Object>) rule.get("labels");
                assertThat(labels).as("labels").isNotNull();
                assertThat(ALLOWED_SEVERITIES).as("severity must be warning or critical")
                        .contains((String) labels.get("severity"));

                Map<String, Object> annotations = (Map<String, Object>) rule.get("annotations");
                assertThat(annotations).as("annotations").isNotNull();
                assertThat((String) annotations.get("summary")).as("summary").isNotBlank();
                assertThat((String) annotations.get("description")).as("description").isNotBlank();
            }
        }
        assertThat(totalRules).isGreaterThanOrEqualTo(15);
    }

    @Test
    void dashboardJsonParsesAndHasAReasonableNumberOfRealPanels() throws IOException {
        Path path = repoRoot().resolve("docs/observability/dashboard.json");
        assertThat(Files.exists(path)).as("docs/observability/dashboard.json must exist").isTrue();

        JsonNode dashboard = new ObjectMapper().readTree(Files.readString(path));
        assertThat(dashboard.path("title").asText()).isNotBlank();
        assertThat(dashboard.path("panels").isArray()).isTrue();

        long realPanels = 0;
        for (JsonNode panel : dashboard.path("panels")) {
            assertThat(panel.path("title").asText()).isNotBlank();
            assertThat(panel.path("type").asText()).isNotBlank();
            if (!"row".equals(panel.path("type").asText())) {
                realPanels++;
                assertThat(panel.path("targets").isArray()).as("panel " + panel.path("title").asText() + " needs targets")
                        .isTrue();
                assertThat(panel.path("targets").size()).isGreaterThan(0);
                for (JsonNode target : panel.path("targets")) {
                    assertThat(target.path("expr").asText()).isNotBlank();
                }
            }
        }
        // "Not dozens of panels" per the issue -- a private-beta operational dashboard, not a BI product.
        assertThat(realPanels).isGreaterThan(0).isLessThan(25);
    }

    private static Path repoRoot() {
        // apps/api is always two directories below the repository root in this layout.
        return Path.of("").toAbsolutePath().getParent().getParent();
    }
}
