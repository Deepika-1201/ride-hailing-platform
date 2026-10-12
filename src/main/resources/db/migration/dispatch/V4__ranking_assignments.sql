ALTER TABLE dispatch.search_tasks
    ADD COLUMN ranking_assignment jsonb,
    ADD CONSTRAINT ranking_assignment_object CHECK (ranking_assignment IS NULL OR jsonb_typeof(ranking_assignment) = 'object');