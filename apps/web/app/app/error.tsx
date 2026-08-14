"use client";

import { useEffect } from "react";

import { Button } from "@/components/ui/Button";
import { ErrorState } from "@/components/ui/StateMessage";

import "./app-shell.css";

export default function AppError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    console.error(error);
  }, [error]);

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
        <ErrorState
          title="Something went wrong loading MRROrigin"
          description="This could be a temporary API issue. Try again, or sign in again if the problem continues."
        >
          <Button variant="primary" onClick={reset}>
            Try again
          </Button>
          <form method="POST" action="/auth/sign-out">
            <Button type="submit" variant="secondary">
              Sign in again
            </Button>
          </form>
        </ErrorState>
      </div>
    </div>
  );
}
