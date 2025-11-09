
# What we are building

**Pep Scout**: a voice-first, pet-style guide that provides safe walking navigation outdoors and a small indoor wayfinding demo with real-time **object detection for safety**. The pet speaks short turn cues, the app shows a minimal path UI, and the CV layer warns about frontal obstacles for a visually impaired user.

We will:

* Support **outdoor navigation** from Library to SAC bus stop with OSRM.
* Support **one indoor demo** inside the Library with a tiny floor graph and **QR anchors**.
* Run **object detection on device** to emit “hazard” events that the web UI speaks and displays.
* Use **ElevenLabs** for TTS, **Gemini** for intent parsing and optional STT fallback, **NeuralSeek** for short cited answers over a seeded campus accessibility and transit doc pack.
* Keep the product UI as a **web app**, and wrap it in a **tiny Android shell** to get accurate sensors and on-device CV.
* Reuse **PathSense** logic where helpful, mainly the Python CV service patterns and indoor mapping ideas, but keep our UI and APIs consistent with our repo.

# What we are not building now

* No PT coaching, no exercise plans, no health prompts. We can mention it as a future use case in the pitch, not in the build.

# Sponsor mapping

* **ElevenLabs**: all spoken navigation lines and hazard warnings.
* **Gemini**: command intent parsing, short rewrite of OSRM steps, optional STT fallback.
* **NeuralSeek**: grounded answers to accessibility or campus policy questions from our small doc set.

# Platform choice

* **Web app**: Next.js or Vite for UI and logic.
* **Android wrapper**: Capacitor to expose native sensors and CV. One demo phone.
* **Serverless**: Vercel or Netlify functions to hide keys for ElevenLabs, Gemini, NeuralSeek. Optional OSRM proxy.

# Top user flows

1. Outdoor: “Pep, take me from Library to SAC bus stop”, then spoken steps and a simple map card.
2. Indoor handoff: at Library entrance the app switches to indoor mode, shows a floor image and a blue path, and advances steps by scanning QR at Entrance, Elevator, Destination.
3. Safety: CV detects a frontal obstacle and emits a hazard event. The pet speaks a short warning and the phone vibrates.
4. Q and A: user asks one policy question, NeuralSeek answers with a short cited line.

# Architecture overview

* **Client**: Web app renders maps and steps, subscribes to location and hazards, calls serverless endpoints, speaks TTS audio.
* **Android wrapper**: provides fused GPS and heading, QR scans for anchors, and on-device object detection. It sends “location” and “hazard” events into the WebView.
* **Serverless**:

  * `/api/tts` calls ElevenLabs and returns an audio URL.
  * `/api/intent` calls Gemini to classify a transcript into navigate or QA plus constraints.
  * `/api/steps` calls Gemini to rewrite raw OSRM instructions into short pet lines.
  * `/api/ask` calls NeuralSeek and returns a short answer and source hint.
  * Optional `/api/route` proxies OSRM for foot routes with steps.

# Data and configuration artifacts

* **Indoor graph JSON**: one building, two floors, nodes and edges in pixel coordinates, links between floors via elevator, flags for stairs.
* **Floor images**: two PNGs for floor 1 and floor 2.
* **QR payloads**: text values like `LIB:ENTR`, `LIB:ELEV`, `LIB:DEST`.
* **Doc pack for NeuralSeek**: 3 to 5 short pages, for example accessibility services page, step-free access notes, transit FAQ extracts.
* **Voice lines**: a small copy deck of sub-7-second phrases for directions and safety.

# Interfaces and contracts

**Location event**
Source: Android wrapper.
Fields: latitude, longitude, accuracy_m, bearing_deg, timestamp_ms.
Cadence: 2 seconds, 5 seconds if screen off.

**Hazard event**
Source: Android wrapper CV, or desktop demo via SSE.
Fields: id, ts, where.type indoor or outdoor, where.building and nodeId if indoor, geo if outdoor, kind obstacle or moving_object or stair or curb, label person or chair or bike, severity info or warn or danger, ttl_s.
Display and speak immediately, drop after ttl expires.

**Intent response**
Fields: action navigate or qa, from and to tokens or coordinates, constraints stairs boolean and hills boolean, question if QA.

**Route plan**
Fields: from, to, distance_m, geometry polyline or points, steps array with raw OSRM instruction, location point, distance to next, and rewritten short text.

# Android wrapper plugins

Expose four simple capabilities through Capacitor:

