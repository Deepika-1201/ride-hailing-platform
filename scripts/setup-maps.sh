#!/usr/bin/env bash
# Builds the map data for live simulator runs and the web app into .maps/ (ADR-013, ADR-021, ADR-022):
#   bengaluru.osm.pbf   OpenStreetMap data within the city's bounds, clipped from Geofabrik's Southern Zone extract
#   bengaluru.osrm.*    OSRM car routing data (MLD) for the routing Compose profile
#   bengaluru.pmtiles   the Protomaps basemap within the same bounds
# Needs Docker. Downloads ~600 MB once and keeps ~1 GB. Steps whose output exists are skipped; delete .maps/ to
# start again.
set -euo pipefail
cd "$(dirname "$0")/.."

# The city's bounds in the geography seed.
BBOX=77.30,12.70,77.95,13.35
OSRM_IMAGE=ghcr.io/project-osrm/osrm-backend@sha256:55dbfd47d984c2cacd64e32901b4321c86b5ec761438e69aa053dc78698a786f
PMTILES_IMAGE=protomaps/go-pmtiles:latest
MAPS="$PWD/.maps"
mkdir -p "$MAPS"

step() { printf '\n== %s\n' "$*"; }

if [[ ! -s "$MAPS/bengaluru.osm.pbf" ]]; then
  if [[ ! -s "$MAPS/southern-zone.osm.pbf" ]]; then
    step "Downloading the Southern Zone extract (~530 MB)"
    curl -fL --retry 3 -C - -o "$MAPS/southern-zone.osm.pbf.part" \
      https://download.geofabrik.de/asia/india/southern-zone-latest.osm.pbf
    mv "$MAPS/southern-zone.osm.pbf.part" "$MAPS/southern-zone.osm.pbf"
  fi
  step "Clipping it to Bengaluru"
  # Dense local node IDs keep Osmium's extraction bitmaps within the laptop VM's memory.
  docker run --rm -e DEBIAN_FRONTEND=noninteractive -v "$MAPS:/data" debian:trixie-slim sh -c \
    "apt-get update -qq && apt-get install -y -qq osmium-tool >/dev/null &&
     osmium renumber --overwrite --object-type=node -o /data/southern-zone-dense.osm.pbf /data/southern-zone.osm.pbf &&
     osmium extract --overwrite --strategy=complete_ways --bbox $BBOX --output-format=pbf \
       -o /data/bengaluru.osm.pbf.part /data/southern-zone-dense.osm.pbf"
  mv "$MAPS/bengaluru.osm.pbf.part" "$MAPS/bengaluru.osm.pbf"
  rm "$MAPS/southern-zone.osm.pbf" "$MAPS/southern-zone-dense.osm.pbf"
fi

if [[ ! -s "$MAPS/bengaluru.osrm.mldgr" ]]; then
  step "Building OSRM's car routing data"
  docker run --rm -v "$MAPS:/data" "$OSRM_IMAGE" osrm-extract -p /opt/car.lua /data/bengaluru.osm.pbf
  docker run --rm -v "$MAPS:/data" "$OSRM_IMAGE" osrm-partition /data/bengaluru.osrm
  docker run --rm -v "$MAPS:/data" "$OSRM_IMAGE" osrm-customize /data/bengaluru.osrm
fi

if [[ ! -s "$MAPS/bengaluru.pmtiles" ]]; then
  # Daily builds are kept for a week: yesterday's is always complete.
  build=$(date -u -v-1d +%Y%m%d 2>/dev/null || date -u -d yesterday +%Y%m%d)
  step "Extracting the basemap from Protomaps build $build"
  docker run --rm -v "$MAPS:/data" "$PMTILES_IMAGE" extract "https://build.protomaps.com/$build.pmtiles" \
    /data/bengaluru.pmtiles --bbox="$BBOX" --maxzoom=15
fi

step "Done"
ls -lh "$MAPS" | sed 1d
echo "Start routing with: docker compose --profile routing up -d osrm"
