"use client";

import { useRouter } from "next/navigation";
import { useId, useState, type FormEvent } from "react";

import { Button } from "@/components/ui/Button";
import { Field } from "@/components/ui/Field";
import { Panel } from "@/components/ui/Panel";
import { createBrowserClient } from "@/lib/api/client";
import { ApiError } from "@/lib/api/errors";
import { createWorkspace } from "@/lib/api/workspaces";

function slugify(value: string): string {
  return value
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");
}

export default function NewWorkspacePage() {
  const router = useRouter();
  const [name, setName] = useState("");
  const [slug, setSlug] = useState("");
  const [slugTouched, setSlugTouched] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const headingId = useId();

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      const workspace = await createWorkspace(createBrowserClient(), {
        name: name.trim(),
        slug: slug.trim() || slugify(name),
      });
      router.push(`/app/${workspace.id}`);
    } catch (submitError) {
      setError(
        submitError instanceof ApiError
          ? submitError.message
          : "Could not create the workspace. Check your connection and try again.",
      );
      setSubmitting(false);
    }
  }

  return (
    <div style={{ maxWidth: 480 }}>
      <Panel as="section" aria-labelledby={headingId}>
        <h1 id={headingId} style={{ margin: "0 0 6px", fontSize: "1.5rem" }}>
          Create a workspace
        </h1>
        <p
          style={{
            margin: "0 0 20px",
            color: "var(--ds-text-muted)",
            fontSize: "0.875rem",
          }}
        >
          A workspace holds your projects, Stripe connection, and team.
        </p>
        <form onSubmit={handleSubmit} noValidate>
          <div style={{ display: "grid", gap: 16 }}>
            <Field
              label="Workspace name"
              name="name"
              required
              value={name}
              onChange={(event) => {
                setName(event.target.value);
                if (!slugTouched) setSlug(slugify(event.target.value));
              }}
            />
            <Field
              label="Slug"
              name="slug"
              hint="Used in URLs. Lowercase letters, numbers, and hyphens."
              required
              pattern="^[a-z0-9]+(-[a-z0-9]+)*$"
              value={slug}
              onChange={(event) => {
                setSlugTouched(true);
                setSlug(event.target.value);
              }}
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
              disabled={!name.trim() || !slug.trim()}
            >
              Create workspace
            </Button>
          </div>
        </form>
      </Panel>
    </div>
  );
}
