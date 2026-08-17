import { Panel } from "@/components/ui/Panel";
import { SkeletonBlock } from "@/components/ui/Skeleton";

export default function WeeklySummaryLoading() {
  return (
    <div style={{ display: "grid", gap: 24, maxWidth: 1040 }}>
      <SkeletonBlock label="Loading weekly summary" lines={2} />
      <Panel title="This week">
        <SkeletonBlock label="Loading weekly summary insights" lines={6} />
      </Panel>
    </div>
  );
}
