# Production OIDC provider decision memo (#103)

- Status: **Decision memo only — awaiting owner approval.** Not an ADR.
- Date: 2026-08-28
- Issue: #103 (private-beta readiness gate B, `docs/security/private-beta-checklist.md` item 14)
- Depends on: #104 (Render hosting, approved direction), ADR-0012 / #102 (AWS Secrets Manager + KMS
  via Render OIDC workload identity, implementation merged, beta-environment verification pending)

Nothing in this memo has been implemented. No ADR, no provider account, no client ID/secret, no auth
code change, no cookie/session change, no region choice, no deployment.

This memo supersedes the earlier #103 issue comment (2026-08-26), which was written before #102's
implementation merged. Every architectural claim below was re-verified against the current `main`
(`33af7cb`), and two findings changed materially: the client secret is a **web-tier** secret that
ADR-0012's merged mechanism cannot reach, and it is **not the only** new secret the recommended
topology introduces.

---

## 1. Current auth architecture

### 1.1 `apps/api` — a pure JWT resource server, nothing else

`apps/api/src/main/java/com/mrrorigin/identity/SecurityConfiguration.java` is the entire
authentication surface:

- `sessionCreationPolicy(STATELESS)` — no server-side session, no cookie, no `JSESSIONID`.
- `oauth2ResourceServer(resourceServer -> resourceServer.jwt(withDefaults()))` — bearer JWT
  validation only.
- `csrf(...disable)` — correct and safe precisely _because_ the API is bearer-only and stateless.
- A short explicit `permitAll` list (`/actuator/health*`, `/actuator/info`, `/actuator/prometheus`,
  `/error`, public ingestion, Stripe OAuth callback, Stripe webhooks); `anyRequest().authenticated()`.

Verified absences, all of which matter to this decision:

- **No `JwtDecoder`, `OAuth2TokenValidator`, or `JwtAuthenticationConverter` bean exists anywhere in
  `apps/api/src`** (grep: zero hits). Validation is 100% Spring Boot property-driven.
- **`apps/api/pom.xml` has `spring-boot-starter-security-oauth2-resource-server` and no
  `...-oauth2-client`.** The API has no client registration, no `ClientRegistrationRepository`, and
  no confidential-client credential of its own.

Issuer / JWKS / audience are already externalized and provider-agnostic
(`apps/api/src/main/resources/application.yml`):

```yaml
spring.security.oauth2.resourceserver.jwt:
  issuer-uri: ${OIDC_ISSUER_URI:http://localhost:8081/realms/mrr-origin}
  jwk-set-uri: ${OIDC_JWK_SET_URI:http://localhost:8081/realms/mrr-origin/protocol/openid-connect/certs}
  audiences:
    - ${OIDC_AUDIENCE:mrr-origin-api}
```

`audiences` is Spring Boot's **built-in** audience validator. It checks the standard `aud` claim.
This is the single most important constraint in the provider comparison: a provider whose access
token carries no usable `aud` turns #103 from a configuration change into a code change, and breaks
the premise that both issue #103 and `docs/security/private-beta-checklist.md` item 14 rest on.

Authorization is separate from authentication and already correct: the validated `sub` is the
external IdP subject, and workspace access is re-checked against `workspace_members` in PostgreSQL
on every request (`docs/local-authentication.md`). **No provider choice below changes this**, and no
provider needs to model workspaces, organizations, or roles for MRROrigin.

### 1.2 `apps/web` — already a BFF, with a dev-only stand-in at the front door

`apps/web` is not an SPA holding tokens. The session seam already exists and already satisfies most
of the target topology:

