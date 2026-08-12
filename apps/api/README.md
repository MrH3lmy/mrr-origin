# Tracking ingestion contract

The browser tracker sends version 1 batches to `POST /api/public/v1/events` with
`Content-Type: application/json`, `Origin: https://customer.example`, and the
write-only project credential in `X-Ingestion-Key`.

The ingestion key—not any request-body identifier—selects the owning workspace
and project. The `Origin` host is normalized with the same lowercase, IDN, and
trailing-dot policy used for configured allowed domains, then checked against
that key's project.

Requests are limited to **1 MiB before JSON deserialization** and **100 events**.
Each event timestamp may be exactly 30 days old through exactly 5 minutes in the
future, relative to API receipt time. Older or later timestamps are rejected.

The whole batch is one database transaction: validation or persistence failure
writes nothing. A project-scoped transaction lock serializes ingestion so event
duplicates are detected before visitor or session mutation. New events return
`ACCEPTED`; an event ID already stored for the project returns `DUPLICATE` without
changing its original event, visitor, or session data.

An event with type `identify` carries a non-blank `payload.externalUserId` of at
most 160 characters. External identities and visitor aliases are scoped to the
ingestion key's project. Re-identifying a visitor with the same user is
idempotent, and a known user may collect multiple visitor aliases. A visitor's
first identity link wins: attempting to identify that visitor as a different
user returns `409 visitor_identity_conflict` and rolls back the whole batch.
No email or inferred browser identity participates in this flow.

The API stores the canonical request hash and final per-event results with every
batch receipt. Retrying the exact batch ID and content returns the originally
stored response. Reusing a batch ID with different content returns `409`.

Status codes are:

- `200` for accepted batches and exact retries;
- `400` for malformed/invalid envelopes, more than 100 events, duplicate IDs in
  one batch, unsupported versions, or out-of-window timestamps;
- `401` for unknown or revoked ingestion keys;
- `403` for malformed or unlisted origins;
- `409` for batch-ID/content or session/visitor conflicts; and
- `413` when the raw request body exceeds 1 MiB.

Clients may retry network failures and ambiguous responses with the same batch
ID and unchanged body. They must generate a new batch ID when changing content.

# Stripe customer linking contract

`POST /api/workspaces/{workspaceId}/projects/{projectId}/stripe-customer-links`
links a project's tracked external-user identity (created by `identify()`
above) to a Stripe customer already observed in the workspace's billing
ledger. This is a workspace-management API, authenticated with the same OIDC
bearer token as the rest of `/api/workspaces/**`, and called from the
founder's own backend — never from the browser tracker, since it deals in
Stripe customer IDs. The caller must hold `OWNER` or `ADMIN` on the
workspace.

Request body: `{"externalUserId": "...", "stripeCustomerId": "..."}`. Both
IDs must already exist: `externalUserId` as an `external_identities` row for
`projectId` (i.e. already `identify()`-ed), and `stripeCustomerId` as a
`billing_customers` row for `workspaceId` (i.e. already observed via Stripe
backfill or webhook normalization). Neither can be guessed into existence —
each is checked structurally, scoped by workspace and project, so one
tenant's visitor, application user, Stripe connection, or customer can never
be linked by another tenant.

Re-linking the same external user to the same Stripe customer is idempotent
and returns the existing link unchanged. Attempting to link an external user
already linked to a *different* Stripe customer, or a Stripe customer already
linked to a *different* external user, returns a stable `409` with a
distinguishing `code` (`external_user_already_linked` or
`stripe_customer_already_linked`) — active links are structurally unique
(one per tracked identity, one per Stripe customer) via partial unique
indexes, so this holds under concurrent requests too. No repair/relink
workflow exists yet, so a conflicting link must be resolved by a later,
dedicated issue rather than this endpoint.

`GET /api/workspaces/{workspaceId}/projects/{projectId}/stripe-customer-links/{externalUserId}`
returns the current active link, or `404 stripe_customer_link_not_found`.

Every link row records its provenance: `evidenceSource`, `evidenceReference`,
`linkedBySubjectId` (the authenticated actor), and `createdAt`. Only the
`EXPLICIT_API` evidence source is populated today. `STRIPE_METADATA` is
reserved in the schema for a follow-up issue — `billing_customers` does not
yet persist Stripe `metadata`, so there is no deterministic, inspectable
evidence available to drive it automatically. A `superseded_at` column is
likewise reserved for a future repair-workflow issue; this endpoint never
sets it.

No IP address, device fingerprint, or email-guessing heuristic participates
in this flow.

Status codes are:

- `200` for a created or idempotently-repeated link, and for a successful
  read;
- `400` for a missing/oversized `externalUserId` or `stripeCustomerId`;
- `403` for a member without `OWNER`/`ADMIN` on the workspace;
- `404` for an unknown workspace/project (membership-gated, matching the rest
  of `/api/workspaces/**`), an `externalUserId` never `identify()`-ed for
  that project, a `stripeCustomerId` never observed in that workspace's
  ledger, or no active link on the `GET`; and
- `409` for a conflicting active link in either direction.