* **Location**
  FusedLocationProvider high accuracy, 2 second interval, min 3 to 5 meters. Emits continuous “location” events while guidance is active. Foreground service for reliability during screen off.

* **Heading**
  Rotation vector sensor emits azimuth every 500 ms. Used to trigger early turn prompts when heading diverges from next step.

* **QR anchors**
  ML Kit Barcode or CameraX based view. Returns the payload string for anchor matching. Used to step indoor route reliably.

* **On-device CV hazards**
  TensorFlow Lite Task Library with EfficientDet Lite0 or MobileNet SSD quant. Input 320 to 416. Emit hazard events only for central, sufficiently large boxes. Severity based on box height ratio. Run only in indoor mode or when user enables “Safety Camera” to preserve battery.

# Outdoor navigation spec

* **Routing**: public OSRM foot profile. Parameters include steps true and geometries polyline.
* **Triggers**: speak the next step when within 18 meters of maneuver location or when heading change exceeds 35 degrees toward the next bearing.
* **Reroute**: if snapped distance to polyline exceeds 30 meters for two updates, request a reroute.
* **Snap**: snap user position to nearest polyline point within 25 meters.

# Indoor navigation spec

* **Scope**: Library demo, two floors, one route.
* **Positioning**: anchor confirmation only, through QR scans. Manual Next button is a fallback.
* **Graph**: nodes include Entrance, Elevator floor 1, Elevator floor 2, Destination hallway, Destination room. Edges include costs and stairs flags.
* **Constraints**: if the user or classifier requests no stairs, use only non-stairs edges and the elevator link.
* **UI**: floor PNG as background, an SVG polyline for the path, a dot on last confirmed anchor, large “Scan” and “Next” buttons, and a “Switch to outdoor” toggle at exit.
* **Step copy**: concise lines, for example “Enter the lobby and go straight to the elevator” or “Exit on floor two and turn left.”

# CV hazard detection spec

* **Model**: efficient small detector bundled in the Android app.
* **Filter**: center crop rule plus minimum bounding box area ratio to avoid distant or side objects.
* **Cadence**: target 15 to 25 frames per second on a mid phone.
* **Output**: hazard event with label and severity.
* **Action**: in the web app, show a toast or banner, vibrate the phone, and speak one short warning with ElevenLabs.

# Web app spec

* **Home**: mic button, “from” and “to” inputs with campus pairs, chips for “no stairs” and “avoid hills”, toggle for “Indoor mode”.
* **Guide view**: current step card, big play button, pet avatar states idle listening speaking celebrate warn, mini map or floor image, “Ask” text field for NeuralSeek.
* **Summary**: distance and time, number of steps spoken, hazards observed, “Open in Google Maps” deep link for city-scale follow-on.
* **State machine**: idle, listening, planning, guiding outdoor, handoff at door, guiding indoor, finished.

# Serverless functions spec

* `/api/tts`
  Input text and voice id. Output audio URL. Cache by text hash. Prefetch the next two lines to hide latency.

* `/api/intent`
  Input transcript. Output navigate or QA structure, including constraints.

* `/api/steps`
  Input raw OSRM instructions. Output parallel array of short rewritten lines in pet tone.

* `/api/ask`
  Input question. Output short answer and a source hint string taken from NeuralSeek.

* Optional `/api/route`
  Input from and to coordinates or named tokens. Output route plan with legs and steps. Allows post-processing and stable CORS.

# Reuse from PathSense

* **CV service structure** and event shaping patterns.
* **Indoor mapping ideas** if they used a lightweight node-edge representation.
* **Any small utility** for camera pipeline or simple depth hints if it fits the phone budget.
  We will not merge their entire Next.js or vendor SDK stack. We cherry-pick modules and adapt them to our plugin and hazard event contract.

# Environment and keys

* ElevenLabs API key, Gemini API key, NeuralSeek API key in serverless environment variables.
* On Android, keep any native keys in Keystore, never commit.
* OSRM public router URL configurable.

# Team ownership and seams

* **Android and CV and indoor graph**: you. Own the wrapper plugins, QR anchors, CV model, hazard events, indoor JSON and viewer, and the indoor demo.
* **Speech and intent and TTS**: teammate A. Own STT choice, Gemini intent, ElevenLabs TTS function, copy deck.
* **Outdoor routing**: teammate B. Own OSRM plumbing, snapping, reroute rule, and triggers.
* **Docs and NeuralSeek**: teammate C. Own corpus selection, workspace config, and the `/api/ask` integration.
* **Polish and demo**: everyone for the last hour.