| File                               | What it already does                                                                                                                                                                                  |
| ---------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `lib/auth/session.ts`              | `import "server-only"`. Session is a single cookie `mrr_session`: `httpOnly: true`, `sameSite: "lax"`, `secure` in production, `path: "/"`, `maxAge` 8h. Read only via `next/headers`.                |
| `app/api/proxy/[...path]/route.ts` | Same-origin proxy. Reads the cookie server-side, attaches `Authorization: Bearer …` server-side, streams the upstream response back. Returns `401 {code:"unauthenticated"}` when there is no session. |
| `lib/api/server-client.ts`         | `server-only`. Server components call the API directly with the bearer token; throws `ApiError(401)` with no session.                                                                                 |
| `app/app/layout.tsx`               | On upstream `401`: clears the cookie and redirects to `/auth/sign-in?error=session_expired`. Real expiry handling already exists.                                                                     |

`apps/web/package.json` dependencies are exactly `next`, `react`, `react-dom`, `server-only`. **There
is no `next-auth`, `openid-client`, `jose`, AWS SDK, or vendor auth SDK** — and no `middleware.ts`.
A clean slate, and also: no existing mechanism for the web tier to fetch a secret from anywhere.

**The gap is the front door only.** `app/auth/sign-in/page.tsx` renders a textarea; the operator
pastes a raw access token obtained out-of-band; `app/auth/session/route.ts` writes that raw token
straight into the cookie. There is no authorization-code exchange, no PKCE, no ID token, no refresh
token, and no provider session. `app/auth/sign-out/route.ts` deletes the local cookie and nothing
else. The page labels itself a local-development stand-in, and
`docs/private-beta/pre-beta-smoke-test.md` §2 explicitly forbids using it for the smoke test
("do not use the local token-paste development flow").

### 1.3 Direct answers to the four questions asked

| Question                                                 | Answer                                                                                                                                                                                                                                                                      |
| -------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Is the API already only a JWT resource server?           | **Yes, exclusively.** Stateless, no client role, no session, no custom validator.                                                                                                                                                                                           |
| Does the web application need to act as an OAuth client? | **Yes** — under the recommended topology, `apps/web` becomes the _only_ OAuth client in the system. Nothing else can perform the code exchange without making the API a client or putting tokens in the browser.                                                            |
| Are tokens exposed to browser JavaScript?                | **No, in the runtime path.** The cookie is `httpOnly`; no token is ever serialized into a client component or a response body. The one exception is the dev-only sign-in page, where a human pastes a token into a form field — that path is deleted or gated by this work. |
| Is a BFF/session-cookie model already possible?          | **Yes, and it is already half-built.** The server-only session module and same-origin proxy are the hard parts and they exist. What is missing is a real login/callback and encrypted session contents.                                                                     |

### 1.4 One verification gap worth recording now

Every API integration test authenticates via Spring Security's `jwt()` request post-processor
(`jwt().jwt(jwt -> jwt.subject(...))`), which **bypasses the real `JwtDecoder` entirely**. There is
no test in `apps/api/src/test` that decodes a real signed token against a real JWKS. Consequently
issuer validation, JWKS retrieval, signature verification, and the `audiences` check are **currently
unexercised by any automated test** — they are only ever exercised at runtime against a live
provider. This is not a defect introduced by #103, but it means #103's scope items ("verify issuer
and audience validation is enforced against the production provider's real values", "wrong-issuer
and wrong-audience credentials are rejected as `4xx`, not `500`") cannot be discharged by CI and
**must** be done as live beta-environment verification.

---

## 2. Provider comparison

Four realistic managed providers. Self-hosted Keycloak is excluded: no concrete reason exists to
take on self-managed identity infrastructure before beta when every managed option below clears the
bar, and `AGENTS.md` forbids new infrastructure without explicit approval.

Rows marked **⚠ verify** are integration details that must be confirmed against the provider's live
tenant before implementation begins. They are flagged rather than asserted because getting them
wrong turns "config change" into "code change" mid-implementation.

