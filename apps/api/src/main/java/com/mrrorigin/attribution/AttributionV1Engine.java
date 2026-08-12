package com.mrrorigin.attribution;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** The production implementation of ADR-0005's versioned selection policy. */
public final class AttributionV1Engine {
    public static final String MODEL_VERSION = "attribution-v1";
    public static final long WINDOW_DAYS = 90;

    public Selection select(OffsetDateTime acquisitionAt, List<Touchpoint> candidates) {
        OffsetDateTime beginning = acquisitionAt.minusDays(WINDOW_DAYS);
        List<Touchpoint> eligible = candidates.stream()
                .filter(t -> !t.occurredAt().isBefore(beginning) && !t.occurredAt().isAfter(acquisitionAt))
                .toList();
        if (eligible.isEmpty()) return new Selection(List.of(), null, null);
        Comparator<Touchpoint> order = Comparator.comparing(Touchpoint::occurredAt)
                .thenComparing(Touchpoint::createdAt).thenComparing(Touchpoint::id);
        Touchpoint first = eligible.stream().min(order).orElseThrow();
        Optional<Touchpoint> lastNonDirect = eligible.stream().filter(t -> !t.direct()).max(order);
        Touchpoint last = lastNonDirect.orElseGet(() -> eligible.stream().max(order).orElseThrow());
        return new Selection(eligible, first, last);
    }

    public record Touchpoint(String id, OffsetDateTime occurredAt, OffsetDateTime createdAt,
            String source, String campaign, String landingPage, String referrer,
            String medium, String term, String content) {
        public boolean direct() {
            return blank(referrer) && blank(source) && blank(medium) && blank(campaign)
                    && blank(term) && blank(content);
        }
        private static boolean blank(String value) { return value == null || value.isBlank(); }
    }

    public record Selection(List<Touchpoint> eligible, Touchpoint first, Touchpoint last) {}
}