# Milestones and test plan

**Friday night**

* Web app skeleton. Android wrapper created. Indoor floor PNGs and JSON scaffold. One OSRM route works. One ElevenLabs call works.

**Saturday**

* Gemini intent and step rewrite. Location at 2 second cadence. Indoor QR anchors. CV emits hazard events. Web shows and speaks warnings.

**Sunday**

* Handoff outdoor to indoor at the Library door. Elevator link on floor 2. Single NeuralSeek answer with a source hint. Copy polish. 90-second demo script and screenshots.

**Acceptance tests**

* Outdoor: step triggers at 18 meters, reroute rule at 30 meters off polyline.
* Indoor: scan QR Entrance then Elevator then Destination, path advances correctly.
* CV: chair ahead triggers one hazard warning within 1 to 2 seconds.
* Q and A: one short cited answer returns within the UI.
* Audio: every line under 7 seconds, next two lines pre-fetched.

# Risks and planned fallbacks

* **Android build slips**: keep QR anchors only for indoor, drop CV, still show a complete handoff.
* **ElevenLabs outage**: use Web Speech Synthesis for the demo lines.
* **OSRM slow**: cache Library to SAC pair.
* **STT fail on device**: use browser STT on Chrome.
* **NeuralSeek connectivity**: preload one canned answer locally for the demo question.

# Stretch goals if time allows

* Simple heading-based “stairs ahead” warning using a printed marker before the staircase.
* Indoor polyline smoothing and breadcrumb dots.
* One extra building entrance path.

---

## Android Wrapper Status

**✅ COMPLETED**: Android wrapper with all native plugins implemented under `mobile/android/`
- Location: GPS tracking with 2s intervals  
- Heading: Compass updates every 500ms
- QR: CameraX + ML Kit barcode scanner
- CV: TensorFlow Lite object detection for hazards

**Next**: Download TFLite model, replace `<LAN_IP>` in config, test on device

---

# Android Phase 2 — single-app buildout

## Goal

Make the Android app fully standalone for the demo. All UI and logic live in the app. Call external APIs directly. Keep keys in Android Keystore.

## High-level features to add

1. **Navigation UI**

   * Outdoor route with OSRM, step list, mini map.
   * Indoor demo with floor images, QR anchors, and a simple path drawer.
2. **Voice**

   * STT capture for commands.
   * TTS with ElevenLabs. Cache audio by text hash.
3. **Intent**

   * Gemini to classify utterances: navigate or QA. Extract origin, destination, constraints.
4. **Q and A**

   * NeuralSeek ask endpoint. Show short answer with source hint.
5. **Safety**

   * Use your CVPlugin hazards to interrupt and warn. Vibrate and play a short line.
6. **Settings**

   * No stairs, avoid hills, voice speed, voice choice.
7. **Offline**

   * Indoor works offline. Cache last outdoor route and last 10 TTS clips.

---

## App modules and packages to create

```
mobile/android/app/src/main/java/com/navis/pepscout/
  data/
    PrefsStore.kt                 // DataStore for settings
    Keystore.kt                   // wraps Keystore for API keys
    Cache.kt                      // LRU for TTS MP3 and last route
  net/
    OsrmClient.kt                 // GET route, decode polyline
    ElevenLabsClient.kt           // POST TTS, returns file path
    GeminiClient.kt               // POST intent classify
    NeuralSeekClient.kt           // POST ask
  stt/
    SpeechCapture.kt              // Android SpeechRecognizer with partials
  tts/
    VoicePlayer.kt                // ExoPlayer wrapper, preload next 2 lines
  nav/
    OutdoorRouter.kt              // request OSRM, snap-to-route, reroute
    IndoorGraph.kt                // load JSON, Dijkstra path
    IndoorEngine.kt               // QR handoff, step advance, state machine
    StepFormatter.kt              // rewrite steps to short pet lines
  ui/
    MainActivity.kt
    HomeViewModel.kt
    screens/
      HomeScreen.kt               // mic button, quick pairs, settings
      GuideOutdoorScreen.kt       // step card, mini map, hazard banner
      GuideIndoorScreen.kt        // floor image + polyline, Scan and Next
      AskScreen.kt                // NeuralSeek answers with source
      SettingsScreen.kt
  util/
    Permissions.kt
    Geo.kt                        // bearings, distance, polyline decode
    Events.kt                     // simple bus for hazard, location, heading
assets/
  indoor/library_f1.png
  indoor/library_f2.png
  indoor/library_demo.json        // nodes, edges, elevator link
  qr/LIB_ENTR.png
  qr/LIB_ELEV.png
  qr/LIB_DEST.png
```