| Criterion                                     | Auth0                                                                                                                                                                                                                                                                                                                                                                      | WorkOS AuthKit                                                                                              | Clerk                                                                                                                                                                                                                                                | AWS Cognito                                                                                                                                                                                                         |
| --------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Spring Boot resource-server compatibility** | Excellent. Standard RS256 JWT + JWKS; the single deepest body of precedent for this exact stack. Zero code change.                                                                                                                                                                                                                                                         | Good. Standard JWT + JWKS. Audience shape must be confirmed (below).                                        | Workable but off-idiom. Clerk's session token is designed to be verified by Clerk's own SDKs; using it as a plain API access token means relying on JWT templates.                                                                                   | **Weak.** Cognito **access** tokens carry `client_id`, not `aud` (`aud` appears on the _ID_ token). Spring's `audiences` validator checks `aud` and would reject every token.                                       |
| **Consequence for `audiences` config**        | Works as-is.                                                                                                                                                                                                                                                                                                                                                               | ⚠ verify: confirm the access token carries an `aud` equal to your API identifier, not only `sid`/`org_id`. | ⚠ verify: requires a JWT template setting `aud`; the default session token is not shaped for an external API audience.                                                                                                                              | **Requires a custom `OAuth2TokenValidator` bean** validating `client_id`. This is new Java code in `apps/api` and directly contradicts issue #103's and ADR-0012's stated "provider-agnostic, config-only" premise. |
| **Next.js / BFF compatibility**               | Clean. Plain HTTP redirect to `/authorize` + server-side POST to `/oauth/token`. No client SDK required — matters, given `apps/web` has zero auth dependencies today.                                                                                                                                                                                                      | Clean. Same plain-HTTP shape; the Node SDK is optional sugar.                                               | Poorest fit _for this codebase_. Clerk's idiomatic path is `@clerk/nextjs` middleware mounted in the React tree — deeper coupling than the existing server-only proxy seam, and it wants to own the session that `lib/auth/session.ts` already owns. | Clean HTTP-wise (Hosted UI + standard token endpoint), undermined by the audience problem.                                                                                                                          |
| **OIDC standards support**                    | Full. Authorization Code + PKCE, discovery, RP-initiated logout, refresh tokens, `offline_access`.                                                                                                                                                                                                                                                                         | Full for the flows needed.                                                                                  | Discovery and JWKS exist; the session model is Clerk-specific.                                                                                                                                                                                       | Full for login; non-standard for API-audience validation, and its logout endpoint uses `client_id`/`logout_uri` rather than standard `id_token_hint`/`post_logout_redirect_uri`.                                    |
| **Issuer / JWKS / audience handling**         | Issuer `https://<tenant>.<region>.auth0.com/` (**trailing slash is significant** — a mismatch is a silent 401 source); JWKS at `<issuer>.well-known/jwks.json`; audience is an explicit "API" resource whose identifier string you choose. **`/authorize` must include `audience=<API identifier>` or Auth0 returns an opaque token the resource server cannot validate.** | WorkOS-hosted issuer and a client-scoped JWKS endpoint. ⚠ verify both exact values and `aud` presence.     | Issuer is the Clerk Frontend API URL; JWKS is real; claim shape (`azp`, session claims) is Clerk-specific.                                                                                                                                           | Issuer `https://cognito-idp.<region>.amazonaws.com/<poolId>`; JWKS at `<issuer>/.well-known/jwks.json`; **no usable `aud` on the access token**.                                                                    |
| **Client secret requirement**                 | Yes for a confidential "Regular Web Application" client — exactly the BFF shape.                                                                                                                                                                                                                                                                                           | Yes — a server-side WorkOS API key performs the code exchange.                                              | Yes — `CLERK_SECRET_KEY` for server-side SDK calls.                                                                                                                                                                                                  | Optional. A Cognito app client can be created without a secret (public client + PKCE), which avoids one web-tier secret — but does not offset the audience problem.                                                 |
| **HTTP-only cookie / session support**        | Provider-neutral; the cookie is MRROrigin's, not the provider's. Works with the existing `lib/auth/session.ts`.                                                                                                                                                                                                                                                            | Same.                                                                                                       | Clerk prefers to manage its own cookie/session, which duplicates and competes with the existing session module.                                                                                                                                      | Provider-neutral; same as Auth0.                                                                                                                                                                                    |
| **Refresh token handling**                    | `offline_access` scope; rotating refresh tokens supported; server-side refresh from the BFF.                                                                                                                                                                                                                                                                               | Supported; refresh + session revocation via API.                                                            | Supported, via SDK-managed session refresh.                                                                                                                                                                                                          | Supported; `RevokeToken` / `AdminUserGlobalSignOut` for revocation.                                                                                                                                                 |
| **Logout behavior**                           | RP-initiated logout ending the provider's own SSO session (`id_token_hint` + `post_logout_redirect_uri`), plus refresh-token revocation. This is what makes "logout requires re-authentication" true rather than cosmetic.                                                                                                                                                 | Session revocation via API from the BFF.                                                                    | Session revocation via SDK/API.                                                                                                                                                                                                                      | Hosted-UI `/logout` with non-standard parameters; revocation via the Cognito API.                                                                                                                                   |
| **MFA / security features**                   | Mature: TOTP, WebAuthn/passkeys, push, adaptive/anomaly detection, breached-password detection, brute-force protection.                                                                                                                                                                                                                                                    | Built-in MFA, hosted flows.                                                                                 | Built-in MFA, passkeys.                                                                                                                                                                                                                              | Built-in MFA (TOTP free; SMS billed separately); weaker adaptive-risk tooling.                                                                                                                                      |
| **Audit capabilities**                        | Tenant log stream of auth events; long retention and SIEM streaming are paid add-ons. Short free-tier retention is a beta-appropriate limitation, not a blocker.                                                                                                                                                                                                           | Audit Logs exist as a paid/enterprise add-on.                                                               | Basic dashboard event logs.                                                                                                                                                                                                                          | **CloudTrail** — the same audit source of record ADR-0012 already established for Secrets Manager/KMS. A genuine, if narrow, consistency advantage.                                                                 |
| **Custom domains**                            | Supported, paid tier. Cosmetic for beta; deferrable.                                                                                                                                                                                                                                                                                                                       | Supported.                                                                                                  | Supported.                                                                                                                                                                                                                                           | Supported, but the ACM certificate must live in `us-east-1` regardless of the user-pool region — an extra hoop that also touches #104's still-unmade region choice.                                                 |
| **Render compatibility**                      | Full — an external HTTPS IdP is platform-agnostic. Nothing Render-specific.                                                                                                                                                                                                                                                                                                | Full.                                                                                                       | Full.                                                                                                                                                                                                                                                | Full, and reuses the AWS account relationship ADR-0012 already requires.                                                                                                                                            |
| **Beta cost (5–10 founders)**                 | $0 — free tier ceiling is orders of magnitude above beta scale. ⚠ verify current tier limits at signup; Auth0 pricing tiers have moved more than once.                                                                                                                                                                                                                    | $0 — AuthKit's free MAU ceiling is far above beta scale.                                                    | $0 at this scale.                                                                                                                                                                                                                                    | ~$0 on the free/lowest tier; note Cognito moved to tiered pricing, so verify which tier the needed features fall in.                                                                                                |
| **Operational complexity**                    | Low. One tenant, one API resource, one application client. No infrastructure to run.                                                                                                                                                                                                                                                                                       | Low. Comparable.                                                                                            | Low _if_ the SDK is adopted wholesale; medium if raw OIDC is forced to preserve the existing BFF seam.                                                                                                                                               | Medium. User pool + app client + Hosted-UI domain + the custom validator + the `us-east-1` certificate hoop.                                                                                                        |
| **Vendor lock-in**                            | Low–moderate. Standard OIDC artifacts port to any other IdP; `sub` is the only thing MRROrigin persists.                                                                                                                                                                                                                                                                   | Low–moderate, same reasoning.                                                                               | Higher. SDK and session-model coupling run deeper than a plain code exchange.                                                                                                                                                                        | Low–moderate technically, but the non-standard access-token shape is itself a portability tax paid by every future consumer of that token.                                                                          |
| **Scaling path after beta**                   | Excellent; enterprise features available without re-platforming.                                                                                                                                                                                                                                                                                                           | Excellent, and the strongest option _specifically_ if MRROrigin later needs enterprise SSO/SCIM.            | Good, if staying on Clerk's model.                                                                                                                                                                                                                   | Good if fully AWS-committed.                                                                                                                                                                                        |

