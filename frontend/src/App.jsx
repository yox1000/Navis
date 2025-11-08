import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import MapCanvas from "./components/MapCanvas.jsx";
import { MAP_STYLE_URL, MAPTILER_API_KEY } from "./config.js";
import { handleQuery } from "./api/router.js";

const DEFAULT_COORDS = [-73.1236, 40.9148];

function App() {
  // Geolocation
  const [currentPos, setCurrentPos] = useState(null);
  const [distance, setDistance] = useState(null);
  const [error, setError] = useState(null);

  // Speech
  const [isRecording, setIsRecording] = useState(false);
  const [transcript, setTranscript] = useState("");
  const [assistantReply, setAssistantReply] = useState("Ask Navis anything about your surroundings.");
  const recognitionRef = useRef(null);

  const [theme, setTheme] = useState("light");
  const [currentCoords, setCurrentCoords] = useState(DEFAULT_COORDS);
  const [coordsAccuracy, setCoordsAccuracy] = useState(null);
  const [geolocationError, setGeolocationError] = useState("");
  const [locationLabel, setLocationLabel] = useState("Melville Library");
  const isDark = theme === "dark";

  const sampleRouteGeoJson = useMemo(
    () => ({
      type: "FeatureCollection",
      features: [
        {
          type: "Feature",
          properties: {},
          geometry: {
            type: "LineString",
            coordinates: [
              [-73.1246, 40.9143],
              [-73.1242, 40.9146],
              [-73.1237, 40.9149],
              [-73.1231, 40.9152],
              [-73.1225, 40.9155],
            ],
          },
        },
      ],
    }),
    []
  );

  // Geolocation logic
  const fetchDistance = async (lat1, lon1, lat2, lon2) => {
    try {
      // OSRM public server example
      const url = `https://router.project-osrm.org/route/v1/driving/${lon1},${lat1};${lon2},${lat2}?overview=false`;
      const res = await fetch(url);
      const data = await res.json();

      if (data.code === 'Ok' && data.routes.length > 0) {
        setDistance(data.routes[0].distance); // meters
      } else {
        setError('No route found');
      }
    } catch (err) {
      setError(err.message);
    }
  };

  const fetchPlaceLabel = useCallback(
    async ([lng, lat]) => {
      if (!MAPTILER_API_KEY) return;
      try {
        const response = await fetch(
          `https://api.maptiler.com/geocoding/${lng},${lat}.json?limit=1&key=${MAPTILER_API_KEY}`
        );
        if (!response.ok) return;
        const data = await response.json();
        const label =
          data?.features?.[0]?.place_name ||
          data?.features?.[0]?.text ||
          "Your location";
        setLocationLabel(label);
      } catch (err) {
        console.warn("Reverse geocoding failed", err);
      }
    },
    [MAPTILER_API_KEY]
  );

  useEffect(() => {
    fetchPlaceLabel(DEFAULT_COORDS);
  }, [fetchPlaceLabel]);

  useEffect(() => {
    if (!navigator.geolocation) {
      setGeolocationError("Geolocation is not supported on this device.");
      return;
    }

    const watchId = navigator.geolocation.watchPosition(
      (position) => {
        const { latitude, longitude, accuracy } = position.coords;
        const nextCoords = [longitude, latitude];
        setCurrentCoords(nextCoords);
        setCoordsAccuracy(accuracy ?? null);
        setGeolocationError("");
        fetchPlaceLabel(nextCoords);
      },
      (error) => {
        setGeolocationError(error.message || "Unable to obtain location.");
      },
      {
        enableHighAccuracy: true,
        maximumAge: 2000,
        timeout: 10000,
      }
    );

    return () => navigator.geolocation.clearWatch(watchId);
  }, [fetchPlaceLabel]);

  // Speech logic
  const handleMicToggle = async () => {
    if (!isRecording) {
      // Start speech recognition
      const SpeechRecognition =
        window.SpeechRecognition || window.webkitSpeechRecognition;
      if (!SpeechRecognition) {
        alert('Speech Recognition not supported in this browser.');
        return;
      }

      const recognition = new SpeechRecognition();
      recognitionRef.current = recognition;

      recognition.continuous = true;
      recognition.interimResults = true;
      recognition.lang = 'en-US';

      setAssistantReply("Listening...");

      recognition.onresult = (event) => {
        let interimTranscript = '';
        for (let i = event.resultIndex; i < event.results.length; i++) {
          interimTranscript += event.results[i][0].transcript;
        }
        setTranscript(interimTranscript);

        // Optional: do something if transcript starts with "hello"
        if (interimTranscript.trim().toLowerCase().startsWith('hello')) {
          console.log('Hello detected!');
          // You could trigger audio download or other actions here
        }
      };

      recognition.start();
      setIsRecording(true);
    } else {
      // Stop recognition
      recognitionRef.current.stop();
      setIsRecording(false);

      // TODO: Process speech
      const reply = await handleQuery(transcript);
      setAssistantReply(reply.answer);
    }
  };

  return (
    <div
      className={`min-h-screen transition-colors ${
        isDark ? "bg-slate-950 text-slate-50" : "bg-slate-50 text-slate-900"
      }`}
    >
      <header
        className={`border-b backdrop-blur ${
          isDark
            ? "border-slate-800 bg-slate-900/70"
            : "border-slate-200 bg-white/80"
        }`}
      >
        <div className="mx-auto flex max-w-6xl flex-col gap-3 px-6 py-6 md:flex-row md:items-center md:justify-between">
          <div>
            <p
              className={`text-xs uppercase tracking-[0.3em] ${
                isDark ? "text-emerald-300" : "text-emerald-600"
              }`}
            >
              Navis
            </p>
            <h1 className="text-2xl font-semibold">Spatial Companion</h1>
            <p
              className={`text-sm ${
                isDark ? "text-slate-300" : "text-slate-600"
              }`}
            >
              Location-aware guidance, tailored for blind and low-vision
              explorers.
            </p>
          </div>
          <div className="flex gap-3">
            <button
              onClick={() =>
                setTheme((prev) => (prev === "dark" ? "light" : "dark"))
              }
              className={`rounded-full border px-4 py-2 text-sm font-medium transition ${
                isDark
                  ? "border-slate-700 bg-slate-800 text-slate-200 hover:bg-slate-700"
                  : "border-slate-200 bg-white text-slate-700 hover:bg-slate-100"
              }`}
            >
              {isDark ? "Light Mode" : "Dark Mode"}
            </button>
            <button
              className={`rounded-full border px-5 py-2 text-sm font-medium transition ${
                isDark
                  ? "border-emerald-300/40 bg-emerald-400/10 text-emerald-200 hover:bg-emerald-400/20"
                  : "border-emerald-500/50 bg-emerald-500/10 text-emerald-700 hover:bg-emerald-500/20"
              }`}
            >
              Connect Device
            </button>
          </div>
        </div>
      </header>

      <main className="mx-auto grid max-w-6xl gap-6 px-6 py-8 lg:grid-cols-5">
        <section
          className={`space-y-6 rounded-2xl border p-6 lg:col-span-3 ${
            isDark
              ? "border-slate-800 bg-slate-900/40"
              : "border-slate-200 bg-white"
          }`}
        >
          <div className="flex items-center justify-between">
            <div>
              <p
                className={`text-xs uppercase tracking-[0.3em] ${
                  isDark ? "text-slate-400" : "text-slate-500"
                }`}
              >
                Live Route
              </p>
              <h2 className="text-xl font-semibold">Navigation preview</h2>
            </div>
            <span
              className={`rounded-full px-3 py-1 text-xs ${
                isDark
                  ? "bg-emerald-400/10 text-emerald-200"
                  : "bg-emerald-100 text-emerald-700"
              }`}
            >
              OSRM ↯ MapLibre
            </span>
          </div>
          <MapCanvas
            center={currentCoords}
            routeGeoJson={sampleRouteGeoJson}
            userCoords={currentCoords}
            isDark={isDark}
            styleUrl={MAP_STYLE_URL}
            markerLabel={locationLabel}
          />
          <div
            className={`rounded-xl border p-4 text-sm ${
              isDark
                ? "border-slate-800 bg-slate-900/70 text-slate-300"
                : "border-slate-200 bg-slate-50 text-slate-600"
            }`}
          >
            <p
              className={`font-medium ${
                isDark ? "text-slate-100" : "text-slate-800"
              }`}
            >
              Route summary
            </p>
            <ul
              className={`mt-2 space-y-1 text-xs ${
                isDark ? "text-slate-400" : "text-slate-500"
              }`}
            >
              <li>• Location lock: Ready</li>
              <li>• OSRM service: Connected</li>
              <li>• Maestro context: Awaiting prompt</li>
              <li>
                • Accuracy:{" "}
                {coordsAccuracy ? `${Math.round(coordsAccuracy)} m` : "—"}
              </li>
              <li>
                • Position label:{" "}
                {geolocationError ? "Awaiting precise fix" : locationLabel}
              </li>
            </ul>
          </div>
        </section>

        <section
          className={`space-y-5 rounded-2xl border p-6 lg:col-span-2 ${
            isDark
              ? "border-slate-800 bg-slate-900/60"
              : "border-slate-200 bg-white"
          }`}
        >
          <div>
            <p
              className={`text-xs uppercase tracking-[0.3em] ${
                isDark ? "text-slate-400" : "text-slate-500"
              }`}
            >
              Voice console
            </p>
            <h2
              className={`text-xl font-semibold ${
                isDark ? "text-white" : "text-slate-900"
              }`}
            >
              Ask Navis
            </h2>
            <p
              className={`text-sm ${
                isDark ? "text-slate-400" : "text-slate-500"
              }`}
            >
              Hold the mic button, speak your question, and hear the Maestro +
              OSRM answer immediately.
            </p>
            {geolocationError && (
              <p className="mt-2 text-xs text-rose-400">
                {geolocationError}
              </p>
            )}
          </div>

          <div
            className={`rounded-xl border p-4 ${
              isDark
                ? "border-slate-800 bg-slate-950/60"
                : "border-slate-200 bg-slate-50"
            }`}
          >
            <p
              className={`text-xs uppercase tracking-[0.3em] ${
                isDark ? "text-slate-500" : "text-slate-500"
              }`}
            >
              Transcript
            </p>
            <p
              className={`mt-3 text-base ${
                isDark ? "text-slate-100" : "text-slate-800"
              }`}
            >
              {transcript || "Tap record to capture a question."}
            </p>
          </div>

          <div
            className={`rounded-xl border p-4 ${
              isDark
                ? "border-slate-800 bg-slate-950/60"
                : "border-slate-200 bg-slate-50"
            }`}
          >
            <p
              className={`text-xs uppercase tracking-[0.3em] ${
                isDark ? "text-slate-500" : "text-slate-500"
              }`}
            >
              Navis reply
            </p>
            <p
              className={`mt-3 text-base ${
                isDark ? "text-emerald-100" : "text-emerald-700"
              }`}
            >
              {assistantReply}
            </p>
          </div>

          <button
            onClick={handleMicToggle}
            className={`flex w-full items-center justify-center gap-3 rounded-2xl border px-6 py-4 text-lg font-semibold transition ${
              isRecording
                ? isDark
                  ? "border-rose-500/40 bg-rose-500/10 text-rose-100"
                  : "border-rose-400/40 bg-rose-400/10 text-rose-700"
                : isDark
                  ? "border-emerald-400/40 bg-emerald-400/10 text-emerald-100"
                  : "border-emerald-400/60 bg-emerald-400/10 text-emerald-700"
            }`}
          >
            <span
              className={`h-3 w-3 rounded-full ${
                isRecording ? "bg-rose-400 animate-pulse" : "bg-emerald-400"
              }`}
            />
            {isRecording ? "Listening..." : "Tap to Talk"}
          </button>

          <div
            className={`rounded-xl border p-4 text-xs ${
              isDark
                ? "border-slate-800 bg-slate-900/40 text-slate-400"
                : "border-slate-200 bg-slate-50 text-slate-500"
            }`}
          >
            Tip: try asking “I need a cup of coffee.”
          </div>
        </section>
      </main>
    </div>
  );
}

export default App;
