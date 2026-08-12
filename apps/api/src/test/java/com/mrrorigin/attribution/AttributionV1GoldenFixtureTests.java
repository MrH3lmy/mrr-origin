package com.mrrorigin.attribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Proves ADR-0005's selection, evidence, conflict, inheritance, and recalculation rules are
 * internally consistent against the provider-neutral fixture. This is the decision contract for
 * #17 -- it re-implements the ADR's pure selection rules to validate the fixture, not the
 * attribution engine itself, which is #18's scope.
 */
class AttributionV1GoldenFixtureTests {

    private static JsonNode fixture;

    @BeforeAll
    static void loadProviderNeutralFixture() throws IOException {
        try (var input = AttributionV1GoldenFixtureTests.class.getResourceAsStream("/golden/attribution-v1.json")) {
            assertThat(input).as("golden fixture exists").isNotNull();
            fixture = new ObjectMapper().readTree(input);
        }
    }

    @Test
    void selectionCasesProvePoolingWindowingAndDirectHandling() {
        for (JsonNode testCase : fixture.path("selectionCases")) {
            String caseId = testCase.path("id").asText();
            OffsetDateTime anchor = OffsetDateTime.parse(testCase.path("anchor").asText());
            long windowDays = testCase.path("windowDays").asLong(fixture.path("defaultWindowDays").asLong());

            List<Touchpoint> all = readTouchpoints(testCase.path("touchpoints"));
            List<Touchpoint> pool = eligiblePool(all, anchor, windowDays);

            assertThat(ids(pool)).as(caseId)
                    .containsExactlyInAnyOrderElementsOf(textList(testCase.path("expectedEligibleTouchpointIds")));

            assertThat(idOrNull(selectFirstTouch(pool))).as(caseId + " first-touch")
                    .isEqualTo(testCase.path("expectedFirstTouchId").asText(null));
            assertThat(idOrNull(selectLastTouch(pool, anchor))).as(caseId + " last-touch")
                    .isEqualTo(testCase.path("expectedLastTouchId").asText(null));

            if (testCase.has("expectedDistinctVisitorCount")) {
                long distinctVisitors = pool.stream().map(Touchpoint::visitorId).distinct().count();
                assertThat(distinctVisitors).as(caseId)
                        .isEqualTo(testCase.path("expectedDistinctVisitorCount").asLong());
            }
        }

        assertThat(caseIds("selectionCases"))
                .contains(
                        "delayed-conversion",
                        "multiple-sessions",
                        "direct-return",
                        "first-touch-vs-last-touch-divergence",
                        "merged-anonymous-visitors",
                        "window-boundary-timestamps",
                        "utm-medium-is-non-direct");
    }

    @Test
    void evidenceCasesMapEveryConfidenceLabelToStoredEvidenceOrNone() {
        for (JsonNode testCase : fixture.path("evidenceCases")) {
            String caseId = testCase.path("id").asText();
            OffsetDateTime anchor = OffsetDateTime.parse(testCase.path("anchor").asText());
            List<Touchpoint> pool =
                    eligiblePool(readTouchpoints(testCase.path("touchpoints")), anchor, fixture.path("defaultWindowDays").asLong());

            boolean linkExists = testCase.has("link") && !testCase.path("link").isNull();
            String confidence = !linkExists
                    ? "UNATTRIBUTED"
                    : pool.isEmpty() ? "UNATTRIBUTED" : testCase.path("link").path("tier").asText();
            String evidenceReference = "UNATTRIBUTED".equals(confidence)
                    ? null
                    : testCase.path("link").path("evidenceReference").asText();

            assertThat(confidence).as(caseId).isEqualTo(testCase.path("expectedConfidence").asText());
            assertThat(evidenceReference).as(caseId).isEqualTo(testCase.path("expectedEvidenceReference").asText(null));
            assertThat(idOrNull(selectFirstTouch(pool))).as(caseId + " first-touch")
                    .isEqualTo(testCase.path("expectedFirstTouchId").asText(null));
            assertThat(idOrNull(selectLastTouch(pool, anchor))).as(caseId + " last-touch")
                    .isEqualTo(testCase.path("expectedLastTouchId").asText(null));
        }

        assertThat(caseIds("evidenceCases"))
                .contains(
                        "stripe-metadata-verified-evidence",
                        "explicit-external-user-to-stripe-customer-evidence",
                        "keyed-email-alias-moderate-evidence",
                        "no-evidence",
                        "no-evidence-link-with-empty-pool");
    }