### Why the ranking lands where it does

The decisive filter is §1.1: `apps/api` has **no** custom validator code, and both issue #103 and
`docs/security/private-beta-checklist.md` item 14 assert that provider selection is "a configuration
choice, not a code change." Only providers that emit a standards-shaped JWT with a real `aud`
preserve that. **Cognito fails this outright** and is not recommended despite its CloudTrail and
AWS-consistency advantages — those advantages are real but narrow, and they are not worth writing
and maintaining custom token-validation code in the one part of the system where a subtle bug is a
cross-tenant security bug.

**Clerk** is excluded on a different axis: it is an excellent product whose idiomatic integration
would replace a session seam this codebase has already built and tested. Adopting it means either
fighting the SDK to preserve `lib/auth/session.ts` and the proxy, or discarding working code. Neither
is justified when standards-compliant alternatives integrate additively.

---

## 3. Recommended provider: **Auth0**

Auth0 is recommended because it is the option that requires **zero code change in `apps/api`, zero
vendor SDK in `apps/web`, and zero deviation from standard OIDC**:

1. It emits RS256 JWTs with a real `aud` when `/authorize` is called with the `audience` parameter,
   so `OIDC_ISSUER_URI` / `OIDC_JWK_SET_URI` / `OIDC_AUDIENCE` become the only API-side change —
   exactly the config-only premise item 14 already records.
