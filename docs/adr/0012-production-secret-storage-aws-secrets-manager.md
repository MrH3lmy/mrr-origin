# ADR-0012: Production secret storage — AWS Secrets Manager + KMS via Render OIDC workload identity

- Status: Accepted
- Date: 2026-08-25

## Context

`docs/security/private-beta-checklist.md` item 13 ("KMS/secrets-manager selected for production
secrets") was open. All secrets (Stripe platform/webhook keys, database credentials, email-provider
credentials, and any future OIDC client secret) currently live only as plaintext process environment
variables, per `docs/security/rotation-runbook.md`'s interim posture and the deferred decision noted
in ADR-0003 §"Platform secret key and webhook secrets."

Issue #102 required, without an agent silently choosing a vendor: encryption at rest, least-privilege
runtime access, no admin UI or human operator able to view raw secret values, auditability, a
documented rotation procedure, and beta-environment verification.

Two decision memos preceded this ADR:

1. **#102's own memo** found no hosting/cloud platform selected anywhere in the repository at the
   time, and could not recommend a specific KMS vendor without that hosting decision first.
2. **#104** (hosting) subsequently selected **Render** as the production hosting platform for the API,
   web app, and managed PostgreSQL, and recommended Render-native environment groups as #102's
   secret store on the assumption that hosting choice mechanically determined the secret-store choice.
3. **A follow-up validation memo on #104** checked that assumption against current Render
   documentation and found it does not hold: Render's own documented ceiling for "Protected
   Environments" is _"only Admin team members can view the values of environment variables and
   secret files"_ — Admins remain able to view raw production secret values by design, with no Render
   setting to remove that. This directly fails #102's literal requirement that _no_ human operator can
   view raw values.

The validation memo also confirmed, against current Render documentation, that Render supports
federating a service's identity to AWS IAM via OIDC (`render.com/docs/oidc`): Render can be
registered as an AWS IAM OIDC identity provider, a service is granted a short-lived, automatically
rotated identity token, and AWS STS exchanges that token for temporary credentials scoped to one
IAM role — with the role restrictable to one specific Render service/environment via the token's
`sub` claim. No static, long-lived AWS access key is ever stored on Render.

## Decision

**Select AWS Secrets Manager (secret storage) + AWS KMS (envelope encryption) as the production
secret store, accessed exclusively via Render's OIDC-federated workload identity — never a static AWS
access key, and never Render-native environment groups/secret files as the authoritative store for
any secret in #102's scope.**

Render remains the hosting platform (#104, unchanged by this ADR). Only the secret-storage layer
changes from what #104's memo originally assumed.

### Rejected alternative: Render-native environment groups

Rejected because it fails #102's explicit, non-negotiable requirement — verified against current
Render documentation, not assumed — that no admin UI or human operator can view raw secret values.
Render's own "Protected Environments" feature caps this at "Admin-only," never "no human." The owner
explicitly declined to weaken #102's requirement to fit that ceiling.

### Why AWS Secrets Manager + KMS specifically (not GCP/Azure)

Render's OIDC federation, as currently documented, targets AWS IAM specifically (`oidc.render.com`
registered as an AWS identity provider trusted via `AssumeRoleWithWebIdentity`). This ADR does not
reopen the full multi-cloud comparison already completed in #104's memo; AWS is the only one of the
three hyperscalers with a currently documented, ready-to-use Render-native OIDC bridge, which is the
deciding factor once Render-native secrets were ruled out.

## Architecture

```
Render web service (API)
  -> Render-issued short-lived OIDC token (auto-rotated, injected as AWS_WEB_IDENTITY_TOKEN_FILE)
  -> AWS STS AssumeRoleWithWebIdentity
  -> temporary credentials for one dedicated IAM role, scoped to this Render service/environment
  -> AWS Secrets Manager GetSecretValue (least-privilege, per-secret IAM policy)
  -> KMS Decrypt (the CMK protecting those secrets only)
```

- **No static AWS access keys** are configured on Render or anywhere in this repository. Credentials
  are obtained exclusively through the AWS SDK's standard web-identity credential resolution
  (`DefaultCredentialsProvider`, which resolves `AWS_ROLE_ARN`/`AWS_WEB_IDENTITY_TOKEN_FILE` — both
  set automatically by Render once `AWS_ROLE_ARN` is configured on the service per
  `docs/security/aws-secrets-manager-setup.md`).
