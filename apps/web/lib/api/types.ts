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

// -- Attribution coverage (#22, backed by #19's coverage read model) --

export interface AttributionCoverage {
  modelVersion: string;
  eligibleNewCustomers: number;
  attributedNewCustomers: number;
  coverageRatio: number;
  exclusionReasonCounts: Record<string, number>;
}

// -- Revenue overview + movement drill-down (#22) --

export type MrrMovementType =
  | "NEW"
  | "EXPANSION"
  | "CONTRACTION"
  | "CHURN"
  | "REACTIVATION";

export interface MrrMovementTotal {
  currency: string;
  movementType: MrrMovementType;
  totalMinor: number;
  movementCount: number;
}

export interface CurrentMrrTotal {
  currency: string;
  totalMinor: number;
  customerCount: number;
}

export interface SourceHighlight {
  /** Null when the New MRR in this bucket is not strongly attributed. */
  source: string | null;
  currency: string;
  totalMinor: number;
  customerCount: number;
}

export interface RevenueOverview {
  workspaceId: string;
  projectId: string;
  from: string;
  to: string;
  calculationVersion: string;
  modelVersion: string;
  movementTotals: MrrMovementTotal[];
  currentMrr: CurrentMrrTotal[];
  sourceHighlights: SourceHighlight[];
}

export interface MovementTouch {
  source: string | null;
  campaign: string | null;
  landingPage: string | null;
}

export interface MrrMovementEntry {
  movementId: string;
  stripeCustomerId: string;
  currency: string;
  amountMinor: number;
  movementType: MrrMovementType;
  effectiveAt: string;
  /** Null when attribution has not been recalculated for this movement yet. */
  confidence: "STRONG" | "UNATTRIBUTED" | null;
  unattributedReason: string | null;
  firstTouch: MovementTouch | null;
  lastTouch: MovementTouch | null;
}

export interface MrrMovementsPage {
  entries: MrrMovementEntry[];
  nextCursor: string | null;
}

// -- Source/campaign/landing-page comparison (#23) --

export type ComparisonDimension = "SOURCE" | "CAMPAIGN" | "LANDING_PAGE";

/**
 * One aggregated cell of the comparison table. `dimensionValue` is null for exactly one of two
 * distinct "no value at this level" cases, told apart by `attributed` so a real value can never be
 * conflated with a bucket that has none:
 *
 * - `attributed: false` (SOURCE only): no acceptable acquisition evidence at all -- Unattributed.
 * - `attributed: true`: strongly attributed (real customer-link + touchpoint evidence exists), but
 *   this specific field wasn't captured on that touchpoint (e.g. a direct visit with no
 *   `utm_source`). CAMPAIGN/LANDING_PAGE rows are always `attributed: true` -- those levels only
 *   ever compare within an already strongly-attributed parent source.
 *
 * `movementType` is "NEW" or "CHURN" -- #23 does not report expansion/contraction/reactivation.
 */
export interface ComparisonRow {
  dimensionValue: string | null;
  attributed: boolean;
  currency: string;
  movementType: "NEW" | "CHURN";
  totalMinor: number;
  customerCount: number;
}

/** Names a metric that is unavailable for at least one comparison row, and why. */
export interface UnavailableMetric {
  metric: "RETAINED_MRR" | "NRR";
  reason: string;
}

export interface SourceComparison {
  workspaceId: string;
  projectId: string;
  from: string;
  to: string;
  dimension: ComparisonDimension;
  /** Echoes the parent drill-down filter that was applied, if any. */
  source: string | null;
  campaign: string | null;
  /** True when the request selected the "no campaign captured" bucket explicitly, rather than a real campaign value. */
  campaignMissing: boolean;
  rows: ComparisonRow[];
  /** The cohort age (30/60/90) `retention` was computed at. */
  retentionAgeDays: RetentionAgeDays;
  /** #25's authoritative Retained MRR / NRR, one row per (dimensionValue, attributed, currency). */
  retention: RetentionSummaryRow[];
  /** Empty only when every comparison row has authoritative Retained MRR and NRR. */
  unavailableMetrics: UnavailableMetric[];
}

