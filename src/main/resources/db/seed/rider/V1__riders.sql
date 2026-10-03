-- 500 riders, each with cash as their default method (LLD §4.9). Rider n is identity's seeded user n.

INSERT INTO rider.riders (user_id, first_name, default_payment_method_id)
SELECT ('0199a3f0-0002-7000-8000-' || lpad(to_hex(n), 12, '0'))::uuid,
       (ARRAY['Aditi', 'Akash', 'Ananya', 'Deepak', 'Harish', 'Isha', 'Karthik', 'Neha', 'Rahul', 'Sneha'])[1 + n % 10],
       ('0199a3f0-0004-7000-8000-' || lpad(to_hex(n), 12, '0'))::uuid
FROM generate_series(1, 500) AS n;

INSERT INTO rider.payment_methods (id, rider_id, type, display)
SELECT ('0199a3f0-0004-7000-8000-' || lpad(to_hex(n), 12, '0'))::uuid,
       ('0199a3f0-0002-7000-8000-' || lpad(to_hex(n), 12, '0'))::uuid,
       'CASH', 'Cash'
FROM generate_series(1, 500) AS n;
