"use client";

import { useState } from "react";

import { Button } from "./Button";

interface CopyButtonProps {
  value: string;
  label?: string;
}

export function CopyButton({ value, label = "Copy" }: CopyButtonProps) {
  const [copied, setCopied] = useState(false);
  const [failed, setFailed] = useState(false);

  async function handleCopy() {
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(value);
      } else {
        throw new Error("Clipboard API unavailable");
      }
      setFailed(false);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      setFailed(true);
      setCopied(false);
    }
  }

  return (
    <span style={{ display: "inline-flex", alignItems: "center", gap: 8 }}>
      <Button
        type="button"
        variant="secondary"
        size="small"
        onClick={handleCopy}
      >
        {copied ? "Copied" : label}
      </Button>
      <span
        aria-live="polite"
        style={{
          position: "absolute",
          width: 1,
          height: 1,
          overflow: "hidden",
          clip: "rect(0 0 0 0)",
        }}
      >
        {copied ? "Copied to clipboard" : ""}
        {failed
          ? "Could not copy automatically. Select and copy the text manually."
          : ""}
      </span>
      {failed ? (
        <span style={{ color: "var(--ds-danger)", fontSize: "0.75rem" }}>
          Copy failed — select the text manually
        </span>
      ) : null}
    </span>
  );
}
