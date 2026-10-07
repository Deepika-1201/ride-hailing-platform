-- The quote's fare breakdown, kept for the receipt (LLD §13.6): pricing deletes used quotes after 30 days. Rides booked
-- before this column have none.

ALTER TABLE ride.rides ADD COLUMN fare_breakdown jsonb;
