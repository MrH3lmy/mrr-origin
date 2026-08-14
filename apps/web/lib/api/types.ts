// Mirrors apps/api response DTOs exactly (field names/shape) so the UI never invents state.

export type WorkspaceRole = "OWNER" | "ADMIN" | "MEMBER" | "VIEWER";

export interface Workspace {
  id: string;
  name: string;
  slug: string;
  reportingCurrency: string;
  role: WorkspaceRole;
  createdAt: string;
}

export interface Project {
  id: string;
  workspaceId: string;
  name: string;
  domain: string;
  publicKey: string;
  timezone: string;
  createdAt: string;
}

// -- Tracking (#8) --

export interface ActiveIngestionKey {
  present: boolean;
  id: string | null;
  prefix: string | null;
  createdAt: string | null;
}

export interface IssuedIngestionKey {
  id: string;
  secret: string;
  prefix: string;
  rotated: boolean;
}

export interface AllowedDomain {
  id: string;
  domain: string;
}

export type VerificationStatus = "PENDING" | "SUCCEEDED" | "EXPIRED";

export interface VerificationAttempt {
  id: string;
  token: string;
  status: VerificationStatus;
  createdAt: string;
  expiresAt: string;
  succeededAt: string | null;
}

export type DiagnosticState =
  | "NO_TRAFFIC"
  | "BLOCKED_ORIGIN"
  | "INVALID_KEY"
  | "INVALID_PAYLOAD"
  | "RECEIVING";

export interface FailureSummary {
  count: number;
  lastOccurredAt: string | null;
}

export interface IdentityCoverage {
  totalVisitors: number;
  identifiedVisitors: number;
}

export interface ProjectDiagnosticsReport {
  workspaceId: string;
  projectId: string;
  state: DiagnosticState;
  everReceivedTraffic: boolean;
  lastAcceptedEventAt: string | null;
  lastAcceptedEventType: string | null;
  blockedOrigin: FailureSummary;
  invalidKey: FailureSummary;
  invalidPayload: FailureSummary;
  identityCoverage: IdentityCoverage;
  computedAt: string;
}

// -- Stripe connection + health (#15) --

export type StripeConnectionMode = "TEST" | "LIVE";
export type StripeConnectionStatus =
  | "PENDING"
  | "ACTIVE"
  | "DISCONNECTED"
  | "REVOKED";
export type StripeVerificationStatus = "UNVERIFIED" | "VERIFIED" | "FAILED";

export interface StripeConnection {
  id: string | null;
  workspaceId: string;
  stripeAccountId: string | null;
  mode: StripeConnectionMode | null;
  grantedScope: string | null;
  status: StripeConnectionStatus | null;
  verificationStatus: StripeVerificationStatus | null;
  connectedAt: string | null;
  disconnectedAt: string | null;
  lastVerifiedAt: string | null;
  lastVerificationFailedAt: string | null;
}

export type StripeBillingHealthStatus = "HEALTHY" | "STALE" | "DEGRADED";

export type StripeBillingHealthReason =
  | "NO_ACTIVE_CONNECTION"
  | "CONNECTION_NOT_ACTIVE"
  | "CONNECTION_UNVERIFIED"
  | "WEBHOOK_FAILURES_PRESENT"
  | "RECONCILIATION_MISMATCH_PRESENT"
  | "PROVIDER_RECONCILIATION_MISMATCH_PRESENT"
  | "SYNC_LAG_EXCEEDED"
  | "BACKFILL_IN_PROGRESS"
  | "ORPHANED_EVENTS_PRESENT"
  | "PROVIDER_CHECK_UNAVAILABLE";

export interface LedgerTotals {
  customers: number;
  prices: number;
  subscriptions: number;
  invoices: number;
  payments: number;
  refunds: number;
  discounts: number;
}

export interface ReconciliationMismatch {
  kind: string;
  count: number;
  truncated: boolean;
  sampleStripeIds: string[];
}

export interface StripeBillingHealthReport {
  workspaceId: string;
  status: StripeBillingHealthStatus;
  reasons: StripeBillingHealthReason[];
  connectionPresent: boolean;
  connectionStatus: StripeConnectionStatus | null;
  verificationStatus: StripeVerificationStatus | null;
  connectionMode: StripeConnectionMode | null;
  backfillPhase: string | null;
  backfillComplete: boolean;
  lastSyncAt: string | null;
  syncLagSeconds: number | null;
  oldestPendingEventAgeSeconds: number | null;
  pendingWebhookEvents: number;
  orphanedWebhookEvents: number;
  processedWebhookEvents: number;
  failedWebhookEventsTransient: number;
  failedWebhookEventsUnsupported: number;
  failedWebhookEventsLegacy: number;
  ledgerTotals: LedgerTotals;
  reconciliationMismatches: ReconciliationMismatch[];
  computedAt: string;
}
