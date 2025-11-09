Navis context (updated after map tile fix)
========================================

- Goal: Navis is a voice-first navigation companion for visually impaired users, delivering continuous spoken guidance using precise device coordinates. Initial React/MapLibre web prototype proved UI but lacked location fidelity, so hackathon pivot is to a single-device Android app (Kotlin + Jetpack Compose) that keeps mic → STT → Maestro prompt → OSRM routing → TTS entirely on-device for the demo.
- Current Android app: `MainActivity` hosts a `RealTimeDashboard` composable showing a live clock, latest fused GPS coordinates, and an OSMDroid `MapView`. It requests coarse/fine location permissions at runtime, subscribes to Google’s fused location provider (1s, high accuracy), and keeps a “You are here” marker centered at zoom 18.
- Map tiles: OSMDroid uses a custom `OnlineTileSourceBase` pointing at MapTiler Streets v2. Fix applied (2025-02-15) to use the proper `https://api.maptiler.com/maps/streets-v2/{z}/{x}/{y}@2x.png?key=…&dpi=192` pattern; previous `/512/` segment caused 404s and the app only showed the OSMDroid fallback grid.
- Resolved issue log: “Map renders as infinite grid” traced to invalid MapTiler endpoint (`/512/` path). Solution was to replace the base URL with `.../maps/streets-v2/` and insert `@2x` before `.png`, matching MapTiler’s documented scheme so tiles download successfully.
- Upcoming UI/feature requirements (2025-02-15): add a continuous voice-assistant layer that (1) listens live with VAD to know when the primary user is speaking, (2) streams audio to STT, (3) classifies intents such as “start/go to route,” “nearby places,” “am I on route,” etc., (4) queries routing/POI/back-end logic, (5) responds using AI-generated TTS in near real time, and (6) presents appropriate UI affordances for listening/speaking state. Feature must be efficient and fully on-device-or-local orchestration for the hackathon demo. Continuous context logging requested—keep `context/codex-notes.md` updated whenever architecture or implementation details change.
- Sponsor API integrations required for the assistant: Gemini API for language/intents, ElevenLabs for low-latency neural TTS, and NeuralSeek for navigation-specific knowledge grounding/QA. Need to design STT/VAD pipeline that can hand off text to Gemini/NeuralSeek and return ElevenLabs audio in ~real time.
- UI update (2025-02-15): added push-to-talk controls layered over the map—a status card plus a large hold-to-speak mic button that currently toggles “Listening” state only (no backend yet). Also removed hard-coded zoom resets so user-adjusted zoom levels persist when location updates arrive; the map now re-centers without overriding zoom.
- Manifest permissions currently include `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `INTERNET`, `ACCESS_NETWORK_STATE`. API key temporarily embedded in `res/values/strings.xml`.
- Next big features (not yet implemented): microphone capture, speech recognition, routing, and TTS playback. Backend remains optional; current plan assumes single-phone orchestration.
- API keys (temp storage 2025-02-15): `maptiler_key`, `elevenlabs_api_key`, and `gemini_api_key` are currently hard-coded in `android/app/src/main/res/values/strings.xml` for rapid prototyping. Replace with secure storage before release.
- Mic prototype (2025-02-15): push-to-talk button originally drove an `AudioRecord` loop for level metering; replaced with the system `SpeechRecognizer` RMS feed so the UI meter updates without conflicting audio sources. Audio + location permissions are requested together; mic capture currently used for speech recognition + visual feedback while we prep streaming STT.
- Interim STT (2025-02-15): integrated Android's built-in `SpeechRecognizer` with push-to-talk. Holding the mic button starts recognition, partial/final transcripts stream into the UI, and errors/availability are surfaced in the assistant card. Architecture intentionally modular so we can swap in Gemini STT later while reusing the same UI + state plumbing.
- TTS loop (2025-02-15): wired ElevenLabs streaming TTS via new `ElevenLabsVoice` helper (OkHttp + MediaPlayer). After each final transcript, Navis plays “You said: …” using the sponsor key, and UI surfaces Speaking/Completed/Error states. This sets up the response path for future Gemini-generated messages.
- Intent routing (2025-02-15): added `NeuralSeekClient` (agent `interpreter`) that sends final transcripts to the provided Maistro endpoint, parses the JSON intent/entity, displays status in the UI, and feeds the friendly description into ElevenLabs. Voice feedback now reflects NeuralSeek’s interpretation (“Navigating to …”, “Exploring nearby …”) instead of simply echoing the user.
- Gemini fallback (2025-02-15): NeuralSeek now simply returns raw `textOutput`; Gemini 1.5 Flash rewrites that snippet into clean `{ "intent": … }` JSON every time. This removed the brittle local parsing and ensures ElevenLabs always speaks Gemini’s interpretation, while the UI logs the raw snippet for debugging.

Session notes (2025-02-16)
--------------------------

- Speech loop hardening: removed the old Gemini intent dependency and now parse NeuralSeek’s JSON locally (handles ```json fenced output, nested strings). Added a fallback where Gemini is only invoked if NeuralSeek parsing fails, preventing crashes when speech results arrive quickly.
- Logging overhaul: introduced `NavisLog` that mirrors every `Log.*` call into `files/logs/navis.log`, making it easy to collect crash context without logcat access. Speech recognizer, location, ElevenLabs, Gemini, NeuralSeek, and OSRM calls now emit detailed traces.
- Route rendering: Added OSMDroid polyline overlay management plus densification logic so even very short walks draw a visible blue line. Map auto-zooms to the route bounding box and retains user zoom when idle.
- Walking profile: Switched routing backend from `router.project-osrm.org` (car) to `https://routing.openstreetmap.de/routed-foot` to obtain real pedestrian paths. Added a short-walk simplifier that replaces overly long detours with a straight-line path when destinations are within ~450 m.
- Destination grounding: Gemini now receives the user’s coordinates plus campus context string. Added `LocalPoiResolver` with canonical Stony Brook landmarks (Melville Library, SAC, Staller Center, Javits). When Gemini’s coordinates are far away or missing, Navis falls back to the local POI and prefers nearer matches.
- Permissions/location: fused provider now fetches the “last known” fix as soon as permissions are granted so the HUD doesn’t wait for STT to run before showing coordinates.

