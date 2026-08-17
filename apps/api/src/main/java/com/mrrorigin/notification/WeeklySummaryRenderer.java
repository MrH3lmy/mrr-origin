package com.mrrorigin.notification;

import java.util.List;

import com.mrrorigin.notification.WeeklySummaryService.CurrencySection;
import com.mrrorigin.notification.WeeklySummaryService.Insight;
import com.mrrorigin.notification.WeeklySummaryService.WeeklySummaryResponse;

/**
 * Pure text/HTML rendering of {@link WeeklySummaryResponse}, per #26's contract: one section per
 * currency, one line per insight except {@code STABLE} ones (rolled up into a single trailing
 * count), never "anomaly" language, and flat factual phrasing (no comparative language) for {@code
 * INSUFFICIENT_SAMPLE} insights. Every rendered {@code STABLE}-exclusion still leaves the full
 * insight set in the DTO/JSON for reconciliation -- this class only affects the rendered narrative.
 */
final class WeeklySummaryRenderer {
    private WeeklySummaryRenderer() {}

    static String renderText(WeeklySummaryResponse summary) {
        StringBuilder text = new StringBuilder();
        text.append("Weekly summary: ")
                .append(summary.weekStart())
                .append(" to ")
                .append(summary.weekEnd())
                .append(" (")
                .append(summary.timezone())
                .append(")\n");
        for (CurrencySection section : summary.currencySections()) {
            text.append("\n").append(section.currency()).append(":\n");
            List<Insight> actionable = actionable(section);
            for (Insight insight : actionable) {
                text.append("  - ").append(line(insight)).append("\n");
            }
            long stableCount = section.insights().size() - actionable.size();
            if (stableCount > 0) {
                text.append("  ").append(stableCount).append(" other comparison signals were stable this week.\n");
            }
        }
        return text.toString();
    }

    static String renderHtml(WeeklySummaryResponse summary) {
        StringBuilder html = new StringBuilder("<div>");
        html.append("<h2>Weekly summary: ")
                .append(esc(summary.weekStart().toString()))
                .append(" to ")
                .append(esc(summary.weekEnd().toString()))
                .append(" (")
                .append(esc(summary.timezone()))
                .append(")</h2>");
        for (CurrencySection section : summary.currencySections()) {
            html.append("<section><h3>").append(esc(section.currency())).append("</h3><ul>");
            List<Insight> actionable = actionable(section);
            for (Insight insight : actionable) {
                html.append("<li>")
                        .append(esc(line(insight)))
                        .append(" <a href=\"").append(esc(insight.currentEvidenceLink())).append("\">this week</a>")
                        .append(" / <a href=\"").append(esc(insight.priorEvidenceLink())).append("\">prior week</a>")
                        .append("</li>");
            }
            html.append("</ul>");
            long stableCount = section.insights().size() - actionable.size();
            if (stableCount > 0) {
                html.append("<p>").append(stableCount).append(" other comparison signals were stable this week.</p>");
            }
            html.append("</section>");
        }
        html.append("</div>");
        return html.toString();
    }

    private static List<Insight> actionable(CurrencySection section) {
        return section.insights().stream().filter(i -> !"STABLE".equals(i.status())).toList();
    }

    private static String line(Insight insight) {
        String label = label(insight);
        String movement = "NEW".equals(insight.movementType()) ? "New MRR" : "Churned MRR";
        return switch (insight.status()) {
            case "NEWLY_APPEARED" -> movement + " from " + label + " newly appeared this week: "
                    + insight.currentAmountMinor() + " minor units across " + insight.currentCustomerCount()
                    + " customers.";
            case "DISAPPEARED" -> movement + " from " + label + " disappeared this week (was "
                    + insight.priorAmountMinor() + " minor units across " + insight.priorCustomerCount()
                    + " customers).";
            case "MATERIAL_CHANGE" -> movement + " from " + label + " changed "
                    + Math.round(insight.percentageChange() * 100) + "% to " + insight.currentAmountMinor()
                    + " minor units across " + insight.currentCustomerCount() + " customers.";
            case "INSUFFICIENT_SAMPLE" -> movement + " from " + label + ": " + insight.currentAmountMinor()
                    + " minor units across " + insight.currentCustomerCount()
                    + " customers -- too few to compare week over week.";
            default -> movement + " from " + label + " was stable this week.";
        };
    }

    private static String label(Insight insight) {
        if (insight.dimensionValue() != null) {
            return insight.dimensionValue();
        }
        return "NONE".equals(insight.dimensionBucket()) ? "no value captured" : "Unattributed";
    }

    private static String esc(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
