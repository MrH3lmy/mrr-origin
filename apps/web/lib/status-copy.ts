import type { StatusTone } from "@/components/ui/StatusBadge";
import type {
  DiagnosticState,
  StripeBillingHealthReason,
  StripeBillingHealthStatus,
} from "@/lib/api/types";

export interface StatusCopy {
  tone: StatusTone;
  label: string;
  headline: string;
  detail: string;
}

export const DIAGNOSTIC_STATE_COPY: Record<DiagnosticState, StatusCopy> = {
  RECEIVING: {
    tone: "positive",
    label: "Receiving events",
    headline: "Tracker detected. Events are arriving.",
    detail:
      "At least one event has been accepted for this project. You can continue to Stripe.",
  },
  NO_TRAFFIC: {
    tone: "neutral",
    label: "Waiting for traffic",
    headline: "No traffic detected yet.",
    detail: "Open your site, refresh it, then check again.",
  },
  BLOCKED_ORIGIN: {
    tone: "warning",
    label: "Blocked origin",
    headline: "Events were sent from a domain that isn't allowed yet.",
    detail:
      "Add the domain your site actually runs on to Allowed domains below, then check again.",
  },
  INVALID_KEY: {
    tone: "warning",
    label: "Invalid key",
    headline: "The wrong installation key is being used.",
    detail:
      "Copy the current key below into your tracker installation, then check again.",
  },
  INVALID_PAYLOAD: {
    tone: "danger",
    label: "Invalid payload",
    headline: "Events arrived but couldn't be processed.",
    detail:
      "This usually means the tracker payload is malformed. Confirm you're using @mrr-origin/tracker unmodified.",
  },
};

export const STRIPE_HEALTH_STATUS_COPY: Record<
  StripeBillingHealthStatus,
  StatusCopy
> = {
  HEALTHY: {
    tone: "positive",
    label: "Healthy",
    headline: "Stripe data is healthy and up to date.",
    detail: "No pending processing backlog and the initial sync is complete.",
  },
  STALE: {
    tone: "warning",
    label: "Stale",
    headline: "Stripe data is connected but not fully current.",
    detail:
      "Either the initial sync is still running or recent webhooks haven't finished processing.",
  },
  DEGRADED: {
    tone: "danger",
    label: "Degraded",
    headline: "Stripe data needs attention.",
    detail:
      "A connection, webhook, or reconciliation problem is blocking accurate revenue data.",
  },
};

export const STRIPE_HEALTH_REASON_COPY: Record<
  StripeBillingHealthReason,
  string
> = {
  NO_ACTIVE_CONNECTION: "No Stripe account has been connected yet.",
  CONNECTION_NOT_ACTIVE:
    "The Stripe connection is not currently active — reconnect to restore it.",
  CONNECTION_UNVERIFIED:
    "MRROrigin could not verify access to this Stripe account.",
  WEBHOOK_FAILURES_PRESENT:
    "At least one Stripe webhook failed processing and needs review.",
  RECONCILIATION_MISMATCH_PRESENT:
    "Some local billing records reference Stripe objects that haven't arrived yet.",
  PROVIDER_RECONCILIATION_MISMATCH_PRESENT:
    "A live check found Stripe records that don't exist locally yet.",
  SYNC_LAG_EXCEEDED:
    "Webhook processing has fallen behind — the oldest pending event is over 24 hours old.",
  BACKFILL_IN_PROGRESS:
    "The initial historical sync from Stripe hasn't finished yet.",
  ORPHANED_EVENTS_PRESENT:
    "At least one webhook event couldn't be matched to a connection.",
  PROVIDER_CHECK_UNAVAILABLE:
    "The live comparison against Stripe couldn't run this time.",
};