---

## API wiring and models

### OSRM

* URL example: `https://router.project-osrm.org/route/v1/foot/{lon},{lat};{lon},{lat}?steps=true&geometries=polyline6`
* Response you keep: distance, geometry polyline, steps with maneuver points.
* Timeouts: 5 s. If fail, show cached route if available.

### ElevenLabs

* Store `XI_API_KEY` in Keystore once. Do not hardcode.
* Endpoint: `POST /v1/text-to-speech/{voice_id}` JSON body `{text, model_id}`
* Cache in `filesDir/tts/{hash}.mp3`. Return path string.

### Gemini

* Use "text only" classify endpoint. Input: transcript. Output:

  ```
  { action:"navigate"|"qa",
    from?: {name?:string, lat?:double, lon?:double},
    to?:   {name?:string, lat?:double, lon?:double},
    constraints:{stairs:boolean, hills:boolean},
    question?: string }
  ```
* If from is empty use current GPS.

### NeuralSeek

* Minimal "ask" call. Input: `{question:string}`. Output: `{answer:string, source_hint:string}`.

---

## State machine

```
IDLE -> LISTENING -> INTENT_PARSING
-> NAV_PLANNING -> OUTDOOR_GUIDE or INDOOR_GUIDE
-> FINISHED
Hazard events can interrupt to WARN state with brief TTS, then resume.
```

---

## Concrete tasks for Claude

### A. Settings and storage

* Add DataStore keys: stairsDisabled, avoidHills, voiceId, voiceSpeed.
* Add Keystore helper for API keys. Provide setters and getters.
* Add simple "Secrets" dev screen to paste keys during testing. Do not ship in release.

### B. STT + Intent pipeline

* Implement `SpeechCapture.start()` with partials and stop.
* On final transcript call `GeminiClient.classify()` and route to either navigation or Ask.

### C. Outdoor routing

* Implement `OsrmClient.route(from, to)` and `OutdoorRouter.snapAndTrigger()`:

  * Speak a step when within 18 m of its point or heading change > 35 degrees toward next bearing.
  * Reroute if off-route by 30 m for 2 updates.

### D. Indoor demo

* Load `assets/indoor/library_demo.json`. Include floors 1 and 2, nodes ENTR, ELEV, ELEV2, DEST, plus 4 corridor nodes each floor. Elevator link between ELEV and ELEV2.
* Draw floor image and an overlay path. Advance on QR payloads. Provide manual Next as fallback.

### E. TTS pipeline

* `ElevenLabsClient.speak(text)` returns local file path.
* `VoicePlayer.play(path)` with a short queue. Preload next 2 lines.

### F. Safety

* Subscribe to your `CVPlugin` hazard events in `Events.kt`. Show a banner and play a short warning line. Vibrate pattern `[80,60,80]`. Auto dismiss after `ttl_s`.

### G. Screens

* **HomeScreen**: mic button, quick pairs ("Library → SAC Bus Stop"), Ask field, settings shortcut.
* **GuideOutdoorScreen**: step card, progress, mini map, hazard banner overlay.
* **GuideIndoorScreen**: floor image, path polyline, Scan and Next buttons, hazard banner overlay.
* **AskScreen**: single text field, last 3 answers with source hints.
* **SettingsScreen**: stairs toggle, hills toggle, voice select, voice speed.

### H. Build types

* `debug`: Secrets dev screen visible, logs enabled.
* `release`: Secrets screen hidden.

---

## Acceptance criteria

1. **Voice navigation**

   * Press mic. Say: "Pep, take me from the Library to the SAC bus stop."
   * App calls Gemini, then OSRM, then plays the first line.
   * Mini map shows a polyline. Steps advance with distance triggers.

2. **Indoor**

   * From Home choose "Indoor demo".
   * Scan LIB:ENTR, see dot at entrance. Path to elevator shows.
   * Scan LIB:ELEV, switch to floor 2. Path to destination shows.
   * Manual Next advances if QR is missed.

3. **Safety**

   * Start CV, place a chair ahead. Within 2 seconds hazard banner and short warning play. Phone vibrates.

4. **Ask**

   * Ask one accessibility question. NeuralSeek responds with a short answer and a source hint.

5. **Offline**

   * Toggle airplane mode after loading Indoor screen. QR and path still work.

---

## How to run