2. Its authorization-code + PKCE flow is plain HTTP, so `apps/web` keeps its existing server-only
   session module and same-origin proxy and gains routes rather than a framework.
3. It has by far the deepest track record for "Spring Boot resource server + Next.js BFF," which is
   the lowest-risk position for a team with no dedicated auth or security engineer.
4. It has real RP-initiated logout, which is what makes #103's "logout requires re-authentication"
   check pass rather than merely appear to pass.

**WorkOS AuthKit is a close and legitimate second**, on essentially identical integration shape and
cost, with a better long-term position _if_ MRROrigin later sells to companies requiring enterprise
SSO/SCIM. That is plausible given the B2B-founder buyer profile but is **not** committed product
roadmap and this memo does not treat it as one. If enterprise SSO becomes a concrete pre-launch
requirement, reopen this comparison before implementation — after implementation the switching cost
is small but non-zero.

Two Auth0-specific traps, recorded now so they are not rediscovered mid-implementation:

- **The `audience` parameter is mandatory on `/authorize`.** Omit it and Auth0 issues an opaque
  token, and the resource server rejects every request with an error that looks like a JWKS problem.
- **The issuer's trailing slash is significant.** `https://tenant.region.auth0.com/` ≠
  `https://tenant.region.auth0.com`. A mismatch produces silent 401s.

---

## 4. Recommended session topology: **B — BFF-owned Authorization Code + PKCE**

Topology A (browser holds tokens) is rejected. It would require exposing access and refresh tokens to
browser JavaScript, which directly violates issue #103's scope item "confirm access, refresh, and ID
tokens are never exposed in client-readable browser storage" and
`docs/private-beta/pre-beta-smoke-test.md` §2. It would also mean discarding the server-only session
module and proxy that already exist. There is no argument for it here.

**Topology B is recommended, and it is an extension of what exists — not a rewrite.** The proxy
(`app/api/proxy/[...path]/route.ts`), the server-only session module, and the 401-expiry handling in
`app/app/layout.tsx` all stay as they are. What #103's implementation adds:

1. `GET /auth/login` — generate `state`, `nonce`, and a PKCE `code_verifier`; store them in a
   short-lived `httpOnly` cookie; redirect to Auth0 `/authorize` **with the `audience` parameter**
   and `scope=openid profile offline_access`.