// -- 30/60/90-day retained-MRR cohorts (#25) --

export type RetentionAgeDays = 30 | 60 | 90;

/**
 * One cohort's outcome at a given age. `available: false` means the age hasn't matured yet
 * (`unavailableReason: "MATURITY_PENDING"`) -- every numeric field is null, never a fabricated zero.
 * `retentionPercentage`/`nrr` are ratios (0.85, not 85). `retainedMrrMinor` includes expansion and
 * reactivation and is zero for a fully churned cohort; `nrr` deliberately excludes reactivation
 * (ADR-0004/ADR-0006), so the two can visibly diverge for a cohort that churned and came back.
 */
export interface RetentionAgeCell {
  available: boolean;
  unavailableReason: string | null;
  retainedMrrMinor: number | null;
  retentionPercentage: number | null;
  expansionMrrMinor: number | null;
  contractionMrrMinor: number | null;
  churnMrrMinor: number | null;
  reactivationMrrMinor: number | null;
  nrr: number | null;
}

/**
 * One acquisition-month cohort. `dimensionValue`/`attributed` follow the same "no value at this
 * level" convention as `ComparisonRow`. `startingMrrMinor`/`sampleSize` are acquisition facts and
 * are always populated; `age30`/`age60`/`age90` are populated only once mature.
 */
export interface RetentionCohortRow {
  dimensionValue: string | null;
  attributed: boolean;
  currency: string;
  periodStart: string;
  periodEnd: string;
  startingMrrMinor: number;
  sampleSize: number;
  age30: RetentionAgeCell;
  age60: RetentionAgeCell;
  age90: RetentionAgeCell;
}

export interface RetentionCohorts {
  workspaceId: string;
  projectId: string;
  dimension: ComparisonDimension;
  source: string | null;
  campaign: string | null;
  campaignMissing: boolean;
  cohorts: RetentionCohortRow[];
}

/** One dimension value's cohorts combined across an arbitrary date range, for one age. */
export interface RetentionSummaryRow {
  dimensionValue: string | null;
  attributed: boolean;
  currency: string;
  startingMrrMinor: number;
  sampleSize: number;
  cell: RetentionAgeCell;
}

export interface RetentionSummary {
  workspaceId: string;
  projectId: string;
  from: string;
  to: string;
  dimension: ComparisonDimension;
  source: string | null;
  campaign: string | null;
  campaignMissing: boolean;
  ageDays: RetentionAgeDays;
  rows: RetentionSummaryRow[];
}

// -- Customer directory + evidence timeline (#24) --

export interface CustomerCurrentMrr {
  currency: string;
  amountMinor: number;
}

/**
 * `confidence`/`unattributedReason`/`firstSource` are null when the customer has no `NEW` movement
 * yet, or attribution has not been (re)calculated for it under the current model version -- an
 * operational gap, never a fabricated negative result.
 */
export interface CustomerDirectoryEntry {
  stripeCustomerId: string;
  deleted: boolean;
  providerCreatedAt: string;
  acquisitionEffectiveAt: string | null;
  confidence: "STRONG" | "UNATTRIBUTED" | null;
  unattributedReason: string | null;
  firstSource: string | null;
  currentMrr: CustomerCurrentMrr[];
  subscriptionStatuses: string[];
}

export interface CustomerDirectoryPage {
  entries: CustomerDirectoryEntry[];
  nextCursor: string | null;
}

export type BillingSubscriptionStatus =
  | "incomplete"
  | "incomplete_expired"
  | "trialing"
  | "active"
  | "past_due"
  | "canceled"
  | "unpaid"
  | "paused";

export interface CustomerSubscriptionSummary {
  stripeSubscriptionId: string;
  status: BillingSubscriptionStatus;
  currency: string;
  currentPeriodStart: string | null;
  currentPeriodEnd: string | null;
  cancelAtPeriodEnd: boolean;
  cancelAt: string | null;
  canceledAt: string | null;
  trialStart: string | null;
  trialEnd: string | null;
}

