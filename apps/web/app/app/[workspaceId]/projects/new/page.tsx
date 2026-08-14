"use client";

import { useParams, useRouter } from "next/navigation";
import { useId, useState, type FormEvent } from "react";

import { Button } from "@/components/ui/Button";
import { Field } from "@/components/ui/Field";
import { Panel } from "@/components/ui/Panel";
import { createBrowserClient } from "@/lib/api/client";
import { ApiError } from "@/lib/api/errors";
import { createProject } from "@/lib/api/workspaces";

export default function NewProjectPage() {
  const router = useRouter();
  const params = useParams<{ workspaceId: string }>();
  const workspaceId = params.workspaceId;
  const [name, setName] = useState("");
  const [domain, setDomain] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const headingId = useId();

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      const project = await createProject(createBrowserClient(), workspaceId, {
        name: name.trim(),
        domain: domain.trim(),
      });
      router.push(`/app/${workspaceId}/projects/${project.id}`);
    } catch (submitError) {
      setError(
        submitError instanceof ApiError
          ? submitError.message
          : "Could not create the project. Check your connection and try again.",
      );
      setSubmitting(false);
    }
  }

  return (
    <div style={{ maxWidth: 480 }}>
      <Panel as="section" aria-labelledby={headingId}>
        <h1 id={headingId} style={{ margin: "0 0 6px", fontSize: "1.5rem" }}>
          Create a project
        </h1>
        <p
          style={{
            margin: "0 0 20px",
            color: "var(--ds-text-muted)",
            fontSize: "0.875rem",
          }}
        >
          Each project tracks one site or app end to end: installation,
          verification, and Stripe revenue.
        </p>
        <form onSubmit={handleSubmit} noValidate>
          <div style={{ display: "grid", gap: 16 }}>
            <Field
              label="Project name"
              name="name"
              required
              placeholder="Production"
              value={name}
              onChange={(event) => setName(event.target.value)}
            />
            <Field
              label="Primary domain"
              name="domain"
              required
              placeholder="app.example.com"
              hint="Your site's main domain. You can add more allowed domains after setup."
              value={domain}
              onChange={(event) => setDomain(event.target.value)}
            />
            {error ? (
              <p
                role="alert"
                style={{
                  margin: 0,
                  color: "var(--ds-danger)",
                  fontSize: "0.8125rem",
                }}
              >
                {error}
              </p>
            ) : null}
            <Button
              type="submit"
              variant="primary"
              loading={submitting}
              disabled={!name.trim() || !domain.trim()}
            >
              Create project
            </Button>
          </div>
        </form>
      </Panel>
    </div>
  );
}
