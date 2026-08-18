# Secret and key rotation runbook

Operator-facing procedures for every secret class in the system. Run the relevant procedure on a
fixed schedule (see each section) and immediately on suspected compromise. Nothing here changes
application code — every secret is already read from environment configuration
(`apps/api/src/main/resources/application.yml`); rotation is an infrastructure/operations action.

None of these secrets are currently in a dedicated KMS/secrets manager — they are process
environment variables, per the deferred decision noted in `docs/security/threat-model.md` §3.
Selecting and migrating to a KMS is a private-beta gate (see `docs/security/private-beta-checklist.md`);
until then, "the secret store" below means wherever the deployment's environment variables are
managed (e.g. the hosting provider's encrypted config/secrets feature) — never a committed file.

## Stripe platform secret key

**Blast radius if compromised:** full-platform incident. Because the key acts as any connected
workspace via the `Stripe-Account` header, compromise affects every connected workspace at once —
never treat it as a single-workspace incident. See ADR-0003 §"Credential-compromise procedure" for
the full 6-step incident process; the rotation mechanics are:

1. Create a new restricted key in the Stripe Dashboard with an identical permission set to the
   current one (read-only: customers, subscriptions/items, invoices, charges/payment intents,
   refunds, prices, coupons/discounts, events; Connect "act as connected accounts" enabled; no
   balance/payout/Treasury permissions — see ADR-0003 §"Least-privilege permissions").
2. Store the new key in the secret store alongside the current one; do not overwrite yet.
3. Smoke-test: make one `Stripe-Account`-scoped read call against a sample connected account using
   the new key and confirm it succeeds.
4. Switch the application's `STRIPE_CONNECT_{TEST,LIVE}_SECRET_KEY` to the new key and redeploy/restart.
5. Revoke the old key in the Stripe Dashboard.
6. Record the rotation (date, actor, reason) in the incident/ops log.

**Scheduled cadence:** every 90 days, or immediately on suspected compromise.

## Stripe Connect webhook signing secrets

Test and live modes have separate secrets (`STRIPE_CONNECT_TEST_WEBHOOK_SECRET`,
`STRIPE_CONNECT_LIVE_WEBHOOK_SECRET`); rotate them independently.

1. Generate a new signing secret on the relevant Stripe Connect webhook endpoint in the Dashboard.
   Stripe keeps the old secret valid until you delete it — this creates the overlap window.
2. During the overlap window, configure the application to verify incoming signatures against
   **both** the current and previous secret (ADR-0003 §"Rotation procedure"). This requires a code
   path that accepts two secrets per mode; if that dual-verification support isn't already present,
   add it before starting rotation rather than accepting a processing gap.
3. Once you've confirmed live traffic is verifying successfully against the new secret only, delete
   the old secret from the Stripe Dashboard and drop it from the application's dual-secret
   configuration.

**Scheduled cadence:** every 90 days, or immediately on suspected compromise. Rotate test and live
independently — never let a rotation event conflate the two modes.

## OIDC signing keys / identity provider

The production identity provider is a deferred architecture decision
(`ARCHITECTURE.md` → Deferred decisions); the API validates JWTs against whatever
`OIDC_ISSUER_URI`/`OIDC_JWK_SET_URI` point to (`docs/local-authentication.md`). Once a production
provider is selected:

1. Signing-key rotation should be handled by the identity provider's own key-rotation feature
   (most standards-compliant providers publish multiple active keys via the JWK Set URI and rotate
   automatically) — the API already resolves keys dynamically via `jwk-set-uri` and needs no code
   change to pick up a provider-side rotation.
2. If the provider or audience/client configuration itself changes (not just signing keys),
   update `OIDC_ISSUER_URI` / `OIDC_JWK_SET_URI` / `OIDC_AUDIENCE` and redeploy; verify with a live
   token exchange against a non-production workspace before rolling to all traffic.

**Scheduled cadence:** follow the identity provider's own key-rotation schedule; no manual action
needed unless the provider itself changes.

## Database credentials

`DATABASE_URL` / `DATABASE_USERNAME` / `DATABASE_PASSWORD`.

1. Create a new database role/password (or ask the managed-Postgres provider to rotate credentials)
   with the same grants as the current one.
2. Store the new credentials in the secret store.
3. Roll the application to the new credentials (supports a brief connection-pool restart; the API's
   graceful shutdown, `server.shutdown: graceful`, avoids dropping in-flight requests during the
   restart).
4. Revoke/drop the old role once all instances confirm healthy on the new credentials.

**Scheduled cadence:** every 90 days, or immediately on suspected compromise.

## Postmark server token

`POSTMARK_SERVER_TOKEN`.

1. Create a new server token in the Postmark account settings.
2. Store it in the secret store and roll the application to it.
3. Revoke the old token in Postmark.
4. Confirm the next scheduled weekly-summary dispatch succeeds (or send a manual test) before
   considering rotation complete — a bad token fails closed with a logged warning
   (`WeeklySummaryDispatchService`), it does not silently drop emails.

**Scheduled cadence:** every 180 days, or immediately on suspected compromise.

## Project ingestion keys

Unlike the secrets above, this is already a self-serve, in-product flow — no infra action needed.

1. A workspace manager calls `POST /api/workspaces/{workspaceId}/projects/{projectId}/tracking/ingestion-key`
   (or uses the onboarding UI). This issues a new key and **immediately revokes** the prior one —
   there is no overlap window, so the tracker snippet on the founder's site must be updated with the
   new key at the same time.
2. The raw secret is returned exactly once, in that response; it is never retrievable again after
   the call.

**When to rotate:** on suspected key leakage (e.g. a key visible in a public repo or shared
support ticket), or if a founder asks for it. No fixed schedule — self-service, founder-initiated.

## Incident contacts

Temporary private-beta assignment (decided on #27). A single point of contact for all three roles
is a stopgap, not a long-term posture — revisit once a dedicated security/legal contact is
appointed.

| Role                                    | Contact  | Notes                                                                  |
| --------------------------------------- | -------- | ---------------------------------------------------------------------- |
| Primary incident owner                  | @MrH3lmy |                                                                        |
| Stripe/billing-data incident escalation | @MrH3lmy | e.g. if the platform secret key is suspected compromised               |
| Legal/notification obligations          | @MrH3lmy | temporary coordinator until a real legal/security contact is appointed |
