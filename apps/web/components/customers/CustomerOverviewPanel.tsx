"use client";

import { useState, type FormEvent } from "react";

import { Alert } from "@/components/ui/Alert";
import { Button } from "@/components/ui/Button";
import { Field } from "@/components/ui/Field";
import { Panel } from "@/components/ui/Panel";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { createBrowserClient } from "@/lib/api/client";
import { getCustomerTimeline, repairCustomerLink } from "@/lib/api/customers";
import { ApiError } from "@/lib/api/errors";
import type { CustomerDetail, CustomerTimeline } from "@/lib/api/types";
import { formatDateTime } from "@/lib/format";
import { formatMoneyMinor } from "@/lib/format-currency";
import {
  ACQUISITION_STATUS_COPY,
  ATTRIBUTION_EXCLUSION_REASON_COPY,
  SUBSCRIPTION_STATUS_LABEL,
  SUBSCRIPTION_STATUS_TONE,
} from "@/lib/status-copy";

import styles from "./Customers.module.css";

interface CustomerOverviewPanelProps {
  workspaceId: string;
  projectId: string;
  detail: CustomerDetail;
  onRepaired: (timeline: CustomerTimeline) => void;
}

export function CustomerOverviewPanel({
  workspaceId,
  projectId,
  detail,
  onRepaired,
}: CustomerOverviewPanelProps) {
  const { acquisition } = detail;
  const statusCopy = ACQUISITION_STATUS_COPY[acquisition.status];

  return (
    <Panel
      title="Current MRR and subscription status"
      subtitle={detail.deleted ? "This Stripe customer has been deleted." : undefined}
    >
      <div className={styles.metricGrid}>
        <div>
          <p className={styles.metricLabel}>Current MRR</p>
          {detail.currentMrr.length === 0 ? (
            <p className={styles.metricValue}>—</p>
          ) : (
            detail.currentMrr.map((mrr) => (
              <p className={styles.metricValue} key={mrr.currency}>
                {formatMoneyMinor(mrr.amountMinor, mrr.currency)}
              </p>
            ))
          )}
        </div>
        <div>
          <p className={styles.metricLabel}>Acquisition</p>
          <StatusBadge tone={statusCopy.tone}>{statusCopy.label}</StatusBadge>
          <p className={styles.metricSubtext}>{statusCopy.headline}</p>
        </div>
        <div>
          <p className={styles.metricLabel}>Model version</p>
          <p className={styles.metricValue} style={{ fontSize: "1rem" }}>
            {acquisition.modelVersion}
          </p>
          {acquisition.calculatedAt ? (
            <p className={styles.metricSubtext}>
              Calculated {formatDateTime(acquisition.calculatedAt)}
            </p>
          ) : null}
        </div>
      </div>

      {detail.subscriptions.length > 0 ? (
        <ul className={styles.subscriptionList}>
          {detail.subscriptions.map((subscription) => (
            <li className={styles.subscriptionRow} key={subscription.stripeSubscriptionId}>
              <div>
                <strong>{subscription.stripeSubscriptionId}</strong>
                <p className={styles.subscriptionMeta}>
                  {subscription.currency}
                  {subscription.currentPeriodEnd
                    ? ` · renews ${formatDateTime(subscription.currentPeriodEnd)}`
                    : ""}
                  {subscription.cancelAtPeriodEnd ? " · cancels at period end" : ""}
                  {subscription.trialEnd
                    ? ` · trial ends ${formatDateTime(subscription.trialEnd)}`
                    : ""}
                </p>
              </div>
              <StatusBadge tone={SUBSCRIPTION_STATUS_TONE[subscription.status]}>
                {SUBSCRIPTION_STATUS_LABEL[subscription.status]}
              </StatusBadge>
            </li>
          ))}
        </ul>
      ) : null}

      <details className={styles.disclosure}>
        <summary>
          <span className={styles.disclosureLabel}>Why this attribution?</span>
        </summary>
        <div className={styles.disclosureBody}>
          <dl>
            <dt>Status</dt>
            <dd>
              {statusCopy.label}
              {acquisition.unattributedReason ? (
                <>
                  {" — "}
                  {ATTRIBUTION_EXCLUSION_REASON_COPY[acquisition.unattributedReason] ??
                    acquisition.unattributedReason}
                  {" "}
                  <code>({acquisition.unattributedReason})</code>
                </>
              ) : null}
            </dd>

            <dt>Model version</dt>
            <dd>{acquisition.modelVersion}</dd>

            {acquisition.firstTouch ? (
              <>
                <dt>First touch</dt>
                <dd>
                  {acquisition.firstTouch.source ?? "Direct traffic"}
                  {acquisition.firstTouch.campaign
                    ? ` · campaign: ${acquisition.firstTouch.campaign}`
                    : ""}
                  {acquisition.firstTouch.occurredAt
                    ? ` · ${formatDateTime(acquisition.firstTouch.occurredAt)}`
                    : ""}
                </dd>
              </>
            ) : null}

            {acquisition.lastTouch ? (
              <>
                <dt>Last touch</dt>
                <dd>
                  {acquisition.lastTouch.source ?? "Direct traffic"}
                  {acquisition.lastTouch.campaign
                    ? ` · campaign: ${acquisition.lastTouch.campaign}`
                    : ""}
                  {acquisition.lastTouch.occurredAt
                    ? ` · ${formatDateTime(acquisition.lastTouch.occurredAt)}`
                    : ""}
                </dd>
              </>
            ) : null}

            {acquisition.customerLinkEvidenceId ? (
              <>
                <dt>Identity link evidence</dt>
                <dd>
                  <code>{acquisition.customerLinkEvidenceId}</code>
                </dd>
              </>
            ) : null}

            {acquisition.sourceReferences.length > 0 ? (
              <>
                <dt>Source references</dt>
                <dd>
                  {acquisition.sourceReferences.map((reference) => (
                    <div key={reference}>
                      <code>{reference}</code>
                    </div>
                  ))}
                </dd>
              </>
            ) : null}

            {acquisition.calculatedAt ? (
              <>
                <dt>Calculated at</dt>
                <dd>{formatDateTime(acquisition.calculatedAt)}</dd>
              </>
            ) : null}
          </dl>
        </div>
      </details>

      <RepairSection
        workspaceId={workspaceId}
        projectId={projectId}
        detail={detail}
        onRepaired={onRepaired}
      />
    </Panel>
  );
}