export interface CustomerTouchSummary {
  touchpointId: string;
  occurredAt: string | null;
  source: string | null;
  campaign: string | null;
  landingPage: string | null;
}

export type CustomerAcquisitionStatus =
  | "STRONG"
  | "UNATTRIBUTED"
  | "NOT_RECALCULATED"
  | "NO_ACQUISITION_MOVEMENT";

/**
 * `movementId`/`effectiveAt` are null only for `NO_ACQUISITION_MOVEMENT` (the customer has never had
 * a `NEW` movement). `firstTouch`/`lastTouch`/`customerLinkEvidenceId`/`calculatedAt` are null unless
 * `status` is `STRONG` or `UNATTRIBUTED` (a stored result exists).
 */
export interface CustomerAcquisitionSummary {
  movementId: string | null;
  effectiveAt: string | null;
  modelVersion: string;
  status: CustomerAcquisitionStatus;
  unattributedReason: string | null;
  firstTouch: CustomerTouchSummary | null;
  lastTouch: CustomerTouchSummary | null;
  customerLinkEvidenceId: string | null;
  sourceReferences: string[];
  calculatedAt: string | null;
}

/**
 * Raw application-user and actor subject IDs are null for non-manager roles. Link existence and
 * evidence metadata remain visible so read-only members can still audit attribution.
 */
export interface CustomerActiveLink {
  id: string;
  externalUserId: string | null;
  evidenceSource: string;
  linkedBySubjectId: string | null;
  createdAt: string;
}

/** `reason` is populated only when `canRepair` is false. */
export interface CustomerRepairCapability {
  canRepair: boolean;
  reason: string | null;
}

export interface CustomerDetail {
  stripeCustomerId: string;
  deleted: boolean;
  providerCreatedAt: string;
  subscriptions: CustomerSubscriptionSummary[];
  currentMrr: CustomerCurrentMrr[];
  acquisition: CustomerAcquisitionSummary;
  activeLink: CustomerActiveLink | null;
  repairCapability: CustomerRepairCapability;
}

export type CustomerTimelineEventType =
  | "IDENTITY_LINK_CREATED"
  | "IDENTITY_LINK_SUPERSEDED"
  | "TOUCHPOINT_FIRST"
  | "TOUCHPOINT_LAST"
  | "TOUCHPOINT_FIRST_AND_LAST"
  | "SUBSCRIPTION_STATUS_CHANGED"
  | "MRR_MOVEMENT"
  | "ATTRIBUTION_CALCULATED"
  | "REPAIR_AUDIT";

/**
 * One evidence-timeline row. `eventType`/`at`/`referenceId`/`explanation` are always populated. Every
 * other field is populated only when relevant to that event type, and is otherwise null -- never a
 * fabricated default.
 */
export interface CustomerTimelineEntry {
  eventType: CustomerTimelineEventType;
  at: string;
  referenceId: string;
  explanation: string;
  currency: string | null;
  amountMinor: number | null;
  movementType: MrrMovementType | null;
  source: string | null;
  campaign: string | null;
  landingPage: string | null;
  previousStatus: string | null;
  newStatus: string | null;
  confidence: "STRONG" | "UNATTRIBUTED" | null;
  unattributedReason: string | null;
  modelVersion: string | null;
  actionType: string | null;
  externalUserId: string | null;
}

export interface CustomerTimeline {
  detail: CustomerDetail;
  entries: CustomerTimelineEntry[];
  nextCursor: string | null;
}

// -- Manual repair (#20, first consumed by the #24 customer timeline UI) --

export interface StripeCustomerLinkOutcome {
  id: string;
  workspaceId: string;
  projectId: string;
  externalUserId: string;
  stripeCustomerId: string;
  evidenceSource: string;
  evidenceReference: string;
  linkedBySubjectId: string;
  createdAt: string;
}

export interface CustomerAttributionExplanationEntry {
  movementId: string;
  movementType: MrrMovementType;
  movementAt: string;
  modelVersion: string;
  confidence: "STRONG" | "UNATTRIBUTED";
  unattributedReason: string | null;
  calculatedAt: string;
}

