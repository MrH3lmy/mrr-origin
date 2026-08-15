import { Alert } from "@/components/ui/Alert";
import { Panel } from "@/components/ui/Panel";
import { StatusBadge } from "@/components/ui/StatusBadge";
import type {
  CurrentMrrTotal,
  MrrMovementTotal,
  MrrMovementType,
  RevenueOverview,
  SourceHighlight,
} from "@/lib/api/types";
import { formatMoneyMinor } from "@/lib/format-currency";
import { MOVEMENT_TYPE_LABEL } from "@/lib/status-copy";

import styles from "./Overview.module.css";

const MOVEMENT_ORDER: MrrMovementType[] = [
  "NEW",
  "EXPANSION",
  "CONTRACTION",
  "CHURN",
  "REACTIVATION",
];

const NEGATIVE_MOVEMENT_TYPES = new Set<MrrMovementType>([
  "CONTRACTION",
  "CHURN",
]);

interface CurrencyBucket {
  currency: string;
  currentMrr: CurrentMrrTotal | null;
  movements: Map<MrrMovementType, MrrMovementTotal>;
  sourceHighlights: SourceHighlight[];
}

function groupByCurrency(overview: RevenueOverview): CurrencyBucket[] {
  const currencies = new Set<string>();
  overview.movementTotals.forEach((m) => currencies.add(m.currency));
  overview.currentMrr.forEach((m) => currencies.add(m.currency));
  overview.sourceHighlights.forEach((m) => currencies.add(m.currency));

  return Array.from(currencies)
    .sort()
    .map((currency) => ({
      currency,
      currentMrr:
        overview.currentMrr.find((m) => m.currency === currency) ?? null,
      movements: new Map(
        overview.movementTotals
          .filter((m) => m.currency === currency)
          .map((m) => [m.movementType, m]),
      ),
      sourceHighlights: overview.sourceHighlights
        .filter((m) => m.currency === currency)
        .sort((a, b) => b.totalMinor - a.totalMinor),
    }));
}

interface RevenueSummaryProps {
  overview: RevenueOverview;
  selectedMovementType: MrrMovementType | null;
  selectedSource: string | null | undefined;
  selectedCurrency: string | null;
  onSelectMovementType: (type: MrrMovementType, currency: string) => void;
  onSelectSource: (source: string | null, currency: string) => void;
}