1. Put keys in Secrets screen on first run:

   * ELEVENLABS key, Gemini key, NeuralSeek key
2. Build and run on a real device in Android Studio.
3. Test order:

   * Home → Ask → verify answer
   * Home → Mic → navigate outdoor 30 m path
   * Home → Indoor demo → scan Entrance, Elevator, Destination
   * Safety → toggle on → verify hazard

---

## Test scripts Claude should add

* **TTS cache test**

  * Speak the same line twice. Second play should read from cache and start faster.

* **Reroute test**

  * Start a route, walk 30 m away, ensure reroute triggers and new first step speaks.

* **Constraint test**

  * Toggle "No stairs" in Settings. Indoor path must use elevator link only.

* **Hazard flood control**

  * If CV emits 5 hazards in 3 seconds, only the top severity speaks once and debounces for 2 seconds.

---

## Keep me updated

* Update `BUILD_STATUS.md` after each task with date, task, and pass or fail.
* On key errors, dump a short summary with the thrown exception and URL called.

---

## Small assets to include now

* `assets/indoor/library_f1.png` and `library_f2.png` dummy images.
* `assets/indoor/library_demo.json` minimal graph.
* `assets/qr/LIB_ENTR.png`, `LIB_ELEV.png`, `LIB_DEST.png`.

---

If you want, I can also give a minimal `library_demo.json` node list and an example OSRM request pair for "Library → SAC Bus Stop" that you can hardcode first, then replace with live.

---

# Android Phase 2.1 — Safety CV must-dos

## Goal

Make object and hurdle detection rock solid for the demo. If an obstacle blocks the planned corridor the app must speak a short corrective cue, suggest a clear side, and prevent walking into walls. Works indoors and outdoors.

## Inputs already available

* `LocationPlugin` 2 s cadence
* `HeadingPlugin` 500 ms azimuth
* `CVPlugin` detections
* `OutdoorRouter` polyline + next step
* `IndoorEngine` current floor, current edge, next node

## New data from CV to compute

Add to `cv` module:

1. **Obstacle events**: already emitted. Keep.
2. **Free-space vector**: 2D direction in camera frame toward largest navigable gap.
3. **Wall proximity**: flag when front wall plane likely within 1.5 m.

### Free-space vector spec

* Divide frame into 7 vertical bins.
* Compute occupancy per bin from detections plus optical-flow magnitude.
* Pick the longest contiguous run of low-occupancy bins that intersects camera center.
* Return center-of-gap angle in camera frame degrees, right positive.
* Payload:

```
{ type:"free_space", angle_deg: number, confidence: 0..1, ts }
```

### Wall proximity spec

* Use edge density + FOE contraction heuristic.
* If >70 percent of central columns are high gradient with no parallax for 10 frames, set `wall=true`.
* Payload:

```
{ type:"wall", distance_m: 1.0|0.7|0.5 (bucketed), ts }
```

## Fusion logic on the nav side

Create `SafetyManager` in `nav/` that subscribes to `hazard`, `free_space`, `wall`.

1. **Block detector on route**

* Maintain a 20 degree cone aligned with current heading.
* If any obstacle label in {person, chair, bike, unknown} has box area ratio ≥ 4 percent and centroid inside the cone for ≥ 300 ms, mark `blocked=true`.

2. **Side suggestion**

* When `blocked=true`, read latest `free_space` vector.
* Map angle to cue:

  * angle ≥ +12 deg -> "Small right. Take the open side."
  * angle ≤ −12 deg -> "Small left. Take the open side."
  * else -> "Pause. Path closed ahead."

3. **Wall guard**

* If `wall=true` and speed estimate > 0.5 m/s, speak "Stop. Wall ahead" and set a 2 second deadman that suppresses forward prompts.

4. **Indoor corridor guard**

* If indoor and `blocked=true` for more than 2 seconds on the current edge, compute an alternate within the same floor that deviates at most one edge. If none, ask user to backtrack 3 meters.

5. **Debounce and rate limits**

* Do not speak more than once every 2 seconds for safety cues.
* Coalesce multiple hazards to the highest severity.
* Clear `blocked` when no obstacle in cone for 1 second.

## Spoken copy rules

Keep lines under 7 seconds. Use only these phrases.

* "Careful. Obstacle ahead."  severity warn
* "Stop. Wall ahead."  severity danger
* "Small left. Take the open side."
* "Small right. Take the open side."
* "Path closed. Please wait."
* "Alternate found. Follow the blue line."

## UI

