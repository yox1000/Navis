import { useEffect, useRef, useState } from "react";
import maplibregl from "maplibre-gl";

const ROUTE_SOURCE_ID = "navis-route";
const POSITION_SOURCE_ID = "navis-position";

function MapCanvas({
  center = [-73.9857, 40.7484],
  routeGeoJson = null,
  userCoords = null,
  isDark = false,
  styleUrl = "https://basemaps.cartocdn.com/gl/positron-gl-style/style.json",
  markerLabel = "Current position",
}) {
  const containerRef = useRef(null);
  const mapRef = useRef(null);
  const markerRef = useRef(null);
  const [isReady, setIsReady] = useState(false);

  useEffect(() => {
    if (!containerRef.current) return;

    const map = new maplibregl.Map({
      container: containerRef.current,
      style: styleUrl,
      center,
      zoom: 17,
      pitch: 45,
      bearing: -15,
      attributionControl: true,
    });

    map.addControl(new maplibregl.NavigationControl(), "top-right");
    mapRef.current = map;

    map.on("load", () => {
      setIsReady(true);
      map.addSource(ROUTE_SOURCE_ID, {
        type: "geojson",
        data:
          routeGeoJson ?? {
            type: "FeatureCollection",
            features: [],
          },
      });
      map.addLayer({
        id: "navis-route-line",
        type: "line",
        source: ROUTE_SOURCE_ID,
        paint: {
          "line-color": "#34d399",
          "line-width": 5,
          "line-cap": "round",
          "line-join": "round",
        },
      });

      map.addSource(POSITION_SOURCE_ID, {
        type: "geojson",
        data: {
          type: "FeatureCollection",
          features: [],
        },
      });

      map.addLayer({
        id: "navis-position-circle",
        type: "circle",
        source: POSITION_SOURCE_ID,
        paint: {
          "circle-radius": 8,
          "circle-color": "#f472b6",
          "circle-stroke-width": 3,
          "circle-stroke-color": "#fff",
        },
      });
    });

    return () => {
      if (markerRef.current) {
        markerRef.current.remove();
        markerRef.current = null;
      }
      map.remove();
      mapRef.current = null;
      setIsReady(false);
    };
  }, [styleUrl]);

  useEffect(() => {
    if (!isReady || !mapRef.current || !routeGeoJson) return;
    const source = mapRef.current.getSource(ROUTE_SOURCE_ID);
    if (source) {
      source.setData(routeGeoJson);
    }
  }, [routeGeoJson, isReady]);

  useEffect(() => {
    if (!isReady || !mapRef.current || !userCoords) return;
    const [lng, lat] = userCoords;
    const source = mapRef.current.getSource(POSITION_SOURCE_ID);
    if (source) {
      source.setData({
        type: "FeatureCollection",
        features: [
          {
            type: "Feature",
            geometry: {
              type: "Point",
              coordinates: [lng, lat],
            },
          },
        ],
      });
    }
    if (!markerRef.current) {
      const markerEl = document.createElement("div");
      markerEl.className =
        "rounded-full bg-white/90 px-3 py-1 text-xs font-semibold text-slate-800 shadow";
      markerEl.textContent = markerLabel;
      markerRef.current = new maplibregl.Marker({
        color: "#0ea5e9",
        element: markerEl,
      })
        .setLngLat([lng, lat])
        .addTo(mapRef.current);
    } else {
      markerRef.current.setLngLat([lng, lat]);
      markerRef.current.getElement().textContent = markerLabel;
    }

    mapRef.current.easeTo({
      center: [lng, lat],
      duration: 1200,
      zoom: Math.max(mapRef.current.getZoom(), 17),
    });
  }, [userCoords, isReady, markerLabel]);

  useEffect(() => {
    if (!isReady || !mapRef.current || !center) return;
    mapRef.current.easeTo({ center, duration: 1000 });
  }, [center, isReady]);

  return (
    <div
      ref={containerRef}
      className={`h-[420px] w-full overflow-hidden rounded-xl border ${
        isDark
          ? "border-slate-800 bg-slate-900"
          : "border-slate-200 bg-slate-100"
      }`}
    />
  );
}

export default MapCanvas;
