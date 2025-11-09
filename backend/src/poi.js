import axios from "axios";

export async function findPOI(type, coords) {
  // simple OSM example — refine later
  const url = `https://nominatim.openstreetmap.org/search?format=json&limit=5&q=${encodeURIComponent(
    type
  )}&viewbox=${coords.lon - 0.01},${coords.lat + 0.01},${
    coords.lon + 0.01
  },${coords.lat - 0.01}&bounded=1`;

  const res = await axios.get(url);

  if (!res.data.length) return null;

  const best = res.data[0];
  return {
    name: best.display_name,
    lat: parseFloat(best.lat),
    lon: parseFloat(best.lon),
  };
}
