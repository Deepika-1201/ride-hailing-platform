# ADR-014: Thin payments: a provider interface, a realistic mock, charge after the trip

- **Status:** Accepted
- **Date:** 2026-10-02
- **Related:** [Ride lifecycle](../ride-lifecycle.md) §5–§6; requirements Q7, FR-PY1–FR-PY6, FR-R4, C-2; [ADR-009](ADR-009-idempotency-and-identifiers.md)

## Context

- Riders pay after the trip, online or in cash (Q7). Unpaid amounts become dues that block the next booking (FR-R4).
- Financial correctness in depth (ledgers, reconciliation, settlement, disputes) belongs to the Payment Orchestrator project. Here payments stay thin (C-2), with no runtime dependency on that project.
- The provider is outside our transactions: timeouts mean "unknown", and webhooks can be duplicated, delayed or arrive before the API response.

## Problem

How much payment machinery does this project build, and how does it stay correct with an unreliable provider?

## Options considered

| Option | Pros | Cons |
|---|---|---|
| **A. `PaymentProvider` interface with a realistic mock** | Exercises unknown outcomes, webhooks and idempotency without external accounts | No real money flows |
| B. Integrate the Payment Orchestrator | A cross-project demo | A runtime dependency and a second stack on the 8 GB laptop; it is E-commerce's integration in the portfolio plan |
| C. Razorpay or Stripe test mode directly | Real provider behaviour | Accounts and keys; repeats work done in the Payment Orchestrator |

## Decision

1. **`PaymentProvider` interface:** `charge`, `refund`, `status`, and webhook verification. Two implementations:
   - `MockPaymentProvider`: configurable latency, decline rate, timeout rate, late success, and duplicate or out-of-order signed webhooks;
   - cash, which needs no provider call.
2. **Model ([ride lifecycle §6](../ride-lifecycle.md#6-charges-and-refunds)):**
   - one charge per ride and purpose (`FARE`, `CANCELLATION_FEE`, `NO_SHOW_FEE`);
   - each provider call is an attempt keyed by the attempt ID;
   - refunds are separate records;
   - rider dues are failed charges.
3. **Charging** happens in the `worker` role when it consumes `TripCompleted` or a `RideCancelled` that carries a fee, never in the request path.
4. **Unknown outcomes** are resolved by status checks (10 s, 30 s, 2 min, 10 min, then hourly for 24 h) and webhooks, never by a new attempt. After 24 h unresolved, they go to operations.
5. **Webhooks** are verified (HMAC), deduplicated by provider event ID, and stored raw before processing.
6. **Driver earnings** are a read model built from completed rides and charges: fare, commission, net, and cash collected. There is no double-entry ledger.
7. **Out of scope:** reconciliation, settlement, payouts, disputes and stored card data. The Payment Orchestrator covers them.

## Trade-offs

- No ledger means earnings are a derived view, not an accounting record; fine for a simulation, not for real money.
- A mock can only be as realistic as its configured behaviours.

## Consequences

- Payment failures never block or undo rides; they become dues.
- A `PaymentOrchestratorProvider` adapter could be added later without touching the ride or payment model.

## Revisit when

- Real money is involved: add a ledger and reconciliation, or delegate to the Payment Orchestrator.
