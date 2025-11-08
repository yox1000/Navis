import axios from "axios";

export async function geocode(text) {
  const url = `https://nominatim.openstreetmap.org/search?q=${encodeURIComponent(
    text
  )}&format=json&limit=1`;

  const res = await axios.get(url);

  if (!res.data?.[0]) return null;

  return {
    lat: parseFloat(res.data[0].lat),
    lon: parseFloat(res.data[0].lon),
    name: res.data[0].display_name,
  };
}
