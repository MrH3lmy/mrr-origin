import { Panel } from "@/components/ui/Panel";
import { SkeletonBlock } from "@/components/ui/Skeleton";

export default function CustomerDetailLoading() {
  return (
    <div style={{ display: "grid", gap: 24, maxWidth: 1040 }}>
      <SkeletonBlock label="Loading customer" lines={2} />
      <Panel title="Current MRR and subscription status">
        <SkeletonBlock label="Loading customer detail" lines={4} />
      </Panel>
      <Panel title="Evidence timeline">
        <SkeletonBlock label="Loading evidence timeline" lines={6} />
      </Panel>
    </div>
  );
}
