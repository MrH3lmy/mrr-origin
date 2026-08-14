import { SkeletonBlock } from "@/components/ui/Skeleton";

export default function AppLoading() {
  return (
    <div style={{ maxWidth: 880 }}>
      <SkeletonBlock label="Loading MRROrigin" lines={4} />
    </div>
  );
}
