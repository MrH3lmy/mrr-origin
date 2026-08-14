import { Panel } from "@/components/ui/Panel";
import { SkeletonBlock } from "@/components/ui/Skeleton";

export default function ProjectLoading() {
  return (
    <div style={{ display: "grid", gap: 24, maxWidth: 880 }}>
      <SkeletonBlock label="Loading project status" lines={2} />
      <Panel title="Install the tracker">
        <SkeletonBlock label="Loading tracker installation" lines={4} />
      </Panel>
      <Panel title="Verify tracking">
        <SkeletonBlock label="Loading verification status" lines={3} />
      </Panel>
      <Panel title="Connect Stripe">
        <SkeletonBlock label="Loading Stripe status" lines={3} />
      </Panel>
    </div>
  );
}
