// backend/src/router.js

import { callNeuralSeek } from "./neuralseek.js";
import { geocode } from "./geocode.js";
import { getRoute } from "./osrm.js";
import { findPOI } from "./poi.js";

export async function handleQuery(queryText, coords) {

  // ---- 1) Ask NeuralSeek to classify intent + entity ----
  const nsResp = await callNeuralSeek("main", { queryText });

  // Old API returns values under: result.variables
  const parsedVars = nsResp?.result?.variables || {};
  const { intent, entity } = parsedVars;

  console.log("Parsed intent =", intent, " entity =", entity);

  // ---- 2) NAVIGATION -------------------------------------------------
  if (intent === 1) {
    const dest = await geocode(entity);
    if (!dest) {
      // cannot geocode → fallback
      return await callNeuralSeek("fallback", {});
    }

    const routeData = await getRoute(coords, dest);
    const distanceText = `${Math.round(routeData.routes[0].distance / 100) / 10} km`;
    const etaText = `${Math.round(routeData.routes[0].duration / 60)} min`;

    return await callNeuralSeek("navigation", {
      destinationName: dest.name,
      etaText,
      distanceText
    });
  }

  // ---- 3) ROUTE CHECK ------------------------------------------------
  if (intent === 2) {
    // TODO: Compute real "onRoute"
    const onRoute = true;
    return await callNeuralSeek("route_check", { onRoute });
  }

  // ---- 4) EXPLORATION ------------------------------------------------
  if (intent === 3) {
    const result = await findPOI(entity, coords);

    if (!result) {
      return await callNeuralSeek("exploration", {
        placeFound: false
      });
    }

    return await callNeuralSeek("exploration", {
      placeFound: true,
      placeName: result.name,
      distanceText: "nearby"
    });
  }

  // ---- 5) FALLBACK ---------------------------------------------------
  return await callNeuralSeek("fallback", {});
}
