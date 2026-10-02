# ADR-024: Endpoints declare who may call them; access is denied by default

- **Status:** Accepted
- **Date:** 2026-10-02
- **Related:** [LLD](../low-level-design.md) §12.3, §12.4; [ADR-015](ADR-015-identity.md), [ADR-019](ADR-019-module-layout-and-boundaries.md)

## Context

- Every endpoint is open to some of four user roles (RIDER, DRIVER, OPS, ADMIN) or to anyone (sign-in, webhooks), per the table in LLD §12.4. About 70 endpoints arrive over phases 4 to 11, written in ten modules.
- Ownership (a rider sees only their rides) is checked in application services (FR-I2); this decision is about roles.
- Unknown paths must still answer `404 NOT_FOUND` without a token, and every error is a problem detail with a stable `code` (LLD §13).
- Spring Security's resource server validates bearer tokens (ADR-015).

## Problem

Where is each endpoint's role rule written, and what makes a forgotten one fail safe?

## Options considered

| Option | Pros | Cons |
|---|---|---|
| A. Path rules in one `SecurityFilterChain` | One place shows every rule | The platform module would list every module's paths; a new endpoint is open or closed depending on a pattern elsewhere; unknown paths answer `401`, not `404` |
| B. `@PreAuthorize("hasRole('RIDER')")` with method security | Standard Spring; next to the handler | Expressions are strings, checked only at run time; a forgotten annotation leaves the endpoint open to any signed-in user; its exceptions need mapping to our problem codes |
| **C. `@AllowedRoles(…)` or `@PublicEndpoint` on every handler, an interceptor that enforces it, and an ArchUnit rule that requires it** | Type-safe enum roles next to the handler; a missing declaration fails the build, and at run time refuses everyone; the interceptor raises our own `401` and `403` problems; the filter chain stays generic | A small custom mechanism to maintain; test controllers can skip the declaration (the rule checks production code only), which also lets a test prove the run-time default |

## Decision

1. Every handler of an `@ApiController` declares `@AllowedRoles(…)` or `@PublicEndpoint`, on the method or its class; the method's declaration wins.
2. Spring Security only authenticates: a request with an invalid or expired bearer token gets `401 UNAUTHENTICATED`; every other request reaches the dispatcher. Bearer tokens are ignored on `/v1/auth/**`.
3. An interceptor checks the declaration: no valid token gives `401 UNAUTHENTICATED` with `WWW-Authenticate: Bearer`, a token without an allowed role gives `403 FORBIDDEN`, and a handler without a declaration refuses everyone.
4. An ArchUnit rule fails the build when a production handler declares neither annotation.
5. Controllers receive the caller as a `Caller` parameter and pass it to application services for ownership checks, which answer `404` for what the caller may not see.

## Trade-offs

- The role table now lives in annotations across modules instead of one file. The LLD §12.4 table stays the specification, and each endpoint's tests check it (a wrong role and a wrong owner are refused).
- A custom interceptor instead of Spring's method security: about 60 lines, and its behaviour is pinned by tests and mutation checks.

## Consequences

- A new endpoint can't be shipped open by accident: forgetting the declaration breaks the build, and even without the rule it would refuse everyone.
- Role checks happen before the handler, so `@Valid` body validation errors are never shown to callers who may not call the endpoint.
- Phase 3's `AccessTests` cover the matrix for sample endpoints; each module's endpoint tests reuse `TestUsers` for real tokens.

## Revisit when

- Rules need more than roles (for example per-city operations staff): then an authorization service with attributes, not more annotations.
