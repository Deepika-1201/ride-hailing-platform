# ADR-015: In-house phone sign-in with short-lived JWTs

- **Status:** Accepted
- **Date:** 2026-10-02
- **Related:** [HLD](../architecture.md) §10, §14; requirements FR-I1, FR-I2, NFR-9; [ADR-006](ADR-006-realtime-transport.md)

## Context

- Riders and drivers sign in with a phone number and a one-time code (FR-I1), as ride-hailing apps in India do. Operations and admins need role-based access (FR-I2).
- All users are simulated; SMS is mocked. Local development must run without external identity services on an 8 GB laptop.
- WebSocket handshakes from browsers can't carry an `Authorization` header (ADR-006).
- The Payment Orchestrator issues and verifies its own tokens with nimbus-jose-jwt and Spring Security.

## Problem

Who issues identities and tokens, and how are they checked?

## Options considered

| Option | Pros | Cons |
|---|---|---|
| **A. A small `identity` module: one-time code → JWTs, Spring Security resource server** | No external dependency; runs locally; teaches the token lifecycle; same libraries as the Payment Orchestrator | Security-sensitive code to own; no SSO or MFA |
| B. Keycloak | Full-featured, open source | ~1 GB of memory locally; phone OTP needs extensions |
| C. Amazon Cognito | Managed; supports SMS one-time codes | Cloud-only, so local development differs; cost per SMS |

## Decision

1. **Sign-in:** `POST /v1/auth/otp` sends a 6-digit code through the `NotificationProvider` (mock SMS; a fixed code in local and test environments). `POST /v1/auth/token` exchanges phone + code for tokens. Codes expire after 5 min, allow 5 attempts, and are rate-limited per phone and per IP **(assumed)**.
2. **Tokens:**
   - access tokens are JWTs signed with ES256, valid 15 min, carrying subject, roles and token ID;
   - refresh tokens are opaque, rotated on every use, stored only as hashes, valid 30 days;
   - reuse of a rotated refresh token revokes the whole token family.
3. **Verification:** every role is an OAuth2 resource server that validates JWTs against the module's JWKS. Keys live in Secrets Manager in AWS and are generated per environment locally.
4. **Authorization:** roles `RIDER`, `DRIVER`, `OPS`, `ADMIN`; ownership checks in application services, not only in controllers.
5. **WebSockets:** `POST /v1/realtime/tickets` returns a single-use ticket valid for 60 s, stored in Valkey, which the client passes when connecting. The connection then holds the authenticated identity.
6. **Operations and admin accounts** are seeded; SSO for staff is out of scope.

## Trade-offs

- Owning sign-in code means owning its security: code throttling, key rotation and token revocation are built and tested here.
- No MFA or SSO for staff accounts.

## Consequences

- Tests and the simulator can sign in thousands of users offline.
- Revocation takes effect within the 15-minute access-token lifetime, or at once for refresh.

## Revisit when

- Real users sign in: move to Cognito or Keycloak and keep the resource-server side unchanged.
- Staff accounts need SSO and MFA.

## Amendments

- **2026-10-02, low-level design** ([LLD §12.2](../low-level-design.md#122-tokens), [review](../architecture-review.md) R-15): reuse of a rotated refresh token within 10 s of its rotation **(assumed)** is treated as a client retry whose response was lost. A new pair is issued and the pair from the first rotation is revoked. Later reuse still revokes the whole family.
- **2026-10-02, phase 3** ([LLD §12.1–§12.4](../low-level-design.md#12-identity-and-security), [ADR-024](ADR-024-endpoint-access.md)):
  - Tokens are verified against the configured public keys (EC P-256 JWKs in `ride.security.jwt.keys`), not a JWKS endpoint, because every role runs in the same deployable. In the `local` and `test` profiles, missing keys and a missing code secret are generated in memory; elsewhere startup fails, and a fixed sign-in code is refused.
  - Roles are checked per endpoint by `@AllowedRoles`/`@PublicEndpoint` declarations, denied by default (ADR-024).
  - Seeded staff accounts sign in like anyone else, with the local profile's fixed code.