* Add a compact **Safety banner** that shows one of: Obstacle, Wall, Clear-left, Clear-right, Closed.
* Banner auto hides in 2 seconds unless danger.

## Integration points

* Outdoor: when `blocked=true`, pause step advancement until cleared, but keep reroute checks running.
* Indoor: when `blocked=true`, do not auto-advance. Offer manual Next only if banner is Clear-left or Clear-right.
* Voice queue: safety lines preempt navigation lines.

## Config knobs (put in PrefsStore with defaults)

* `safety.cone_deg = 20`
* `safety.min_box_area_ratio = 0.04`
* `safety.min_block_ms = 300`
* `safety.cooldown_ms = 2000`
* `safety.wall_trigger_frames = 10`
* `safety.wall_distance_bins = [1.5, 1.0, 0.7, 0.5]`

## Acceptance tests for demo

### A. Couch-in-path indoor

Setup: floor 1 corridor, small couch centered 3 to 4 m ahead.

1. Walk toward couch. Expect banner "Obstacle" and voice "Careful. Obstacle ahead."
2. Within 1 second receive free-space angle. If positive, voice "Small right. Take the open side." If negative, "Small left…"
3. After you sidestep, `blocked=false` within 1 second. Outdoor or indoor step guidance resumes.

### B. Wall-block at end of corridor

Place camera facing a wall at 1 m to 1.5 m.

1. Hold steady while walking slowly. Expect "Stop. Wall ahead." and 2 second deadman where no forward prompts occur.
2. Turn 30 degrees. Deadman clears. Next nav line may play.

### C. Persistent block

Stand still behind the couch for 3 seconds indoors.

1. Expect "Path closed. Please wait."
2. If an alternate one-edge detour exists in current floor, compute and say "Alternate found. Follow the blue line." Path redraws.

### D. Rate limit

Wave a hand in front of camera repeatedly.

1. Expect at most one safety voice line every 2 seconds. No spam.

### E. Outdoor curb

Put a box 1 m ahead outdoors.

1. Expect "Careful. Obstacle ahead." with banner.
2. Heading-based prompts continue after obstacle clears.

## Telemetry and logs

* Add a simple on-device log view in debug builds at Settings → Debug:

  * last 20 hazard events
  * last 10 free-space vectors
  * last 10 wall flags
  * last 10 spoken lines
* Write a single CSV per session in `filesDir/logs/` with timestamp, type, payload.

## Keys and privacy

* No images saved. Only derived events and aggregate stats.
* All external API calls keep keys in Keystore.

## Deliverables checklist for Claude

* Implement `cv/free_space` and `cv/wall` computations in CVPlugin with the specs above.
* Implement `nav/SafetyManager` with fusion logic and rate limiting.
* Wire safety to preempt TTS in `VoicePlayer`.
* Add Safety banner UI and vibration pattern `[80,60,80]`.
* Add Prefs knobs and Debug log view.
* Update RUNBOOK with the four demo scripts and a quick reset tip.

---

When Claude says done, try the couch and wall scripts exactly as written. If any step misses, I will tune the thresholds with you.

---

. Here is a clean, non-blocking checklist + file tree for Claude Code. It keeps your work isolated, uses placeholders, and lets you drop in PathSense code for reference without touching teammates' paths. No code inside, only structure, contracts, and tasks.

# Goals for your slice

* Own indoor navigation, Android wrapper sensors, and CV safety.
* Do not break teammates’ speech or outdoor OSRM work.
* Reuse PathSense logic by cloning it locally for reference only.

# Repo baseline noted

You already have:

```
~/Navis/
  frontend/     # Vite app running
  backend/      # Node/Express running
  hackthenorth-2024/   # PathSense repo clone (reference only)
```

# Additive file tree (no collisions)

Create these paths and placeholders. Keep all your work under clearly named folders.

