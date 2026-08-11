# Local authentication

MRROrigin's management API is an OAuth 2.0 resource server. It accepts signed JWT access tokens and uses the validated `sub` claim as the external identity-provider subject. Workspace authorization comes from `workspace_members`; workspace IDs or roles supplied by a client are never treated as authorization.

## Configuration

Set these variables for the local OIDC provider:

| Variable           | Default                                                                 | Purpose                        |
| ------------------ | ----------------------------------------------------------------------- | ------------------------------ |
| `OIDC_ISSUER_URI`  | `http://localhost:8081/realms/mrr-origin`                               | Required JWT issuer            |
| `OIDC_JWK_SET_URI` | `http://localhost:8081/realms/mrr-origin/protocol/openid-connect/certs` | Signature-verification keys    |
| `OIDC_AUDIENCE`    | `mrr-origin-api`                                                        | Required access-token audience |

The issuer and JWK endpoint are configurable so Keycloak, Auth0, Clerk, or another standards-compliant provider can be selected later without changing workspace authorization. Production-provider selection remains a deferred architecture decision.

## Test identities

Integration tests use Spring Security's JWT request test support with these deterministic subjects:

| Subject      | Purpose                                            |
| ------------ | -------------------------------------------------- |
| `user-alice` | Workspace owner and normal management happy path   |
| `user-bob`   | Cross-workspace denial and invited-member behavior |

These are external subject identifiers, not application passwords or seeded production accounts. Tests create the corresponding `workspace_members` rows through the management API.

## Calling the API

Obtain an access token from the configured local provider with:

- issuer equal to `OIDC_ISSUER_URI`;
- audience containing `OIDC_AUDIENCE`; and
- a stable, non-empty `sub` claim.

Then call the API with `Authorization: Bearer <access-token>`. Creating a workspace automatically records the authenticated subject as its `OWNER`. Subsequent workspace, member, and project operations re-check that subject's membership in PostgreSQL on every request.
