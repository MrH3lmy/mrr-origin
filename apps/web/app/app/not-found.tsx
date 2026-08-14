import { ButtonLink } from "@/components/ui/Button";
import { EmptyState } from "@/components/ui/StateMessage";

import "./app-shell.css";

export default function AppNotFound() {
  return (
    <div
      className="app-shell"
      style={{
        display: "grid",
        placeItems: "center",
        minHeight: "100dvh",
        padding: 24,
      }}
    >
      <div style={{ width: "min(480px, 100%)" }}>
        <EmptyState
          title="We couldn't find that"
          description="The workspace or project may not exist, or you may not have access to it."
        >
          <ButtonLink variant="primary" href="/app">
            Back to MRROrigin
          </ButtonLink>
        </EmptyState>
      </div>
    </div>
  );
}
