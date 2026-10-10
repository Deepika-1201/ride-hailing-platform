import { layers, namedFlavor } from "@protomaps/basemaps";
import regularFont from "@fontsource/ibm-plex-sans/files/ibm-plex-sans-latin-400-normal.woff2?url";
import mediumFont from "@fontsource/ibm-plex-sans/files/ibm-plex-sans-latin-500-normal.woff2?url";
import italicFont from "@fontsource/ibm-plex-sans/files/ibm-plex-sans-latin-400-italic.woff2?url";
import type { StyleSpecification } from "maplibre-gl";

/** Where scripts/setup-maps.sh puts the Bengaluru extract; nginx and the dev server serve it here. */
export const BASEMAP_URL = "/maps/bengaluru.pmtiles";

export const ATTRIBUTION =
  '<a href="https://protomaps.com">Protomaps</a> © <a href="https://openstreetmap.org/copyright">OpenStreetMap contributors</a>';

/** The basemap, fonts and sprites are all served locally (ADR-022). */
export function basemapStyle(): StyleSpecification {
  return {
    version: 8,
    "font-faces": {
      "Noto Sans Regular": regularFont,
      "Noto Sans Medium": mediumFont,
      "Noto Sans Italic": italicFont,
    },
    sprite: "/map-icons/light",
    sources: {
      protomaps: { type: "vector", url: "pmtiles://" + BASEMAP_URL, attribution: ATTRIBUTION },
    },
    layers: layers("protomaps", namedFlavor("light"), { lang: "en" }),
  };
}

/** Without the extract: a plain background, so the live layers still show. */
export function blankStyle(): StyleSpecification {
  return {
    version: 8,
    sources: {},
    layers: [{ id: "background", type: "background", paint: { "background-color": "#eef0f2" } }],
  };
}

/** Whether the extract has been downloaded. */
export async function basemapAvailable(): Promise<boolean> {
  try {
    const response = await fetch(BASEMAP_URL, { headers: { Range: "bytes=0-15" } });
    return response.status === 206 || response.status === 200;
  } catch {
    return false;
  }
}
