"use client";

import { useState } from "react";

import type {
  AttributionCoverage,
  MrrMovementType,
  ProjectDiagnosticsReport,
  RevenueOverview,
  StripeBillingHealthReport,
} from "@/lib/api/types";

import { DataHealthPanel } from "./DataHealthPanel";
import { MovementsDrilldown } from "./MovementsDrilldown";
import { RevenueSummary } from "./RevenueSummary";

interface OverviewClientProps {
  workspaceId: string;
  projectId: string;
  from: string;
  to: string;
  overview: RevenueOverview;
  /** Null when this signal couldn't be loaded -- rendered as its own degraded/failed state, never blocking the rest of the page. */
  coverage: AttributionCoverage | null;
  stripeHealth: StripeBillingHealthReport | null;
  diagnostics: ProjectDiagnosticsReport | null;
}

interface Selection {
  movementType: MrrMovementType | null;
  source: string | null;
  /** True when the Unattributed bucket was selected (distinct from "no source selected"). */
  sourceUnattributed: boolean;
  currency: string | null;
}

const EMPTY_SELECTION: Selection = {
  movementType: null,
  source: null,
  sourceUnattributed: false,
  currency: null,
};

export function OverviewClient({
  workspaceId,
  projectId,
  from,
  to,
  overview,
  coverage,
  stripeHealth,
  diagnostics,
}: OverviewClientProps) {
  const [selection, setSelection] = useState<Selection>(EMPTY_SELECTION);

  return (
    <div style={{ display: "grid", gap: 24 }}>
      <RevenueSummary
        overview={overview}
        selectedMovementType={selection.movementType}
        selectedSource={selection.source}
        selectedCurrency={selection.currency}
        onSelectMovementType={(type, currency) =>
          setSelection((current) =>
            current.movementType === type && current.currency === currency
              ? EMPTY_SELECTION
              : {
                  movementType: type,
                  source: null,
                  sourceUnattributed: false,
                  currency,
                },
          )
        }
        onSelectSource={(nextSource, currency) =>
          setSelection((current) =>
            current.source === nextSource && current.currency === currency
              ? EMPTY_SELECTION
              : {
                  movementType: null,
                  source: nextSource,
                  sourceUnattributed: nextSource === null,
                  currency,
                },
          )
        }
      />

      <DataHealthPanel
        workspaceId={workspaceId}
        projectId={projectId}
        coverage={coverage}
        stripeHealth={stripeHealth}
        diagnostics={diagnostics}
      />

      <MovementsDrilldown
        workspaceId={workspaceId}
        projectId={projectId}
        from={from}
        to={to}
        movementType={selection.movementType}
        source={selection.source}
        sourceUnattributed={selection.sourceUnattributed}
        currency={selection.currency}
        onClearFilters={() => setSelection(EMPTY_SELECTION)}
      />
    </div>
  );
}
