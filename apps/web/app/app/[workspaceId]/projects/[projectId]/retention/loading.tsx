import { Panel } from "@/components/ui/Panel";
import { SkeletonBlock } from "@/components/ui/Skeleton";

export default function RetentionLoading() {
  return (
    <div style={{ display: "grid", gap: 24, maxWidth: 1040 }}>
      <SkeletonBlock label="Loading retention" lines={2} />
      <Panel title="Retained-MRR cohorts">
        <SkeletonBlock label="Loading retention cohorts" lines={6} />
      </Panel>
      <Panel title="Attribution coverage">
        <SkeletonBlock label="Loading attribution coverage" lines={3} />
      </Panel>
    </div>
  );
}
