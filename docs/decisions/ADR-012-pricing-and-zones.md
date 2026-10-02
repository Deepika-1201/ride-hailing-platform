# ADR-012: Upfront quotes, versioned fare rules and H3 zones

- **Status:** Accepted
- **Date:** 2026-10-02
- **Related:** [HLD](../architecture.md) §8; requirements Q4, FR-PR1–FR-PR4, §5; [ADR-003](ADR-003-postgresql-postgis.md)

## Context

- Phase 1 chose upfront pricing (Q4): the rider sees and accepts a price before booking, the fare doesn't depend on noisy GPS traces, and charging after the trip needs no estimate-then-adjust step.
- Fare rules differ per city and category and change over time. Surge depends on place and time, and can move between quoting and booking.
- Surge, demand and supply counts and dispatch partitioning all need a grid of zones within a city.

## Problem

1. How are prices fixed and kept consistent between quote and booking?
2. How do fare rules change without affecting existing quotes?
3. Which zones divide a city?

## Options considered

### Pricing

| Option | Pros | Cons |
|---|---|---|
| **A. Upfront, immutable quotes** | Price known before booking; surge locked in; simple payment | Route changes need a re-quote |
| B. Metered (distance and time from GPS) | Exact for the trip driven | Depends on GPS quality; final price unknown to the rider |
| C. Hybrid (upfront with adjustments) | Fair for detours | Adjustment rules and disputes |

### Zones

| Option | Pros | Cons |
|---|---|---|
| **A. H3 cells, resolution 7 (~5 km²), plus PostGIS polygons for special areas** | Hexagons: every neighbour is equidistant; uniform sizes; easy rings and neighbour aggregation; Uber built H3 for this | Cells don't follow roads or neighbourhoods; special areas need polygons |
| B. Geohash | Simple strings | Rectangles of uneven size; neighbours at different distances |
| C. S2 | Hierarchical, used by Google | Square cells, less suited to neighbour aggregation |
| D. Administrative polygons (wards) | Familiar names | Very uneven sizes; boundaries change |

## Decision

1. **Quotes** in `pricing.quotes` are immutable and single-use. Each records the inputs and outputs of pricing:
   - rider, pickup, drop-off, city, zone, category;
   - route distance and duration, and the fare-rule version used;
   - surge multiplier, fare breakdown and pickup ETA;
   - creation time and expiry (5 min).

   Booking consumes a quote with a conditional update in the booking transaction.
2. **Fare rules** are versioned per city and category with an effective-from time:
   - `fare = max(minimum, (base + per_km × km + per_min × min) × surge) + booking_fee`, then tax;
   - integer paise throughout, rounded up to whole rupees once, at the end.

   Fees (cancellation, no-show) are versioned the same way.
3. **Zones** are H3 resolution-7 cells. Special areas (airport, railway stations, stadium) are PostGIS polygons that take precedence over cells. Service areas (where rides may start) are PostGIS polygons per city.
4. **Surge** is a multiplier per zone, capped at 2.0×. In V1 it comes from admin rules by zone and time window. From V4 it is computed every minute from demand against supply, mapped through a step table and limited to ±0.2 change per minute. A quote locks the multiplier it used.
5. **Extensions** (airport fee, promotions, corporate rates) are added as ordered fare components behind a `FareComponent` interface, not as branches in the formula.

## Trade-offs

- Destination changes and extra stops need a new quote (designed for, not built).
- A rider may hold a cheaper quote for up to 5 minutes while surge rises; that cost is accepted in exchange for a stable price.
- H3 cells cut across neighbourhoods. Surge maps show hexagons, not familiar areas.

## Consequences

- Every fare is reproducible from its quote and rule version, which makes disputes and audits straightforward.
- The live-index query (ADR-004) supplies the pickup ETA estimate in the quote.
- Resolution-7 cells give ~150 zones for Bengaluru's ~740 km² service area. That is small enough to compute surge every minute, and too many to use as metric labels (HLD §15).

## Revisit when

- Riders need destination changes or multiple stops: re-quote mid-trip with a price delta.
- Surge experiments need finer zones (resolution 8) or demand forecasts rather than current counts.
