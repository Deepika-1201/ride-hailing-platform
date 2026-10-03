-- Bengaluru's first fare and fee rules, in effect since before any local run, and a few surge rules (LLD §4.9).
-- MINI is the illustrative rule of LLD §10.2. Published by the seeded admin.

INSERT INTO pricing.fare_rules (id, city_id, category, version, effective_from, base_paise, per_km_paise,
                                per_min_paise, minimum_paise, booking_fee_paise, tax_bp, commission_bp, currency,
                                created_by) VALUES
    ('0199a3f0-0006-7000-8000-000000000001', 'blr', 'AUTO', 1, '2026-01-01T00:00:00+05:30',
     3000, 1500, 100, 3000, 500, 500, 1500, 'INR', '0199a3f0-0000-7000-8000-000000000002'),
    ('0199a3f0-0006-7000-8000-000000000002', 'blr', 'MINI', 1, '2026-01-01T00:00:00+05:30',
     4000, 1400, 150, 8000, 1000, 500, 2000, 'INR', '0199a3f0-0000-7000-8000-000000000002'),
    ('0199a3f0-0006-7000-8000-000000000003', 'blr', 'SEDAN', 1, '2026-01-01T00:00:00+05:30',
     5000, 1800, 200, 10000, 1000, 500, 2000, 'INR', '0199a3f0-0000-7000-8000-000000000002'),
    ('0199a3f0-0006-7000-8000-000000000004', 'blr', 'XL', 1, '2026-01-01T00:00:00+05:30',
     7000, 2400, 250, 15000, 1500, 500, 2000, 'INR', '0199a3f0-0000-7000-8000-000000000002');

INSERT INTO pricing.fee_rules (id, city_id, category, version, effective_from, cancellation_fee_paise,
                               no_show_fee_paise, commission_bp, currency, created_by) VALUES
    ('0199a3f0-0006-7000-8000-000000000011', 'blr', 'AUTO', 1, '2026-01-01T00:00:00+05:30', 2500, 5000, 1500, 'INR',
     '0199a3f0-0000-7000-8000-000000000002'),
    ('0199a3f0-0006-7000-8000-000000000012', 'blr', 'MINI', 1, '2026-01-01T00:00:00+05:30', 5000, 7500, 2000, 'INR',
     '0199a3f0-0000-7000-8000-000000000002'),
    ('0199a3f0-0006-7000-8000-000000000013', 'blr', 'SEDAN', 1, '2026-01-01T00:00:00+05:30', 5000, 7500, 2000, 'INR',
     '0199a3f0-0000-7000-8000-000000000002'),
    ('0199a3f0-0006-7000-8000-000000000014', 'blr', 'XL', 1, '2026-01-01T00:00:00+05:30', 7500, 10000, 2000, 'INR',
     '0199a3f0-0000-7000-8000-000000000002');

-- Early flights at the airport; weekday evening peaks on MG Road and in Indiranagar; Friday and Saturday nights in
-- Koramangala, wrapping past midnight. The cells are H3 resolution 7.
INSERT INTO pricing.surge_rules (id, city_id, zone_id, days_of_week, start_local, end_local, multiplier,
                                 created_by) VALUES
    ('0199a3f0-0006-7000-8000-000000000021', 'blr', 'area:BLR-AIRPORT', '{1,2,3,4,5,6,7}', '04:30', '08:00', 1.20,
     '0199a3f0-0000-7000-8000-000000000002'),
    ('0199a3f0-0006-7000-8000-000000000022', 'blr', '8761892e9ffffff', '{1,2,3,4,5}', '17:30', '20:30', 1.40,
     '0199a3f0-0000-7000-8000-000000000002'),
    ('0199a3f0-0006-7000-8000-000000000023', 'blr', '8761892ecffffff', '{1,2,3,4,5}', '17:30', '20:30', 1.30,
     '0199a3f0-0000-7000-8000-000000000002'),
    ('0199a3f0-0006-7000-8000-000000000024', 'blr', '87618925cffffff', '{5,6}', '22:00', '02:00', 1.50,
     '0199a3f0-0000-7000-8000-000000000002');
