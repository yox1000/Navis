import axios from "axios";

export async function getRoute(origin, dest) {
  const url = `http://router.project-osrm.org/route/v1/driving/${origin.lon},${origin.lat};${dest.lon},${dest.lat}?overview=full&geometries=geojson&steps=true`;

  const res = await axios.get(url);
  return res.data;
}
