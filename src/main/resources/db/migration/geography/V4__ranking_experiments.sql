ALTER TABLE geography.city_categories
    ADD COLUMN ranking_experiment_id text,
    ADD COLUMN ranking_experiment_ranker text,
    ADD COLUMN ranking_experiment_percentage int,
    ADD CONSTRAINT ranking_experiment_complete CHECK (
        (ranking_experiment_id IS NULL AND ranking_experiment_ranker IS NULL AND ranking_experiment_percentage IS NULL)
        OR (ranking_experiment_id IS NOT NULL AND ranking_experiment_ranker IS NOT NULL AND ranking_experiment_percentage IS NOT NULL
            AND ranking_experiment_id ~ '^[a-z0-9][a-z0-9_-]{0,63}$'
            AND ranking_experiment_ranker IN ('nearest', 'eta', 'weighted')
            AND ranking_experiment_percentage BETWEEN 0 AND 100)
    );