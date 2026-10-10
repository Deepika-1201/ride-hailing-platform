import { addProtocol, type GeoJSONSource, MapLibreMap, setWorkerUrl } from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import mapWorkerUrl from "maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url";
import { Protocol } from "pmtiles";
import { useEffect, useRef, useState } from "react";
import type { FeatureCollection } from "geojson";
import type { GeoPoint } from "../api/types";
import { type Bbox, bboxOf, statusColour } from "./layers";
import { ATTRIBUTION, basemapAvailable, basemapStyle, blankStyle } from "./style";

setWorkerUrl(mapWorkerUrl);

let protocolAdded = false;

const EMPTY: FeatureCollection = { type: "FeatureCollection", features: [] };

export const BENGALURU: GeoPoint = { lat: 12.9716, lon: 77.5946 };

interface Props {
  drivers?: FeatureCollection;
  rides?: FeatureCollection;
  markers?: FeatureCollection;
  center?: GeoPoint;
  zoom?: number;
  onClick?: (at: GeoPoint) => void;
  onBounds?: (bbox: Bbox) => void;
  onRideClick?: (rideId: string) => void;
}

/** A MapLibre map with the live layers: drivers by status, active rides, and the screen's own markers. */
export function MapView({ drivers, rides, markers, center = BENGALURU, zoom = 12, onClick, onBounds, onRideClick }: Props) {
  const container = useRef<HTMLDivElement>(null);
  const map = useRef<MapLibreMap | undefined>(undefined);
  const [ready, setReady] = useState(false);
  const [basemap, setBasemap] = useState<boolean>();
  const handlers = useRef({ onClick, onBounds, onRideClick });
  handlers.current = { onClick, onBounds, onRideClick };

  useEffect(() => {
    void basemapAvailable().then(setBasemap);
  }, []);

  useEffect(() => {
    if (basemap === undefined || !container.current) return;
    if (!protocolAdded) {
      addProtocol("pmtiles", new Protocol().tile);
      protocolAdded = true;
    }
    const m = new MapLibreMap({
      container: container.current,
      style: basemap ? basemapStyle() : blankStyle(),
      center: [center.lon, center.lat],
      zoom,
      attributionControl: { compact: false, customAttribution: basemap ? undefined : ATTRIBUTION },
    });
    map.current = m;
    m.on("load", () => {
      m.addSource("rides", { type: "geojson", data: EMPTY });
      m.addSource("drivers", { type: "geojson", data: EMPTY });
      m.addSource("markers", { type: "geojson", data: EMPTY });
      m.addLayer({
        id: "rides",
        type: "line",
        source: "rides",
        paint: {
          "line-color": ["case", ["get", "selected"], "#dc2626", "#64748b"],
          "line-width": ["case", ["get", "selected"], 4, 2],
          "line-dasharray": [2, 1],
        },
      });
      m.addLayer({
        id: "drivers",
        type: "circle",
        source: "drivers",
        paint: {
          "circle-radius": ["interpolate", ["linear"], ["zoom"], 10, 2.5, 15, 6],
          "circle-color": statusColour as never,
          "circle-opacity": ["case", ["get", "stale"], 0.35, 0.9],
          "circle-stroke-width": 0.5,
          "circle-stroke-color": "#ffffff",
        },
      });
      m.addLayer({
        id: "markers",
        type: "circle",
        source: "markers",
        paint: {
          "circle-radius": 8,
          "circle-color": ["match", ["get", "kind"], "pickup", "#16a34a", "dropoff", "#dc2626", "driver", "#2563eb", "#111827"],
          "circle-stroke-width": 2,
          "circle-stroke-color": "#ffffff",
        },
      });
      m.on("click", (e) => handlers.current.onClick?.({ lat: e.lngLat.lat, lon: e.lngLat.lng }));
      m.on("click", "rides", (e) => {
        const id = e.features?.[0]?.properties?.ride_id;
        if (id) handlers.current.onRideClick?.(String(id));
      });
      m.on("click", "drivers", (e) => {
        const id = e.features?.[0]?.properties?.ride_id;
        if (id) handlers.current.onRideClick?.(String(id));
      });
      const bounds = () => {
        const b = m.getBounds();
        handlers.current.onBounds?.(bboxOf(b.getWest(), b.getSouth(), b.getEast(), b.getNorth()));
      };
      m.on("moveend", bounds);
      bounds();
      setReady(true);
    });
    return () => {
      m.remove();
      map.current = undefined;
      setReady(false);
    };
    // The map is made once; later centres and zooms are the user's to change.
  }, [basemap]);

  useEffect(() => {
    if (ready) (map.current?.getSource("drivers") as GeoJSONSource | undefined)?.setData(drivers ?? EMPTY);
  }, [ready, drivers]);
  useEffect(() => {
    if (ready) (map.current?.getSource("rides") as GeoJSONSource | undefined)?.setData(rides ?? EMPTY);
  }, [ready, rides]);
  useEffect(() => {
    if (ready) (map.current?.getSource("markers") as GeoJSONSource | undefined)?.setData(markers ?? EMPTY);
  }, [ready, markers]);

  return (
    <div className="map" aria-busy={!ready}>
      <div ref={container} className="map-canvas" />
      {basemap === false && <div className="map-note" role="status">Basemap unavailable</div>}
    </div>
  );
}
