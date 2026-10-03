-- 2,000 verified Bengaluru drivers, each with one vehicle: 30% AUTO, 40% MINI, 20% SEDAN and 10% XL (LLD §4.9).
-- Driver n is identity's seeded user n.

INSERT INTO driver.drivers (id, city_id, first_name, last_name, verification)
SELECT ('0199a3f0-0001-7000-8000-' || lpad(to_hex(n), 12, '0'))::uuid,
       'blr',
       (ARRAY['Aarav', 'Arjun', 'Divya', 'Farhan', 'Gowri', 'Imran', 'Kavya', 'Lakshmi', 'Manjunath', 'Meera',
              'Naveen', 'Nikhil', 'Pooja', 'Prakash', 'Ravi', 'Rekha', 'Sanjay', 'Shilpa', 'Suresh', 'Vinay'])[1 + n % 20],
       (ARRAY['Gowda', 'Hegde', 'Iyer', 'Khan', 'Kumar', 'Naidu', 'Patil', 'Rao', 'Reddy', 'Shetty'])[1 + (n / 20) % 10],
       'VERIFIED'
FROM generate_series(1, 2000) AS n;

INSERT INTO driver.vehicles (id, driver_id, category, plate, make, model, colour)
SELECT ('0199a3f0-0003-7000-8000-' || lpad(to_hex(n), 12, '0'))::uuid,
       ('0199a3f0-0001-7000-8000-' || lpad(to_hex(n), 12, '0'))::uuid,
       kind.category, 'KA 01 SD ' || lpad(n::text, 4, '0'), kind.make, kind.model, kind.colour
FROM generate_series(1, 2000) AS n
CROSS JOIN LATERAL (
    SELECT * FROM (VALUES
        ('AUTO', 'Bajaj', 'RE Compact', 'Green and yellow'),
        ('MINI', 'Maruti Suzuki', 'Wagon R', 'White'),
        ('SEDAN', 'Honda', 'City', 'Silver'),
        ('XL', 'Toyota', 'Innova Crysta', 'Grey')) AS v (category, make, model, colour)
    WHERE v.category = CASE WHEN n % 10 < 3 THEN 'AUTO' WHEN n % 10 < 7 THEN 'MINI' WHEN n % 10 < 9 THEN 'SEDAN'
                            ELSE 'XL' END
) AS kind;
