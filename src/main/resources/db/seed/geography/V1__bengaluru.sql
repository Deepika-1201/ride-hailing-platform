-- Bengaluru for local runs and demos (LLD §4.9): the city, its service area, the airport and the main railway station
-- as special areas, and all four categories with default dispatch settings.
SET LOCAL search_path = geography, public;

INSERT INTO geography.cities (id, name, time_zone, currency, bounds) VALUES
    ('blr', 'Bengaluru', 'Asia/Kolkata', 'INR',
     ST_GeomFromText('POLYGON((77.30 12.70, 77.95 12.70, 77.95 13.35, 77.30 13.35, 77.30 12.70))', 4326));

-- The metro area up to and including the airport.
INSERT INTO geography.service_areas (id, city_id, area) VALUES
    ('0199a3f0-0005-7000-8000-000000000001', 'blr',
     ST_GeomFromText('MULTIPOLYGON(((77.42 12.80, 77.82 12.80, 77.82 13.26, 77.42 13.26, 77.42 12.80)))', 4326));

INSERT INTO geography.special_areas (id, city_id, code, name, kind, area, priority) VALUES
    ('0199a3f0-0005-7000-8000-000000000002', 'blr', 'BLR-AIRPORT', 'Kempegowda International Airport', 'AIRPORT',
     ST_GeomFromText('MULTIPOLYGON(((77.67 13.17, 77.74 13.17, 77.74 13.23, 77.67 13.23, 77.67 13.17)))', 4326), 10),
    ('0199a3f0-0005-7000-8000-000000000003', 'blr', 'BLR-KSR-STATION', 'KSR Bengaluru City railway station',
     'STATION',
     ST_GeomFromText('MULTIPOLYGON(((77.5644 12.9734, 77.5744 12.9734, 77.5744 12.9834, 77.5644 12.9834, 77.5644 12.9734)))',
                     4326), 5);

INSERT INTO geography.city_categories (city_id, category)
SELECT 'blr', code FROM geography.categories;
