# AWS Secrets Manager + KMS setup (ADR-0012)

External AWS/Render configuration required to run the beta/production API with
`MRRORIGIN_SECRETS_PROVIDER=aws-secrets-manager`. Nothing in this document is committed as real
configuration anywhere in this repository — every identifier below is a placeholder. Do not put a
real AWS account ID, role ARN, secret ARN, or KMS key ID into this repository or into any GitHub
issue/PR.

This document is setup instructions only. It does not itself constitute the beta-environment
verification evidence #102 requires — that evidence (AWS resources actually provisioned, OIDC
federation actually exercised, CloudTrail entries actually observed) is recorded separately once the
beta environment exists.

## 1. Register Render as an AWS OIDC identity provider

In AWS IAM → Identity providers → Add provider:

- Provider type: OpenID Connect
- Provider URL: `https://oidc.render.com/<RENDER_WORKSPACE_ID>`
- Audience: `<RENDER_WORKSPACE_ID>` (per Render's OIDC documentation, the workspace ID doubles as the
  default audience value)

## 2. Create one dedicated IAM role, trusted only by the intended Render service

Do not reuse this role for any other Render service or environment. Example trust policy
(placeholders only):

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Federated": "arn:aws:iam::<AWS_ACCOUNT_ID>:oidc-provider/oidc.render.com/<RENDER_WORKSPACE_ID>"
      },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringEquals": {
          "oidc.render.com/<RENDER_WORKSPACE_ID>:aud": "<RENDER_WORKSPACE_ID>"
        },
        "StringLike": {
          "oidc.render.com/<RENDER_WORKSPACE_ID>:sub": "environment:<RENDER_ENVIRONMENT_ID>:service:<RENDER_SERVICE_ID>"
        }
      }
    }
  ]
}
```

The `sub` claim restriction is what scopes this role to exactly one Render service/environment —
never widen it to match every service in the workspace.

## 3. Least-privilege Secrets Manager policy

Grant `secretsmanager:GetSecretValue` only on the exact secret ARNs this deployment needs — never a
wildcard across all secrets in the account. Example (placeholders only):

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "secretsmanager:GetSecretValue",
      "Resource": [
        "arn:aws:secretsmanager:<AWS_REGION>:<AWS_ACCOUNT_ID>:secret:mrrorigin/beta/stripe-connect-live-secret-key-??????",
        "arn:aws:secretsmanager:<AWS_REGION>:<AWS_ACCOUNT_ID>:secret:mrrorigin/beta/stripe-connect-live-webhook-secret-??????",
        "arn:aws:secretsmanager:<AWS_REGION>:<AWS_ACCOUNT_ID>:secret:mrrorigin/beta/database-password-??????",
        "arn:aws:secretsmanager:<AWS_REGION>:<AWS_ACCOUNT_ID>:secret:mrrorigin/beta/postmark-server-token-??????"
      ]
    }
  ]
}
```

Do not grant `secretsmanager:ListSecrets`, `PutSecretValue`, `DeleteSecret`, or any action beyond
`GetSecretValue` to this role. Rotation (§6 below) is an operator action using the operator's own
credentials, never something the running application performs on itself.