function RepairSection({
  workspaceId,
  projectId,
  detail,
  onRepaired,
}: CustomerOverviewPanelProps) {
  const [externalUserId, setExternalUserId] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  if (!detail.repairCapability.canRepair) {
    return (
      <Alert
        tone="info"
        title="Repair requires manager permission."
        detail={
          detail.activeLink
            ? "This customer already has an identity link. Ask a workspace owner or admin to correct it if it's wrong."
            : "Ask a workspace owner or admin to link this customer to an application user."
        }
      />
    );
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!externalUserId.trim()) return;
    setSubmitting(true);
    setError(null);
    setSuccess(null);
    try {
      const client = createBrowserClient();
      await repairCustomerLink(
        client,
        workspaceId,
        projectId,
        externalUserId.trim(),
        detail.stripeCustomerId,
      );
      const refreshed = await getCustomerTimeline(
        client,
        workspaceId,
        projectId,
        detail.stripeCustomerId,
      );
      setSuccess(`Linked to ${externalUserId.trim()}.`);
      setExternalUserId("");
      onRepaired(refreshed);
    } catch (submitError) {
      setError(
        submitError instanceof ApiError
          ? submitError.message
          : "Could not repair this link. Try again.",
      );
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form className={styles.repairForm} onSubmit={submit} aria-label="Repair identity link">
      <div className={styles.repairField}>
        <Field
          label={detail.activeLink ? "Correct the linked application user ID" : "Link to an application user ID"}
          hint="The externalUserId passed to identify() by your tracker."
          value={externalUserId}
          onChange={(event) => setExternalUserId(event.target.value)}
          placeholder="user_123"
        />
      </div>
      <Button type="submit" variant="primary" loading={submitting} disabled={!externalUserId.trim()}>
        {detail.activeLink ? "Correct link" : "Link customer"}
      </Button>
      {error ? (
        <p role="alert" style={{ color: "var(--ds-danger)", fontSize: "0.8125rem", width: "100%" }}>
          {error}
        </p>
      ) : null}
      {success ? (
        <p role="status" style={{ color: "var(--ds-positive)", fontSize: "0.8125rem", width: "100%" }}>
          {success}
        </p>
      ) : null}
    </form>
  );
}