```
Navis/
├─ frontend/
│  ├─ src/
│  │  ├─ indoor/
│  │  │  ├─ graph/
│  │  │  │  └─ library_demo.json              # indoor floor graph JSON (two floors, elevator link)
│  │  │  ├─ maps/
│  │  │  │  ├─ library_f1.png                 # placeholder floor plan
│  │  │  │  └─ library_f2.png                 # placeholder floor plan
│  │  │  ├─ components/
│  │  │  │  └─ IndoorMapViewer.tsx            # SVG overlay on PNG floor image
│  │  │  └─ readme.md                         # how to author floor graphs and anchors
│  │  ├─ safety/
│  │  │  ├─ HazardOverlay.tsx                 # small banner/toast for hazards
│  │  │  └─ useHazards.ts                     # subscribes to hazard stream or native events
│  │  ├─ native/
│  │  │  ├─ bridge.d.ts                       # Capacitor plugin interfaces only
│  │  │  └─ readme.md                         # how web listens for native events
│  │  └─ routes/
│  │     └─ IndoorDemoPage.tsx                # one page for the demo flow
│  ├─ public/
│  │  ├─ qr/
│  │  │  ├─ LIB_ENTR.png                      # printable QR for Entrance
│  │  │  ├─ LIB_ELEV.png                      # printable QR for Elevator
│  │  │  └─ LIB_DEST.png                      # printable QR for Destination
│  │  └─ audio/.gitkeep                       # ElevenLabs cache target
│  └─ docs/
│     └─ copy_deck.md                         # sub-7-second voice lines, safety phrases
│
├─ backend/
│  ├─ addons/
│  │  ├─ hazards/
│  │  │  ├─ router.ts                         # SSE: GET /api/hazards/stream, POST /api/hazards
│  │  │  └─ readme.md
│  │  └─ indoor/
│  │     └─ readme.md                         # indoor notes, not code
│  ├─ env/
│  │  └─ .env.example.append                  # add ELEVEN_API_KEY, GEMINI_API_KEY, NEURALSEEK_API_KEY
│  └─ docs/
│     └─ contracts.md                         # event schemas copied below
│
├─ mobile/
│  └─ android/                                # Capacitor wrapper project root
│     ├─ plugins/
│     │  ├─ location/                         # Fused location service plugin
│     │  ├─ heading/                          # rotation vector plugin
│     │  ├─ qr/                               # ML Kit barcode scanner screen
│     │  └─ cv/                               # TFLite detector plugin, emits hazard events
│     └─ readme.md                            # build, run, permissions, test steps
│
├─ third_party/
│  └─ pathsense/                              # optional mirror or symlink to hackthenorth-2024
│     └─ readme.md                            # list of folders we borrow patterns from
│
└─ RUNBOOK.md                                  # one-page run and demo script
```

# Contracts to pin down

Keep these in `backend/docs/contracts.md` and mirror in `frontend/src/native/bridge.d.ts`.

**Location event**

* `lat` number, `lon` number, `accuracy_m` number, `bearing_deg` number, `ts` ms
* cadence 2 s, drop to 5 s screen off

**Hazard event**

* `id` string, `ts` ms
* `where`: `{type:"indoor"| "outdoor", building?:string, nodeId?:string}`
* `geo`: `{lat:number, lon:number, accuracy_m:number}` optional
* `kind`: `obstacle|moving_object|stair|curb|door`
* `label`: `person|chair|bike|unknown`
* `severity`: `info|warn|danger`
* `ttl_s`: number

**Intent response**

* `action`: `navigate|qa`
* `from`: token or coords
* `to`: token or coords
* `constraints`: `{stairs:boolean, hills:boolean}`
* `question`: string when `action=qa`

**Route plan**

* `from`, `to`
* `distance_m`
* `geometry`: polyline or points
* `steps`: array of `{raw, text, distance, location}`

# Claude Code checklist

Give this list to Claude Code. It can scaffold files and fill them with code later.

## A. Frontend isolation

1. Create `frontend/src/indoor/graph/library_demo.json` with two floors, elevator link, and three anchors: ENTR, ELEV, DEST. Put placeholders only.
2. Create `frontend/src/indoor/maps/library_f1.png` and `library_f2.png` as dummy 1000x700 PNGs.
3. Create `IndoorMapViewer.tsx` that renders an image and an SVG polyline. Do not import any teammate pages. Export a single `<IndoorDemoPage />`.
4. Create `frontend/src/safety/HazardOverlay.tsx` and `useHazards.ts`. Subscribe to either:

   * EventSource at `/api/hazards/stream` in browser, or
   * Capacitor native event `hazard` when on Android.
5. Create `frontend/src/native/bridge.d.ts` with plugin interfaces: `LocPlugin`, `HeadingPlugin`, `QrPlugin`, `CVPlugin`. Only types and event names, no code.
6. Add `frontend/public/qr/*.png` placeholder QR files for printing.
7. Add `frontend/docs/copy_deck.md` with sample short lines and hazard phrases.
8. Do not modify existing pages your teammates own. Link your demo page under a new route like `/indoor-demo` only.

## B. Backend addon, no collisions