    @Test
    void conflictingDeterministicEvidencePrefersTheHigherTierAndRecordsTheConflict() {
        for (JsonNode testCase : fixture.path("conflictCases")) {
            String caseId = testCase.path("id").asText();
            OffsetDateTime anchor = OffsetDateTime.parse(testCase.path("anchor").asText());
            List<Touchpoint> all = readTouchpoints(testCase.path("touchpoints"));

            List<JsonNode> links = new ArrayList<>();
            testCase.path("links").forEach(links::add);
            JsonNode winner = links.stream()
                    .min(Comparator.comparingInt(link -> tierRank(link.path("tier").asText())))
                    .orElseThrow();

            List<String> winnerVisitorIds = textList(winner.path("poolVisitorIds"));
            List<Touchpoint> winnerPool = eligiblePool(
                    all.stream().filter(tp -> winnerVisitorIds.contains(tp.visitorId())).toList(),
                    anchor,
                    fixture.path("defaultWindowDays").asLong());

            boolean poolsDisjoint = links.size() > 1
                    && links.stream()
                            .filter(link -> link != winner)
                            .anyMatch(other -> textList(other.path("poolVisitorIds")).stream()
                                    .noneMatch(winnerVisitorIds::contains));

            assertThat(winner.path("tier").asText()).as(caseId).isEqualTo(testCase.path("expectedConfidence").asText());
            assertThat(winner.path("evidenceReference").asText()).as(caseId)
                    .isEqualTo(testCase.path("expectedEvidenceReference").asText());
            assertThat(idOrNull(selectFirstTouch(winnerPool))).as(caseId + " first-touch")
                    .isEqualTo(testCase.path("expectedFirstTouchId").asText(null));
            assertThat(idOrNull(selectLastTouch(winnerPool, anchor))).as(caseId + " last-touch")
                    .isEqualTo(testCase.path("expectedLastTouchId").asText(null));
            assertThat(poolsDisjoint).as(caseId + " conflict recorded")
                    .isEqualTo(testCase.path("expectedConflictRecorded").asBoolean());
        }

        assertThat(caseIds("conflictCases")).contains("conflicting-deterministic-evidence");
    }

    @Test
    void reactivationExpansionContractionAndChurnInheritAcquisitionEvidence() {
        for (JsonNode testCase : fixture.path("inheritanceCases")) {
            String caseId = testCase.path("id").asText();
            OffsetDateTime acquisitionAnchor = OffsetDateTime.parse(testCase.path("acquisitionAnchor").asText());
            List<Touchpoint> acquisitionPool = eligiblePool(
                    readTouchpoints(testCase.path("acquisitionTouchpoints")), acquisitionAnchor, fixture.path("defaultWindowDays").asLong());

            String acquisitionEvidenceReference = testCase.path("link").path("evidenceReference").asText();
            String acquisitionFirstTouch = idOrNull(selectFirstTouch(acquisitionPool));
            String acquisitionLastTouch = idOrNull(selectLastTouch(acquisitionPool, acquisitionAnchor));

            assertThat(acquisitionEvidenceReference).as(caseId).isEqualTo(testCase.path("expectedEvidenceReference").asText());
            assertThat(acquisitionFirstTouch).as(caseId).isEqualTo(testCase.path("expectedFirstTouchId").asText());
            assertThat(acquisitionLastTouch).as(caseId).isEqualTo(testCase.path("expectedLastTouchId").asText());

            for (JsonNode movement : testCase.path("dependentMovements")) {
                // Every dependent movement type (New's own siblings: Reactivation, Expansion,
                // Contraction, Churn) inherits the acquisition's evidence untouched -- no new window,
                // no new touchpoint search -- even when a later, unrelated touchpoint exists nearby.
                assertThat(acquisitionEvidenceReference).as(movement.path("id").asText())
                        .isEqualTo(testCase.path("expectedEvidenceReference").asText());
                assertThat(acquisitionFirstTouch).as(movement.path("id").asText())
                        .isEqualTo(testCase.path("expectedFirstTouchId").asText());
                assertThat(acquisitionLastTouch).as(movement.path("id").asText())
                        .isEqualTo(testCase.path("expectedLastTouchId").asText());

                JsonNode distractor = movement.path("distractorTouchpoint");
                if (!distractor.isMissingNode() && !distractor.isNull()) {
                    String distractorId = distractor.path("id").asText();
                    assertThat(distractorId).as(movement.path("id").asText() + " distractor never selected")
                            .isNotEqualTo(acquisitionFirstTouch)
                            .isNotEqualTo(acquisitionLastTouch);
                }
            }
        }

        assertThat(caseIds("inheritanceCases"))
                .contains(
                        "reactivation-inherits-original-acquisition-evidence",
                        "expansion-contraction-churn-inherit-acquisition-evidence");
    }