- **Only the Render API runtime's federated IAM role** may call `secretsmanager:GetSecretValue`
  against the application's secrets, and `kms:Decrypt` against the CMK protecting them. Human/operator
  IAM principals (console users, engineers' own IAM identities) are never granted these permissions —
  see `docs/security/aws-secrets-manager-setup.md` for the explicit deny/no-grant posture and example
  least-privilege policies (placeholders only; no real account ID, ARN, or role name is committed to
  this repository).
- **CloudTrail is the audit source of record** for secret access (`GetSecretValue`, `Decrypt` calls),
  independent of and in addition to Render's own audit log.
- **Secret resolution happens at two boundaries only: application startup, and operator-driven
  rotation** (redeploy/restart after updating a secret's value in AWS Secrets Manager). A running
  instance never calls Secrets Manager on a per-request basis — resolved values are cached in process
  memory for the life of the instance, matching ADR-0003 §"Secret store/KMS unavailability"'s existing
  fail-closed model and avoiding manufacturing an availability dependency on Secrets Manager for
  already-running, healthy instances.
- **Fail closed at startup:** if any secret configured for AWS resolution cannot be resolved (access
  denied, not found, empty value, or any AWS SDK failure), the application fails to start. There is no
  partial startup and no fallback to a plaintext/default value. See
  `apps/api/src/main/java/com/mrrorigin/platform/secrets/AwsSecretsManagerEnvironmentPostProcessor.java`.
- **No plaintext fallback path exists in code.** The mechanism either resolves every configured secret
  from AWS Secrets Manager or the application does not start; it does not silently fall back to an
  environment variable of the same name if AWS resolution is enabled.
- **Local development is unaffected.** The mechanism is inert unless
  `MRRORIGIN_SECRETS_PROVIDER=aws-secrets-manager` is set, which local development and CI never set —
  `application.yml`'s existing `${SOME_ENV_VAR:}}`-style local defaults continue to work exactly as
  before.

## Implementation contract

- The mapping from a configuration property (e.g. `STRIPE_CONNECT_LIVE_SECRET_KEY`) to an AWS Secrets
  Manager secret ID/ARN is **deployment-owned configuration, not application code**. No AWS account
  ID, secret ARN, region, or secret value is hardcoded anywhere in this repository — see
  `apps/api/src/main/resources/application.yml`'s `mrrorigin.secrets` block and
  `docs/security/aws-secrets-manager-setup.md` for the full contract and the realistic target-property
  list (Stripe platform secret key, Stripe webhook signing secrets, database credentials, and
  email-provider credentials — the concrete secret classes that already exist as named configuration
  properties in this codebase today).
- The application currently has **no OIDC client-secret property** (the API is a pure OAuth2 resource
  server validating JWTs — `spring.security.oauth2.resourceserver.jwt.*` — with no confidential client
  credential of its own). #102 named "OIDC client secret" as a secret class to cover; this ADR's
  mechanism is generic (an arbitrary `{target-property, secret-id}` mapping) and will cover that
  secret the same way once #103 introduces a concrete property for it. Inventing a property name for a
  secret that does not yet exist in this codebase would violate #102's own "do not invent credentials
  or secret names" instruction, so none is added here.
- Resolved values are injected into the Spring `Environment` as a property source at the highest
  precedence, using the **same property names** the existing `${STRIPE_CONNECT_LIVE_SECRET_KEY:}`-style
  placeholders in `application.yml` already reference. Every existing configuration consumer
  (`StripeConnectProperties`, `EmailProperties`, the datasource configuration, etc.) requires **zero
  code changes** — this ADR only changes where those values come from, not how they are consumed.

## Consequences

- Beta and production deployments must provision AWS Secrets Manager entries, a KMS key, an IAM role
  trusting Render's OIDC provider, and least-privilege policies before the beta environment can start
  with `MRRORIGIN_SECRETS_PROVIDER=aws-secrets-manager` — this is external AWS/Render configuration
  work tracked as part of #102's remaining beta-environment-verification acceptance criteria, not
  satisfied by this ADR or the accompanying code alone.
- This introduces AWS as a dependency independent of the Render hosting choice (#104) — an explicit,
  deliberate exception to defaulting everything to one vendor, made necessary by Render's documented
  inability to deny Admin secret-value visibility. `docs/security/aws-secrets-manager-setup.md`
  documents the exact external setup required.
- Rotation for every secret class now follows a two-step pattern: update the value in AWS Secrets
  Manager, then redeploy/restart the Render service so the next startup resolution picks it up (see
  the updated `docs/security/rotation-runbook.md`). There is no live re-fetch without a restart.
- `docs/security/private-beta-checklist.md` item 13 remains `❌ Open` until the beta-environment
  verification evidence described in #102 exists (AWS resources provisioned, Render OIDC federation
  configured and exercised, CloudTrail evidence captured, fail-closed startup exercised against the
  real beta environment) — implementation merging is necessary but not sufficient to close #102.

## References

- #102 (production secret storage/KMS — requirements and both decision memos)
- #104 (hosting decision; Render selected; its memo's secret-store assumption superseded by this ADR)
- ADR-0003 (Stripe platform-key/webhook-secret handling, rotation procedure, and fail-closed model this
  ADR generalizes to every secret class)
- `docs/security/private-beta-checklist.md` item 13
- `docs/security/rotation-runbook.md`
- `docs/security/aws-secrets-manager-setup.md` (external AWS/Render setup, this ADR's companion doc)
- [Render: Managed Auth with OpenID Connect](https://render.com/docs/oidc)
- [Render: Environment Variables and Secrets](https://render.com/docs/configure-environment-variables)