export function RevenueSummary({
  overview,
  selectedMovementType,
  selectedSource,
  selectedCurrency,
  onSelectMovementType,
  onSelectSource,
}: RevenueSummaryProps) {
  const buckets = groupByCurrency(overview);

  if (buckets.length === 0) {
    return (
      <Panel
        title="MRR movement"
        subtitle="New, expansion, contraction, churn, and reactivation MRR."
      >
        <Alert
          tone="neutral"
          title="No MRR movement in this period yet."
          detail="Once Stripe subscriptions change during the selected period, movements will appear here."
        />
      </Panel>
    );
  }

  return (
    <Panel
      title="MRR movement"
      subtitle="New, expansion, contraction, churn, and reactivation MRR."
    >
      {buckets.map((bucket, index) => {
        const maxAbs = Math.max(
          1,
          ...MOVEMENT_ORDER.map(
            (type) => bucket.movements.get(type)?.totalMinor ?? 0,
          ),
        );
        return (
          <div
            key={bucket.currency}
            className={index > 0 ? styles.currencyGroup : undefined}
          >
            {buckets.length > 1 ? (
              <p className={styles.currencyGroupLabel}>{bucket.currency}</p>
            ) : null}

            <div className={styles.heroGrid} style={{ marginBottom: 20 }}>
              <div className={styles.heroMetric}>
                <p className={styles.heroLabel}>Current MRR</p>
                <p className={styles.heroValue}>
                  {formatMoneyMinor(
                    bucket.currentMrr?.totalMinor ?? 0,
                    bucket.currency,
                  )}
                </p>
                <p className={styles.heroSubtext}>
                  {bucket.currentMrr?.customerCount ?? 0} paying customer
                  {bucket.currentMrr?.customerCount === 1 ? "" : "s"}
                </p>
              </div>
              <div className={styles.heroMetric}>
                <p className={styles.heroLabel}>New MRR</p>
                <p className={styles.heroValue}>
                  {formatMoneyMinor(
                    bucket.movements.get("NEW")?.totalMinor ?? 0,
                    bucket.currency,
                  )}
                </p>
                <p className={styles.heroSubtext}>this period</p>
              </div>
              <div className={styles.heroMetric}>
                <p className={styles.heroLabel}>Churned MRR</p>
                <p className={styles.heroValue}>
                  {formatMoneyMinor(
                    bucket.movements.get("CHURN")?.totalMinor ?? 0,
                    bucket.currency,
                  )}
                </p>
                <p className={styles.heroSubtext}>this period</p>
              </div>
            </div>

            <ul className={styles.meterList}>
              {MOVEMENT_ORDER.map((type) => {
                const total = bucket.movements.get(type);
                const amount = total?.totalMinor ?? 0;
                const widthPercent =
                  maxAbs === 0 ? 0 : Math.round((amount / maxAbs) * 100);
                const isSelected =
                  selectedMovementType === type &&
                  selectedCurrency === bucket.currency;
                return (
                  <li key={type}>
                    <button
                      type="button"
                      className={`${styles.meterRow} ${amount === 0 ? styles.meterRowDisabled : ""}`}
                      aria-pressed={isSelected}
                      disabled={amount === 0}
                      onClick={() =>
                        onSelectMovementType(type, bucket.currency)
                      }
                    >
                      <span className={styles.meterLabel}>
                        {MOVEMENT_TYPE_LABEL[type]}
                      </span>
                      <span className={styles.meterTrack}>
                        <span
                          className={`${styles.meterFill} ${
                            NEGATIVE_MOVEMENT_TYPES.has(type)
                              ? styles.meterFillNegative
                              : styles.meterFillPositive
                          }`}
                          style={{ width: `${widthPercent}%` }}
                        />
                      </span>
                      <span className={styles.meterValue}>
                        {formatMoneyMinor(amount, bucket.currency)}
                        {total ? ` (${total.movementCount})` : ""}
                      </span>
                    </button>
                  </li>
                );
              })}
              <li>
                <div
                  className={`${styles.meterRow} ${styles.meterRowDisabled}`}
                >
                  <span className={styles.meterLabel}>Retained MRR</span>
                  <span className={styles.heroSubtext}>
                    Not available yet — 30/60/90-day retention cohorts ship with
                    the Retention screen.
                  </span>
                </div>
              </li>
            </ul>

            {bucket.sourceHighlights.length > 0 ? (
              <div style={{ marginTop: 24 }}>
                <p className={styles.currencyGroupLabel}>
                  Where New MRR came from
                </p>
                <ul className={styles.meterList}>
                  {bucket.sourceHighlights.map((highlight) => {
                    const isSelected =
                      selectedSource === (highlight.source ?? "UNATTRIBUTED") &&
                      selectedCurrency === bucket.currency;
                    const maxSource = Math.max(
                      1,
                      ...bucket.sourceHighlights.map((h) => h.totalMinor),
                    );
                    const widthPercent = Math.round(
                      (highlight.totalMinor / maxSource) * 100,
                    );
                    return (
                      <li key={highlight.source ?? "unattributed"}>
                        <button
                          type="button"
                          className={styles.meterRow}
                          aria-pressed={isSelected}
                          onClick={() =>
                            onSelectSource(
                              highlight.source ?? "UNATTRIBUTED",
                              bucket.currency,
                            )
                          }
                        >
                          <span className={styles.meterLabel}>
                            {highlight.source ?? (
                              <StatusBadge tone="neutral">
                                Unattributed
                              </StatusBadge>
                            )}
                          </span>
                          <span className={styles.meterTrack}>
                            <span
                              className={`${styles.meterFill} ${
                                highlight.source
                                  ? styles.meterFillPositive
                                  : styles.meterFillNeutral
                              }`}
                              style={{ width: `${widthPercent}%` }}
                            />
                          </span>
                          <span className={styles.meterValue}>
                            {formatMoneyMinor(
                              highlight.totalMinor,
                              bucket.currency,
                            )}{" "}
                            ({highlight.customerCount})
                          </span>
                        </button>
                      </li>
                    );
                  })}
                </ul>
                <p className={styles.heroSubtext} style={{ marginTop: 8 }}>
                  Ranked by New MRR this period. Which sources retain best ships
                  with Sources and Retention.
                </p>
              </div>
            ) : null}
          </div>
        );
      })}
    </Panel>
  );
}
