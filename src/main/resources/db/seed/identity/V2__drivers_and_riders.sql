-- Seeded drivers and riders (LLD §4.9): user n has an ID and a phone number derived from n, so the driver and rider
-- seeds agree with these without querying identity.

INSERT INTO identity.users (id, phone, roles)
SELECT ('0199a3f0-0001-7000-8000-' || lpad(to_hex(n), 12, '0'))::uuid, '+91' || (7000000000 + n), ARRAY['DRIVER']
FROM generate_series(1, 2000) AS n
ON CONFLICT (phone) DO NOTHING;

INSERT INTO identity.users (id, phone, roles)
SELECT ('0199a3f0-0002-7000-8000-' || lpad(to_hex(n), 12, '0'))::uuid, '+91' || (8000000000 + n), ARRAY['RIDER']
FROM generate_series(1, 500) AS n
ON CONFLICT (phone) DO NOTHING;