2. `GET /auth/callback` — validate `state`, exchange `code` + `code_verifier` for tokens server-side
   using the client secret, validate the ID token's `nonce`, and write the session cookie.
3. **Encrypt the session cookie contents.** Today `mrr_session` holds a raw access token. It should
   hold an encrypted (JWE) bundle: access token, refresh token, ID-token claims, and expiry. The
   browser continues to see only an opaque `httpOnly` value; the change protects the contents from
   anything that gets a copy of the cookie at rest. This introduces a **new secret** — see §6.
4. **Silent server-side refresh.** Before proxying, check expiry; if expired, redeem the refresh
   token server-side and rewrite the cookie. No Redis or server-side session store — `AGENTS.md`
   forbids adding one pre-beta, and the recommended design does not need one; the web tier stays
   stateless.
5. **Real logout.** Clear the cookie **and** redirect through Auth0's RP-initiated logout endpoint so
   the provider's own SSO session ends. Today's cookie-delete-only logout would leave a user silently
   re-authenticated on the next login attempt — which would pass a naive smoke-test check while
   failing its intent.
6. **Delete or hard-gate the token-paste sign-in path.** `app/auth/sign-in/page.tsx` and
   `app/auth/session/route.ts` must not be reachable in the beta environment. A pasted-token endpoint
   that accepts any bearer string is an authentication bypass if it survives to a deployed
   environment.

**One sizing risk to design for:** an encrypted bundle containing an Auth0 access token, a refresh
token, and ID-token claims can approach or exceed the ~4KB per-cookie browser limit. Decide up front
between (a) storing only the refresh token plus minimal claims and re-minting access tokens on
demand, or (b) cookie chunking. Discovering this at integration time is a predictable and avoidable
delay.

`SameSite=Lax` remains correct for this design: the OIDC callback is a top-level GET navigation, which
`Lax` permits. `Strict` would break the callback. This should be recorded as a deliberate choice
rather than an inherited default when the ADR is written.

---

## 5. Does the API remain a resource server? **Yes — unchanged, and this is a hard boundary.**

`SecurityConfiguration.java` requires **zero code change**. `apps/api` never becomes an OAuth client,
never receives a client secret, never issues or stores a session, and never learns which provider was
chosen beyond three environment values:

| Variable           | Beta value                                                  | Sensitivity |
| ------------------ | ----------------------------------------------------------- | ----------- |
| `OIDC_ISSUER_URI`  | `https://<tenant>.<region>.auth0.com/` (trailing slash)     | Non-secret  |
| `OIDC_JWK_SET_URI` | `https://<tenant>.<region>.auth0.com/.well-known/jwks.json` | Non-secret  |
| `OIDC_AUDIENCE`    | the chosen Auth0 API identifier                             | Non-secret  |

None of these are secrets, so **none belongs in ADR-0012's AWS Secrets Manager mappings.** They are
ordinary Render environment variables. Adding them to `required-target-properties` would be a
category error and would fail startup for no security benefit.

The OAuth client role lives entirely in `apps/web`. That separation is what keeps this decision a
configuration change on the API side.

---

## 6. Secrets required, and how they map to #102 / ADR-0012

The recommended topology introduces **two new secrets, both in the web tier** — one more than the
earlier memo identified.

| Secret                                                   | Tier       | Purpose                                                              | Covered by ADR-0012's merged mechanism today? |
| -------------------------------------------------------- | ---------- | -------------------------------------------------------------------- | --------------------------------------------- |
| `AUTH0_CLIENT_SECRET`                                    | `apps/web` | Server-side authorization-code exchange and refresh-token redemption | **No**                                        |
| Session-cookie encryption key (JWE)                      | `apps/web` | Encrypts the session bundle at rest in the cookie (§4.3)             | **No**                                        |
| `AUTH0_CLIENT_ID`                                        | `apps/web` | Public client identifier                                             | N/A — not a secret                            |
| `OIDC_ISSUER_URI` / `OIDC_JWK_SET_URI` / `OIDC_AUDIENCE` | `apps/api` | Resource-server validation                                           | N/A — not secrets (§5)                        |

