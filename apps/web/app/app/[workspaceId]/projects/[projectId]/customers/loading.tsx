import { Panel } from "@/components/ui/Panel";
import { SkeletonBlock } from "@/components/ui/Skeleton";

export default function CustomersLoading() {
  return (
    <div style={{ display: "grid", gap: 24, maxWidth: 1040 }}>
      <SkeletonBlock label="Loading customers" lines={2} />
      <Panel title="Customers">
        <SkeletonBlock label="Loading customer list" lines={6} />
      </Panel>
    </div>
  );
}
