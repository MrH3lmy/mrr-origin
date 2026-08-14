import { Panel } from "@/components/ui/Panel";
import { SkeletonBlock } from "@/components/ui/Skeleton";

export default function OverviewLoading() {
  return (
    <div style={{ display: "grid", gap: 24, maxWidth: 1040 }}>
      <SkeletonBlock label="Loading overview" lines={2} />
      <Panel title="MRR movement">
        <SkeletonBlock label="Loading MRR movement" lines={6} />
      </Panel>
      <Panel title="Data health">
        <SkeletonBlock label="Loading data health" lines={3} />
      </Panel>
      <Panel title="Movement evidence">
        <SkeletonBlock label="Loading movement evidence" lines={4} />
      </Panel>
    </div>
  );
}
