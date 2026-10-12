ALTER TABLE pricing.quotes DROP CONSTRAINT quotes_route_source_check;
ALTER TABLE pricing.quotes ADD CONSTRAINT quotes_route_source_check
    CHECK (route_source IN ('MOCK', 'OSRM', 'OSRM_ADJUSTED', 'MOCK_FALLBACK'));