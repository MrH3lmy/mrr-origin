"use client";

import { useState, type FormEvent } from "react";

import { Alert } from "@/components/ui/Alert";
import { Button } from "@/components/ui/Button";
import { CopyButton } from "@/components/ui/CopyButton";
import { Panel } from "@/components/ui/Panel";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { createBrowserClient } from "@/lib/api/client";
import { ApiError } from "@/lib/api/errors";
import {
  addAllowedDomain,
  getActiveIngestionKey,
  issueOrRotateIngestionKey,
  listAllowedDomains,
  removeAllowedDomain,
} from "@/lib/api/tracking";
import type {
  ActiveIngestionKey,
  AllowedDomain,
  Project,
} from "@/lib/api/types";

import styles from "./TrackerInstallCard.module.css";

interface TrackerInstallCardProps {
  workspaceId: string;
  projectId: string;
  project: Project;
  activeKey: ActiveIngestionKey;
  onActiveKeyChange: (key: ActiveIngestionKey) => void;
  initialDomains: AllowedDomain[];
}

function buildSnippet(keyValue: string): string {
  return `import { createTracker } from "@mrr-origin/tracker";

const tracker = createTracker({ publicKey: "${keyValue}" });
tracker.page();

// Send queued events using your ingestion key.
async function flush() {
  const events = tracker.drain();
  if (events.length === 0) return;
  await fetch("<YOUR_MRRORIGIN_API_ORIGIN>/api/public/v1/events", {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-Ingestion-Key": "${keyValue}" },
    body: JSON.stringify({ version: 1, batchId: crypto.randomUUID(), events }),
  });
}
setInterval(flush, 5000);`;
}