## 4. KMS decrypt permission, scoped to the one CMK protecting these secrets

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "kms:Decrypt",
      "Resource": "arn:aws:kms:<AWS_REGION>:<AWS_ACCOUNT_ID>:key/<KMS_KEY_ID>"
    }
  ]
}
```

Use a dedicated CMK for these application secrets rather than the AWS-managed
`aws/secretsmanager` default key, so this policy can name it explicitly and no other workload in the
account implicitly gains decrypt access.

## 5. Explicit non-grant for human/operator principals

This is the requirement Render-native environment groups could not satisfy (see ADR-0012). Verify,
for every human/console IAM principal (individual engineer identities, any break-glass/admin role):

- No IAM policy attached to a human principal grants `secretsmanager:GetSecretValue` or `kms:Decrypt`
  on the ARNs above.
- If a broad administrative policy (e.g. `AdministratorAccess`) is attached to any human principal for
  unrelated reasons, add an explicit `Deny` statement on these specific secret/key ARNs to that
  principal's boundary, or move these secrets into a separate AWS account/role boundary that
  administrative access does not implicitly reach. Do not rely on "nobody happens to have a policy
  that grants this" as the control — verify no path exists, including via broad wildcard grants.
- Record which IAM principals were checked and the outcome as part of #102's beta-environment
  verification evidence — this document only specifies the control, not the evidence that it holds.

## 6. CloudTrail verification

- Confirm a CloudTrail trail is active in the account/region covering `secretsmanager:GetSecretValue`
  and `kms:Decrypt` as data/management events.
- After the beta API starts with `MRRORIGIN_SECRETS_PROVIDER=aws-secrets-manager`, confirm CloudTrail
  recorded exactly the expected `GetSecretValue`/`Decrypt` calls, attributed to the dedicated IAM
  role's assumed-role identity (not a human principal, not a static access key).
- This CloudTrail evidence, not this document, is what satisfies #102's auditability requirement in
  practice.

## 7. Render-side configuration

On the Render web service running the API:

- Set `AWS_ROLE_ARN` to the dedicated IAM role's ARN (§2). Render then automatically issues and
  rotates the OIDC token and sets `AWS_WEB_IDENTITY_TOKEN_FILE` — do not set that variable manually
  (per Render's own documentation, a manually-set value may not match the credentials Render manages).
- Set `MRRORIGIN_SECRETS_PROVIDER=aws-secrets-manager`.
- Set `MRRORIGIN_SECRETS_AWS_REGION` to the AWS region holding the secrets (§3–4).
- Set one `MRRORIGIN_SECRETS_AWS_MAPPINGS_<n>_TARGETPROPERTY` / `MRRORIGIN_SECRETS_AWS_MAPPINGS_<n>_SECRETID`
  pair per secret this deployment needs, using Spring Boot's standard indexed environment-variable
  list binding (see `apps/api/src/main/resources/application.yml`'s `mrrorigin.secrets` block for the
  exact contract). This repository requires this configuration to be entirely deployment-owned — no
  index, target property, or secret ID is hardcoded in source control.
- **Also set `MRRORIGIN_SECRETS_AWS_REQUIRED_TARGET_PROPERTIES`** (comma-separated target-property
  names) to every target property listed above that this deployment considers a mandatory production
  secret. This is not optional bookkeeping: it is what actually prevents a forgotten mapping from
  silently resolving from a stray plaintext environment variable instead of failing startup — see
  ADR-0012 §"Implementation contract". A target property intentionally left out of both this list and
  the mappings above (e.g. a test-mode Stripe secret on a live-only deployment) is treated as genuinely
  optional and will not block startup.

## Realistic target-property list

These are the secret classes #102 named, mapped to the configuration property keys that already
exist in `apps/api/src/main/resources/application.yml` today:

| #102 secret class             | Existing target property (`application.yml`)                                                                                                                                                                                                                                                                            |
| ----------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Stripe platform secret        | `STRIPE_CONNECT_LIVE_SECRET_KEY`, `STRIPE_CONNECT_TEST_SECRET_KEY`                                                                                                                                                                                                                                                      |
| Stripe webhook signing secret | `STRIPE_CONNECT_LIVE_WEBHOOK_SECRET`, `STRIPE_CONNECT_TEST_WEBHOOK_SECRET`                                                                                                                                                                                                                                              |
| Database credentials          | `DATABASE_USERNAME`, `DATABASE_PASSWORD`                                                                                                                                                                                                                                                                                |
| Email-provider credentials    | `POSTMARK_SERVER_TOKEN`                                                                                                                                                                                                                                                                                                 |
| OIDC client secret            | **No property exists yet.** The API is currently a pure OAuth2 resource server (JWT validation only, `spring.security.oauth2.resourceserver.jwt.*`) with no confidential client secret of its own. Once #103 introduces one, map it through this same mechanism — do not invent a property name ahead of that decision. |

Not every listed property must be present in every deployment's mapping list — only configure a
mapping for a property this specific deployment actually uses (e.g. a test-mode-only beta environment
may omit the live-mode Stripe entries).

## References

- ADR-0012
- #102
- [Render: Managed Auth with OpenID Connect](https://render.com/docs/oidc)
- `docs/security/rotation-runbook.md`