### The structural gap, now confirmed rather than predicted

ADR-0012 anticipated the client secret by name: _"this ADR's mechanism … will cover that secret the
same way once #103 introduces a concrete property for it."_ Re-verifying that against the merged
implementation shows it does not hold as written, because of **where** the secret lives:

- The mechanism is `AwsSecretsManagerEnvironmentPostProcessor` + `SecretResolver` under
  `apps/api/src/main/java/com/mrrorigin/platform/secrets/` — a Spring Boot `EnvironmentPostProcessor`.
  It runs only inside the JVM, only in `apps/api`.
- The AWS SDK dependencies (`software.amazon.awssdk:secretsmanager`, `:sts`) exist only in
  `apps/api/pom.xml`.
- `apps/web` is a separate Node runtime whose entire dependency set is `next`, `react`, `react-dom`,
  `server-only`. It has no AWS SDK, no secret-resolution path, and no Render-OIDC-to-STS bootstrap.

ADR-0012's mechanism is generic **in its mapping contract** but not **in its runtime**. Both new
secrets are web-tier, so neither can be resolved by the merged code. This is real, non-trivial scope
that sits _between_ "select a provider" and "wire up OIDC," and it must be visible before
implementation starts.

### Recommended resolution (not implemented, for approval)

Build a small Node-side mirror of ADR-0012 in `apps/web` that reproduces the same contract, rather
than weakening the contract for the web tier:

- A dedicated IAM role for the Render **web** service, trusting Render's OIDC provider, scoped by the
  token's `sub` claim to that one service/environment — the same pattern
  `docs/security/aws-secrets-manager-setup.md` documents for the API service, applied a second time.
  Render issues the OIDC token per service, so this requires a second role and a second trust policy,
  not a shared one.
- `@aws-sdk/client-secrets-manager` plus web-identity credential resolution (`AWS_ROLE_ARN` /
  `AWS_WEB_IDENTITY_TOKEN_FILE`, set automatically by Render). **No static AWS access key**, matching
  ADR-0012.
- Resolution **at server startup only**, cached in process memory for the instance lifetime — matching
  ADR-0012's explicit rejection of per-request Secrets Manager calls and its rotation model
  ("update the value in AWS, then redeploy").
- **Fail closed**, with **no plaintext fallback**: if a declared-required secret cannot be resolved,
  the web server does not start. Reproduce `SecretResolver`'s required-target-properties check so a
  forgotten mapping fails startup instead of silently resolving from a stray environment variable.
- KMS: reuse the existing CMK with the web role granted `kms:Decrypt` on it, or provision a second
  CMK. Reusing is simpler and adequate at beta scale; this is an owner call (§8).

`docs/security/rotation-runbook.md` and `docs/security/aws-secrets-manager-setup.md` will both need a
web-tier section once this lands. Both are out of scope for this memo.

### Whether this can be deferred

There is one legitimate sequencing lever, and it should be a conscious choice rather than a default:
Auth0 supports registering the BFF as a **public client using PKCE only**, with no client secret. PKCE
protects the code exchange without client authentication, so this would eliminate `AUTH0_CLIENT_SECRET`
entirely — but **not** the session-encryption key, so it does not remove the need for a web-tier
secret path; it only reduces it from two secrets to one.

**Recommendation: do not take this lever.** A BFF is a confidential client that can genuinely keep a
secret, and standard guidance is that such clients should authenticate. Registering it as public to
avoid infrastructure work trades a permanent security posture reduction for a one-time schedule
saving, and the web-tier secret path still has to be built for the encryption key. Recorded here
because it is a real option the owner is entitled to weigh, not because it is advised.

---

## 7. Estimated beta cost