    @Test
    void recalculationUnderANewerModelVersionChangesEvidenceWithoutMutatingRawTouchpoints() {
        for (JsonNode testCase : fixture.path("recalculationCases")) {
            String caseId = testCase.path("id").asText();
            OffsetDateTime anchor = OffsetDateTime.parse(testCase.path("anchor").asText());
            List<Touchpoint> all = readTouchpoints(testCase.path("touchpoints"));

            List<String> observedConfidences = new ArrayList<>();
            for (JsonNode modelVersion : testCase.path("modelVersions")) {
                long windowDays = modelVersion.path("windowDays").asLong();
                List<Touchpoint> pool = eligiblePool(all, anchor, windowDays);
                String confidence = pool.isEmpty() ? "UNATTRIBUTED" : testCase.path("link").path("tier").asText();
                String evidenceReference =
                        "UNATTRIBUTED".equals(confidence) ? null : testCase.path("link").path("evidenceReference").asText();

                String label = caseId + "/" + modelVersion.path("version").asText();
                assertThat(confidence).as(label).isEqualTo(modelVersion.path("expectedConfidence").asText());
                assertThat(evidenceReference).as(label).isEqualTo(modelVersion.path("expectedEvidenceReference").asText(null));
                assertThat(idOrNull(selectFirstTouch(pool))).as(label + " first-touch")
                        .isEqualTo(modelVersion.path("expectedFirstTouchId").asText(null));
                assertThat(idOrNull(selectLastTouch(pool, anchor))).as(label + " last-touch")
                        .isEqualTo(modelVersion.path("expectedLastTouchId").asText(null));
                observedConfidences.add(confidence);
            }

            // The two model versions must actually disagree, or this fixture would not be proving
            // that recalculation changes the derived result while the raw touchpoint stays identical.
            assertThat(observedConfidences).as(caseId).doesNotHaveDuplicates();
        }

        assertThat(caseIds("recalculationCases")).contains("recalculation-under-newer-model-version");
    }

    // --- ADR-0005 pure selection rules ---

    private static boolean isDirect(Touchpoint touchpoint) {
        return isBlank(touchpoint.referrerUrl())
                && isBlank(touchpoint.utmSource())
                && isBlank(touchpoint.utmMedium())
                && isBlank(touchpoint.utmCampaign())
                && isBlank(touchpoint.utmTerm())
                && isBlank(touchpoint.utmContent());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static List<Touchpoint> eligiblePool(List<Touchpoint> touchpoints, OffsetDateTime anchor, long windowDays) {
        OffsetDateTime windowStart = anchor.minusDays(windowDays);
        return touchpoints.stream()
                .filter(tp -> !tp.occurredAt().isBefore(windowStart) && !tp.occurredAt().isAfter(anchor))
                .toList();
    }

    private static Optional<Touchpoint> selectFirstTouch(List<Touchpoint> pool) {
        return pool.stream().min(
                Comparator.comparing(Touchpoint::occurredAt)
                        .thenComparing(Touchpoint::createdAt)
                        .thenComparing(Touchpoint::id));
    }

    private static Optional<Touchpoint> selectLastTouch(List<Touchpoint> pool, OffsetDateTime anchor) {
        boolean anyNonDirect = pool.stream().anyMatch(tp -> !isDirect(tp));
        List<Touchpoint> candidates = anyNonDirect ? pool.stream().filter(tp -> !isDirect(tp)).toList() : pool;
        return candidates.stream()
                .filter(tp -> !tp.occurredAt().isAfter(anchor))
                .max(
                        Comparator.comparing(Touchpoint::occurredAt)
                                .thenComparing(Touchpoint::createdAt)
                                .thenComparing(Touchpoint::id));
    }

    private static int tierRank(String tier) {
        return switch (tier) {
            case "VERIFIED" -> 0;
            case "STRONG" -> 1;
            case "MODERATE" -> 2;
            default -> throw new AssertionError("unknown tier " + tier);
        };
    }

    // --- fixture plumbing ---

    private record Touchpoint(
            String id,
            OffsetDateTime occurredAt,
            OffsetDateTime createdAt,
            String visitorId,
            String referrerUrl,
            String utmSource,
            String utmMedium,
            String utmCampaign,
            String utmTerm,
            String utmContent) {}

    private static List<Touchpoint> readTouchpoints(JsonNode node) {
        List<Touchpoint> touchpoints = new ArrayList<>();
        node.forEach(tp -> touchpoints.add(new Touchpoint(
                tp.path("id").asText(),
                OffsetDateTime.parse(tp.path("occurredAt").asText()),
                OffsetDateTime.parse(tp.path("createdAt").asText()),
                tp.path("visitorId").asText(),
                nullableText(tp, "referrerUrl"),
                nullableText(tp, "utmSource"),
                nullableText(tp, "utmMedium"),
                nullableText(tp, "utmCampaign"),
                nullableText(tp, "utmTerm"),
                nullableText(tp, "utmContent"))));
        return touchpoints;
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static List<String> ids(List<Touchpoint> touchpoints) {
        return touchpoints.stream().map(Touchpoint::id).toList();
    }

    private static String idOrNull(Optional<Touchpoint> touchpoint) {
        return touchpoint.map(Touchpoint::id).orElse(null);
    }

    private static List<String> textList(JsonNode arrayNode) {
        List<String> values = new ArrayList<>();
        arrayNode.forEach(node -> values.add(node.asText()));
        return values;
    }

    private static Set<String> caseIds(String section) {
        Set<String> ids = new HashSet<>();
        fixture.path(section).forEach(testCase -> assertThat(ids.add(testCase.path("id").asText())).isTrue());
        return ids;
    }
}
