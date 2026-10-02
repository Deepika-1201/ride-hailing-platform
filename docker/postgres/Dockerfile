# PostgreSQL 18 with PostGIS 3 from the PGDG packages the official image already uses (ADR-003).
# The postgis/postgis images have no ARM64 build.
FROM postgres:18
RUN apt-get update \
    && apt-get install -y --no-install-recommends postgresql-18-postgis-3 \
    && rm -rf /var/lib/apt/lists/*
