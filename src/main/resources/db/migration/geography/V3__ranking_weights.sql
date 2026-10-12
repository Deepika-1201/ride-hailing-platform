ALTER TABLE geography.city_categories
    ADD COLUMN ranking_eta_weight double precision NOT NULL DEFAULT 1
        CHECK (ranking_eta_weight > 0 AND ranking_eta_weight <= 1000),
    ADD COLUMN ranking_acceptance_weight double precision NOT NULL DEFAULT 60
        CHECK (ranking_acceptance_weight >= 0 AND ranking_acceptance_weight <= 3600),
    ADD COLUMN ranking_idle_weight double precision NOT NULL DEFAULT 2
        CHECK (ranking_idle_weight >= 0 AND ranking_idle_weight <= 60),
    ADD COLUMN ranking_heading_weight double precision NOT NULL DEFAULT 30
        CHECK (ranking_heading_weight >= 0 AND ranking_heading_weight <= 3600);