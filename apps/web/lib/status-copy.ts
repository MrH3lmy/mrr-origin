import type { StatusTone } from "@/components/ui/StatusBadge";
import type {
  BillingSubscriptionStatus,
  CustomerAcquisitionStatus,
  DiagnosticState,
  MrrMovementType,
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

/** Verbatim humanized reason copy from DESIGN_SYSTEM.md's "Data health and unattributed repair". */
export const ATTRIBUTION_EXCLUSION_REASON_COPY: Record<string, string> = {
  NO_ACTIVE_LINK: "No application user is linked to this Stripe customer.",
  NO_ELIGIBLE_TOUCHPOINT:
    "The user is linked, but no eligible acquisition touchpoint exists in the attribution window.",
  NOT_RECALCULATED:
    "Attribution has not been recalculated for the current model yet.",
};

export const MOVEMENT_TYPE_LABEL: Record<MrrMovementType, string> = {
  NEW: "New MRR",
  EXPANSION: "Expansion MRR",
  CONTRACTION: "Contraction MRR",
  CHURN: "Churned MRR",
  REACTIVATION: "Reactivation MRR",
};

/** #24: humanized reason copy for the customer timeline's acquisition summary. */
export const ACQUISITION_STATUS_COPY: Record<CustomerAcquisitionStatus, StatusCopy> = {
  STRONG: {
    tone: "positive",
    label: "Attributed",
    headline: "This customer's acquisition source is strongly attributed.",
    detail: "A tracked visitor was explicitly identified and linked to this Stripe customer.",
  },
  UNATTRIBUTED: {
    tone: "neutral",
    label: "Unattributed",
    headline: "No acquisition source could be determined for this customer.",
    detail: "See the reason below, and repair the link if one is available.",
  },
  NOT_RECALCULATED: {
    tone: "info",
    label: "Not yet calculated",
    headline: "Attribution has not been recalculated for the current model yet.",
    detail: "This is an operational gap, not a negative result.",
  },
  NO_ACQUISITION_MOVEMENT: {
    tone: "neutral",
    label: "No paid acquisition yet",
    headline: "This customer has never had positive recurring revenue.",
    detail: "There is no New MRR movement to attribute.",
  },
};

export const SUBSCRIPTION_STATUS_LABEL: Record<BillingSubscriptionStatus, string> = {
  incomplete: "Incomplete",
  incomplete_expired: "Incomplete (expired)",
  trialing: "Trialing",
  active: "Active",
  past_due: "Past due",
  canceled: "Canceled",
  unpaid: "Unpaid",
  paused: "Paused",
};

export const SUBSCRIPTION_STATUS_TONE: Record<BillingSubscriptionStatus, StatusTone> = {
  incomplete: "neutral",
  incomplete_expired: "danger",
  trialing: "info",
  active: "positive",
  past_due: "warning",
  canceled: "neutral",
  unpaid: "danger",
  paused: "neutral",
};