export interface RepairCustomerLinkResponse {
  link: StripeCustomerLinkOutcome;
  actionType: "CREATED" | "CORRECTED" | "UNCHANGED";
  previousIdentityStripeCustomerId: string | null;
  targetCustomerAttribution: CustomerAttributionExplanationEntry[];
  displacedCustomerId: string | null;
  displacedCustomerAttribution: CustomerAttributionExplanationEntry[];
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

// -- Weekly action summary (#26) --

export interface WeeklySummaryEvidenceFilters {
  from: string;
  to: string;
  movementType: MrrMovementType | null;
  currency: string | null;
  source: string | null;
  sourceUnattributed: boolean;
  sourceMissing: boolean;
  campaign: string | null;
  campaignMissing: boolean;
  landingPage: string | null;
  landingPageMissing: boolean;
}

export type WeeklySummaryInsightStatus =
  | "MATERIAL_CHANGE"
  | "NEWLY_APPEARED"
  | "DISAPPEARED"
  | "INSUFFICIENT_SAMPLE"
  | "STABLE";

/**
 * One New/Churned MRR week-over-week comparison for a `(dimension, bucket, currency)`. `dimensionBucket`
 * follows the same three-state contract as `ComparisonRow`: `null` with a real `dimensionValue`,
 * `"NONE"` (evidence exists, this field wasn't captured), or `"UNATTRIBUTED"` (SOURCE only, no
 * evidence at all) -- the two are never coalesced. `percentageChange` is null exactly when
 * `priorAmountMinor` is 0 (undefined ratio, `status: "NEWLY_APPEARED"`); a zero *current* amount
 * against a nonzero prior is `-1.0`, not null (`status: "DISAPPEARED"`). `applicableCustomerCount` is
 * the exact count #26's low-volume/material-change gate used, so a client never has to re-derive it.
 */
export interface WeeklySummaryInsight {
  dimension: ComparisonDimension;
  dimensionValue: string | null;
  dimensionBucket: "NONE" | "UNATTRIBUTED" | null;
  movementType: "NEW" | "CHURN";
  currentAmountMinor: number;
  currentCustomerCount: number;
  priorAmountMinor: number;
  priorCustomerCount: number;
  percentageChange: number | null;
  applicableCustomerCount: number;
  status: WeeklySummaryInsightStatus;
  currentEvidenceFilters: WeeklySummaryEvidenceFilters;
  priorEvidenceFilters: WeeklySummaryEvidenceFilters;
  currentEvidenceLink: string;
  priorEvidenceLink: string;
}

export interface WeeklySummaryCurrencySection {
  currency: string;
  insights: WeeklySummaryInsight[];
}

export interface WeeklySummary {
  workspaceId: string;
  projectId: string;
  /** IANA zone id (`Project.timezone`) the week boundaries below are computed in. */
  timezone: string;
  weekStart: string;
  weekEnd: string;
  priorWeekStart: string;
  priorWeekEnd: string;
  generatedAt: string;
  currencySections: WeeklySummaryCurrencySection[];
}

// -- Weekly summary delivery (#59) --

export interface WeeklySummaryOptOut {
  optedOut: boolean;
}

export type WeeklySummaryDeliveryStatus =
  | "PENDING"
  | "SENDING"
  | "SENT"
  | "FAILED"
  | "PERMANENTLY_FAILED"
  | "BLOCKED_MISSING_EMAIL";

/**
 * `recipientEmail` is null exactly when `status` is `BLOCKED_MISSING_EMAIL` (accepted B3
 * correction: the member has no verified email yet). `lastOutcomeAmbiguous` is true when the last
 * failed attempt was a network-level failure whose outcome on the provider side is unknown (the
 * delivery plan's "Delivery guarantee" -- at-least-once, not exactly-once).
 */
export interface WeeklySummaryDelivery {
  id: string;
  recipientEmail: string | null;
  weekStart: string;
  status: WeeklySummaryDeliveryStatus;
  attemptCount: number;
  lastError: string | null;
  lastOutcomeAmbiguous: boolean;
  providerMessageId: string | null;
  createdAt: string;
  updatedAt: string;
}