export function TrackerInstallCard({
  workspaceId,
  projectId,
  project,
  activeKey: key,
  onActiveKeyChange,
  initialDomains,
}: TrackerInstallCardProps) {
  const [issuedSecret, setIssuedSecret] = useState<string | null>(null);
  const [issuing, setIssuing] = useState(false);
  const [confirmingRotate, setConfirmingRotate] = useState(false);
  const [keyError, setKeyError] = useState<string | null>(null);

  const [domains, setDomains] = useState(initialDomains);
  const [domainInput, setDomainInput] = useState("");
  const [addingDomain, setAddingDomain] = useState(false);
  const [domainError, setDomainError] = useState<string | null>(null);
  const [removingId, setRemovingId] = useState<string | null>(null);

  async function handleIssueOrRotate() {
    setIssuing(true);
    setKeyError(null);
    try {
      const client = createBrowserClient();
      const issued = await issueOrRotateIngestionKey(
        client,
        workspaceId,
        projectId,
      );
      setIssuedSecret(issued.secret);
      setConfirmingRotate(false);
      onActiveKeyChange(
        await getActiveIngestionKey(client, workspaceId, projectId),
      );
    } catch (error) {
      setKeyError(
        error instanceof ApiError
          ? error.message
          : "Could not generate a key. Try again.",
      );
    } finally {
      setIssuing(false);
    }
  }

  async function handleAddDomain(event: FormEvent) {
    event.preventDefault();
    if (!domainInput.trim() || addingDomain) return;
    setAddingDomain(true);
    setDomainError(null);
    try {
      const client = createBrowserClient();
      await addAllowedDomain(
        client,
        workspaceId,
        projectId,
        domainInput.trim(),
      );
      setDomains(await listAllowedDomains(client, workspaceId, projectId));
      setDomainInput("");
    } catch (error) {
      setDomainError(
        error instanceof ApiError
          ? error.message
          : "Could not add that domain.",
      );
    } finally {
      setAddingDomain(false);
    }
  }

  async function handleRemoveDomain(domainId: string) {
    setRemovingId(domainId);
    setDomainError(null);
    try {
      const client = createBrowserClient();
      await removeAllowedDomain(client, workspaceId, projectId, domainId);
      setDomains((current) =>
        current.filter((domain) => domain.id !== domainId),
      );
    } catch (error) {
      setDomainError(
        error instanceof ApiError
          ? error.message
          : "Could not remove that domain.",
      );
    } finally {
      setRemovingId(null);
    }
  }

  const snippetKey =
    issuedSecret ?? "<your ingestion key — generate one below>";

  return (
    <Panel
      title="Install the tracker"
      subtitle={`For ${project.name} (${project.domain})`}
      actions={
        key.present ? (
          <StatusBadge tone="positive">Key active</StatusBadge>
        ) : (
          <StatusBadge tone="neutral">Not installed</StatusBadge>
        )
      }
    >
      <div style={{ display: "grid", gap: 20 }}>
        <div>
          {!key.present ? (
            <Button
              variant="primary"
              onClick={handleIssueOrRotate}
              loading={issuing}
            >
              Generate installation key
            </Button>
          ) : (
            <div
              style={{
                display: "flex",
                flexWrap: "wrap",
                alignItems: "center",
                gap: 10,
              }}
            >
              <span
                style={{
                  fontFamily: "ui-monospace, monospace",
                  fontSize: "0.8125rem",
                  color: "var(--ds-text-muted)",
                }}
              >
                Active key prefix: {key.prefix}
              </span>
              {!confirmingRotate ? (
                <Button
                  variant="secondary"
                  size="small"
                  onClick={() => setConfirmingRotate(true)}
                >
                  Rotate key
                </Button>
              ) : (
                <span
                  style={{
                    display: "inline-flex",
                    gap: 8,
                    alignItems: "center",
                  }}
                >
                  <span
                    style={{
                      fontSize: "0.8125rem",
                      color: "var(--ds-warning)",
                    }}
                  >
                    Rotating immediately invalidates the current key.
                  </span>
                  <Button
                    variant="destructive"
                    size="small"
                    onClick={handleIssueOrRotate}
                    loading={issuing}
                  >
                    Confirm rotate
                  </Button>
                  <Button
                    variant="ghost"
                    size="small"
                    onClick={() => setConfirmingRotate(false)}
                  >
                    Cancel
                  </Button>
                </span>
              )}
            </div>
          )}
          {keyError ? (
            <p
              role="alert"
              style={{
                color: "var(--ds-danger)",
                fontSize: "0.8125rem",
                marginTop: 8,
              }}
            >
              {keyError}
            </p>
          ) : null}
        </div>

        {issuedSecret ? (
          <Alert
            tone="warning"
            title="Copy this key now"
            detail="For security, the full key is only ever shown once, right after it's created or rotated."
          >
            <CopyButton value={issuedSecret} label="Copy key" />
          </Alert>
        ) : null}

        <div className={styles.snippetSection}>
          <p
            style={{
              margin: "0 0 8px",
              fontSize: "0.8125rem",
              fontWeight: 600,
            }}
          >
            Installation snippet
          </p>
          <pre className={styles.snippet}>
            <code>{buildSnippet(snippetKey)}</code>
          </pre>
          <div style={{ marginTop: 8 }}>
            <CopyButton value={buildSnippet(snippetKey)} label="Copy snippet" />
          </div>
        </div>

        <div>
          <p
            style={{
              margin: "0 0 8px",
              fontSize: "0.8125rem",
              fontWeight: 600,
            }}
          >
            Allowed domains
          </p>
          <p
            style={{
              margin: "0 0 10px",
              fontSize: "0.8125rem",
              color: "var(--ds-text-muted)",
            }}
          >
            Events are only accepted from domains listed here. Add the exact
            domain your site runs on.
          </p>
          {domains.length === 0 ? (
            <p
              style={{
                margin: "0 0 10px",
                fontSize: "0.8125rem",
                color: "var(--ds-warning)",
              }}
            >
              No allowed domains yet — events from any domain will be blocked
              until you add one.
            </p>
          ) : (
            <ul
              style={{
                listStyle: "none",
                margin: "0 0 10px",
                padding: 0,
                display: "grid",
                gap: 6,
              }}
            >
              {domains.map((domain) => (
                <li key={domain.id} className={styles.domainRow}>
                  <span style={{ fontFamily: "ui-monospace, monospace" }}>
                    {domain.domain}
                  </span>
                  <Button
                    variant="ghost"
                    size="small"
                    onClick={() => handleRemoveDomain(domain.id)}
                    loading={removingId === domain.id}
                    aria-label={`Remove ${domain.domain} from allowed domains`}
                  >
                    Remove
                  </Button>
                </li>
              ))}
            </ul>
          )}
          <form
            onSubmit={handleAddDomain}
            style={{ display: "flex", gap: 8, flexWrap: "wrap" }}
          >
            <label
              htmlFor="add-domain"
              style={{
                position: "absolute",
                width: 1,
                height: 1,
                overflow: "hidden",
                clip: "rect(0 0 0 0)",
              }}
            >
              Domain to allow
            </label>
            <input
              id="add-domain"
              className={styles.domainInput}
              value={domainInput}
              onChange={(event) => setDomainInput(event.target.value)}
              placeholder="app.example.com"
            />
            <Button
              type="submit"
              variant="secondary"
              size="small"
              loading={addingDomain}
              disabled={!domainInput.trim()}
            >
              Add domain
            </Button>
          </form>
          {domainError ? (
            <p
              role="alert"
              style={{
                color: "var(--ds-danger)",
                fontSize: "0.8125rem",
                marginTop: 8,
              }}
            >
              {domainError}
            </p>
          ) : null}
        </div>
      </div>
    </Panel>
  );
}
