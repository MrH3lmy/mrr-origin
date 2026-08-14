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
  coverage: AttributionCoverage;
  stripeHealth: StripeBillingHealthReport;
  diagnostics: ProjectDiagnosticsReport;
}

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
  const [movementType, setMovementType] = useState<MrrMovementType | null>(
    null,
  );
  const [source, setSource] = useState<string | null>(null);

  return (
    <div style={{ display: "grid", gap: 24 }}>
      <RevenueSummary
        overview={overview}
        selectedMovementType={movementType}
        selectedSource={source}
        onSelectMovementType={(type) =>
          setMovementType((current) => (current === type ? null : type))
        }
        onSelectSource={(nextSource) =>
          setSource((current) => (current === nextSource ? null : nextSource))
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
        movementType={movementType}
        source={source}
        onClearFilters={() => {
          setMovementType(null);
          setSource(null);
        }}
      />
    </div>
  );
}