1. Under `backend/addons/hazards`, add an Express router with:

   * `GET /api/hazards/stream` SSE
   * `POST /api/hazards` fan-out to SSE clients
   * Write docs in `readme.md` about JSON schema above
2. In the backend main server, mount this router by adding one line. Keep the path as `/api/hazards`.
3. Add `backend/env/.env.example.append` with the three API keys. Teammates can merge into their `.env` later.
4. Do not touch their OSRM, TTS, or intent routes. Keep yours fully separate.

## C. Android wrapper skeleton

1. Initialize Capacitor in `Navis/` and add Android platform under `mobile/android/`.
2. Create plugins folders:

   * `plugins/location` with start, stop, Event `location`
   * `plugins/heading` with start, stop, Event `heading`
   * `plugins/qr` with a scanner activity that returns string payload
   * `plugins/cv` that loads a TFLite model and emits `hazard` events
3. Add `mobile/android/readme.md` with:

   * Permissions list: camera, fine location, foreground service, notifications on Android 13
   * Build steps in Android Studio
   * How to test each plugin from the web app page
4. Do not embed any web code in the app. The app only hosts the WebView, exposes plugins, and points to the local dev URL or a built frontend.

## D. Indoor assets and anchors

1. Author `library_demo.json` with:

   * floor 1 nodes: ENTR, ELEV, corridor nodes
   * floor 2 nodes: ELEV2, DEST
   * links: elevator from ELEV to ELEV2
   * stairs edges flagged with `"stairs": true` for future use
2. Place anchors:

   * ENTR → QR payload `LIB:ENTR`
   * ELEV → QR payload `LIB:ELEV`
   * DEST → QR payload `LIB:DEST`
3. In `IndoorMapViewer.tsx` show a Next button and a Scan button. Only wire event names. No logic here that touches teammate routes.

## E. PathSense reuse without risk

1. Keep `hackthenorth-2024/` at the root for reference.
2. Add `third_party/pathsense/readme.md` with a small map:

   * camera pipeline folder names you plan to borrow concepts from
   * any indoor mapping shape files they used
   * do not import their code into the build
3. If you want to copy tiny utilities later, paste into `mobile/android/plugins/cv/notes.txt` first so the team can review before merge.

## F. Contracts and runbook

1. Write `backend/docs/contracts.md` with the four schemas above.
2. Write `RUNBOOK.md` that includes:

   * Start frontend, start backend
   * Print QR from `frontend/public/qr`
   * Android: install APK, allow permissions, open app
   * Outdoor segment handoff rules
   * Indoor demo path sequence
   * Hazard test: place a chair, verify warning voice and vibration

## G. Smoke tests

1. Desktop only: open `/indoor-demo`, open DevTools network tab, start SSE stream with `GET /api/hazards/stream`, then `curl` POST a fake hazard to see overlay update.
2. Phone with wrapper: verify `location` and `hazard` events arrive in the page.
3. QR: open scanner, confirm payloads `LIB:ENTR`, `LIB:ELEV`, `LIB:DEST` appear in the page.
4. No teammate breakage: run their speech and outdoor pages. Your imports must not appear there.

# Minimal changes to existing code

* Backend: one `app.use()` to mount `/api/hazards`.
* Frontend: one new route to your demo page.
  No edits to teammates’ pages, reducers, or endpoints.

# Placeholders to include now

* `library_f1.png` and `library_f2.png` simple floor sketches.
* `library_demo.json` with 6 to 10 nodes per floor.
* Three QR PNGs with the payload text printed under each code.
* An empty `audio/.gitkeep` for TTS cache.
* A one-line `readme.md` in every new folder so Git keeps them.

# Short task list per day

**Day 1**

* Create folders and placeholders.
* Add hazards SSE router and mount it.
* Add demo route `/indoor-demo` that renders the floor image and an empty overlay.

**Day 2**

* Wire `useHazards` to SSE and native event.
* Add QR scan button and event handler stub.
* Write indoor graph loader and draw a static polyline between ENTR and DEST.

**Day 3**

* Android plugins first run: location, then QR, then CV hazard.
* Run through the demo sequence. Fix fit-and-finish.

# Notes for Claude Code

* Do not modify any file outside the listed new folders.
* Keep all new imports scoped to the new demo route.
* When creating plugin interface types in `bridge.d.ts`, only declare event names and payload shapes. Do not ship code in this pass.
* Keep all images and JSON small to commit cleanly.

This gives Claude Code a safe, isolated scaffold that you can extend fast, while teammates continue their work without conflicts.