| Line item                                   | Monthly                                                                                                                                                |
| ------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Auth0, 5–10 founders                        | **$0** (free tier ceiling is far above beta scale; ⚠ verify current tier limits at signup)                                                            |
| Auth0 custom domain                         | $0 if deferred; paid-tier upgrade if adopted now. **Recommend deferring** — cosmetic, and neither the smoke test nor any correctness check requires it |
| AWS Secrets Manager, 2 new web-tier secrets | ~$0.80 (~$0.40/secret/month)                                                                                                                           |
| AWS KMS                                     | $0 incremental if the existing ADR-0012 CMK is reused; ~$1 for a second CMK                                                                            |
| **Total incremental**                       | **~$1–2/month**, on top of #102's and #104's already-approved costs                                                                                    |

Cost is not a differentiator among the four providers at beta scale — all four are effectively free
for 5–10 users. It should carry no weight in this decision.

---

## 8. Owner decisions required before implementation

Blocking — implementation of #103 cannot start until each is answered:

1. **Approve Auth0** as the production OIDC provider, or name a different pick. (If enterprise
   SSO/SCIM is a concrete pre-launch requirement, say so now — that is the one input that would move
   the recommendation to WorkOS AuthKit.)
2. **Approve Topology B**, extending the existing `apps/web` BFF seam rather than rewriting it.
3. **Approve the web-tier secret path (§6)** — a second Render service IAM role, a Node-side
   AWS Secrets Manager resolver in `apps/web` mirroring ADR-0012's fail-closed, no-plaintext-fallback
   contract. This is new scope beyond "select a provider" and is the single largest item this memo
   surfaces. Confirm whether it ships inside #103 or as its own issue.
4. **Reuse the existing ADR-0012 CMK for the web tier, or provision a second CMK?**
5. **Confirm both new secrets are in ADR-0012's scope**, i.e. neither may live as a Render-native
   environment variable. ADR-0012 rejected Render-native storage for every secret in #102's scope;
   this memo assumes that holds for web-tier secrets too, but the ADR's text was written when only
   API-tier secrets existed.
6. **Confidential client (recommended) or public client + PKCE?** See §6's sequencing lever.
7. **MFA policy for beta** — enable, or defer. Optional for a 5–10 founder beta; the owner's call.
8. **Custom Auth0 domain now, or deferred?** Recommend deferred.

Owner actions in external systems, once the above are approved (none performed by this memo):

9. Create the Auth0 tenant; create the **API** resource whose identifier becomes `OIDC_AUDIENCE`;
   create the **Regular Web Application** client, yielding `AUTH0_CLIENT_ID` and
   `AUTH0_CLIENT_SECRET`.
10. Register the real callback and logout URLs — **blocked on #104's region/domain choice**, which
    this memo does not make. Until the beta hostname exists, these are `https://<beta-web-host>/auth/callback`
    and `https://<beta-web-host>/auth/sign-in`.
11. Confirm an **ADR will be written** once the above are approved (issue #103 scope item 1). This
    memo deliberately does not create one.

## Sequencing note

#103's beta-environment verification checks — issuer/audience enforcement, cookie policy, logout,
and the wrong-issuer/wrong-audience rejection cases — cannot be discharged in CI (§1.4). They require
the deployed Render beta environment, which also gates #102's outstanding verification. Neither #102
nor #103 closes on merge; both close on evidence from the live beta environment. This memo does not
change that sequencing and does not close either issue.

## References

- #103, #104, #102, #29 (frozen protocol — untouched)
- ADR-0012 (`docs/adr/0012-production-secret-storage-aws-secrets-manager.md`)
- `docs/security/private-beta-checklist.md` item 14
- `docs/security/aws-secrets-manager-setup.md`
- `docs/local-authentication.md`
- `docs/private-beta/pre-beta-smoke-test.md` — Preconditions, §2 "Authentication and tenancy"
- `ARCHITECTURE.md` — Security baseline, Deferred decisions
- `apps/api/src/main/java/com/mrrorigin/identity/SecurityConfiguration.java`
- `apps/api/src/main/resources/application.yml`
- `apps/web/lib/auth/session.ts`, `apps/web/app/api/proxy/[...path]/route.ts`