Latest issues + observations (2025-02-17)
----------------------------------------

- Gemini occasionally returns off-campus coordinates even with location hints; we now log the chosen destination (Gemini vs. local POI) and added a destination marker on the map so testers can visually verify each resolved point. Still need to export `navis.log` from the device (adb pull) to debug mis-resolutions since logs aren’t stored in the repo.
- Routing backend switched to `https://routing.openstreetmap.de/routed-foot` for true pedestrian paths, but we noticed some ultra-short walks still come back empty; added straight-line densification and logging so we know when fallback is used.
- Next debugging tasks: capture `navis.log` after each test run to confirm the coordinates Gemini/local resolver selected, expand the POI table with more campus landmarks, or integrate a MapTiler/OSM reverse-geocode verification step before calling OSRM.
- Coordinate grounding update (2025-02-17): added a lightweight Nominatim (OpenStreetMap) client so navigation intents first try OSM search for precise lat/lon near the user; Gemini now runs only when neither local POIs nor Nominatim produce a result, keeping coordinates anchored to official OSM data.
- Route smoothing tweak (2025-02-17): the short-walk simplifier now keeps OSRM polylines whenever they return ≥3 points, only drawing a straight densified line when OSRM fails or returns a degenerate 2-point path, eliminating the SAC straight-line bug.
- Intent routing (2025-02-17 → 2025-02-18): NeuralSeek remains the primary intent classifier (interpreter agent); Gemini is only invoked as a fallback when NeuralSeek or parsing fails, keeping sponsor requirements satisfied while preserving resiliency.
- Route check (2025-02-17): Intent 2 now measures the user’s live location against the active OSRM polyline, computing distance-to-route, progress, and ETA; if the user drifts >15 m it warns or suggests rerouting, otherwise it reports remaining distance/duration.
- Coordinate selection (2025-02-17): Nominatim now biases (not clamps) lookups around the user and we run Gemini grounding in parallel, choosing the closer/higher-confidence result so off-campus queries no longer get stuck with inaccurate local matches.
- Nearby explore (2025-02-17): Intent 3 uses the same Nominatim client to fetch up to five POIs within ~1.5 km of the user, summarizes the closest three with distance/direction in speech, and falls back with guidance when no local results exist.
- NeuralSeek restored (2025-02-18): Intent classification once again uses NeuralSeek’s interpreter agent (Gemini is only used as a fallback when NeuralSeek/parsing fails), so sponsor requirements are met and intent JSON stays consistent.
- NeuralSeek explore (2025-02-18): Intent 3 now also queries NeuralSeek agent “main” with the user’s phrase plus lat/lon; if the agent returns contextual guidance we speak it, otherwise we fall back to the local nearby-summary list.

Planning + next steps
---------------------

- Intent roadmap:  
  1. Intent 4 (non-navigation) already returns a friendly idle message.  
  2. Intent 1 (navigate) is live with Gemini + local POI fallbacks + OSRM walking routes.  
  3. Intent 3 (explore nearby) will call a dedicated NeuralSeek interpreter that accepts coordinates and responds with spoken POI lists.  
  4. Intent 2 (route check) will use the active OSRM polyline to compare user heading/distance to ensure they remain on path.
- Routing UI backlog: display ETA/distance badges on the dashboard, show turn-by-turn cards, add a “cancel route” action, and persist the last route so intent 2 can re-use it.
- Reliability work: replace hard-coded API keys with secure storage, make Gemini/STT timeouts resilient, and add retry/backoff logic for NeuralSeek/OSRM.
- Future demos: integrate real-time VAD+streaming STT (Gemini Live or Speech Services), run ElevenLabs in streaming mode, and add multi-language support once the English flow stabilizes.
