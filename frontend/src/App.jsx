import { useMemo, useState } from "react";

const demoQuestions = [
  "How far to the next turn?",
  "Is there a coffee shop nearby?",
  "Guide me to the nearest exit.",
];

function App() {
  const [isRecording, setIsRecording] = useState(false);
  const [transcript, setTranscript] = useState("");
  const [assistantReply, setAssistantReply] = useState(
    "Ask Navis anything about your surroundings."
  );

  const nextSuggestion = useMemo(() => {
    if (!transcript) return demoQuestions[0];
    const currentIndex =
      demoQuestions.findIndex((q) => q === transcript) ?? 0;
    return demoQuestions[(currentIndex + 1) % demoQuestions.length];
  }, [transcript]);

  const handleMicToggle = () => {
    setIsRecording((prev) => !prev);
    if (!isRecording) {
      setTranscript("Listening...");
      setAssistantReply("Processing your location and question...");
    } else {
      setTranscript(nextSuggestion);
      setAssistantReply(
        "Head north for 60 feet, then turn right. Watch for the tactile strip on your left."
      );
    }
  };

  return (
    <div className="min-h-screen bg-slate-950 text-slate-50">
      <header className="border-b border-slate-800 bg-slate-900/70 backdrop-blur">
        <div className="mx-auto flex max-w-6xl flex-col gap-3 px-6 py-6 md:flex-row md:items-center md:justify-between">
          <div>
            <p className="text-xs uppercase tracking-[0.3em] text-emerald-300">
              Navis
            </p>
            <h1 className="text-2xl font-semibold">Spatial Companion</h1>
            <p className="text-sm text-slate-300">
              Location-aware guidance, tailored for blind and low-vision
              explorers.
            </p>
          </div>
          <button className="rounded-full border border-emerald-300/40 bg-emerald-400/10 px-5 py-2 text-sm font-medium text-emerald-200 transition hover:bg-emerald-400/20">
            Connect Device
          </button>
        </div>
      </header>

      <main className="mx-auto grid max-w-6xl gap-6 px-6 py-8 lg:grid-cols-5">
        <section className="space-y-6 rounded-2xl border border-slate-800 bg-slate-900/40 p-6 lg:col-span-3">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-xs uppercase tracking-[0.3em] text-slate-400">
                Live Route
              </p>
              <h2 className="text-xl font-semibold">Navigation preview</h2>
            </div>
            <span className="rounded-full bg-emerald-400/10 px-3 py-1 text-xs text-emerald-200">
              OSRM ↯ MapLibre
            </span>
          </div>
          <div className="h-[420px] rounded-xl border border-slate-800 bg-gradient-to-br from-slate-900 to-slate-800 p-4 text-sm text-slate-400">
            Map rendering placeholder. This is where MapLibre will show the
            route, current position, and tactile cues.
          </div>
          <div className="rounded-xl border border-slate-800 bg-slate-900/70 p-4 text-sm text-slate-300">
            <p className="font-medium text-slate-100">Route summary</p>
            <ul className="mt-2 space-y-1 text-xs text-slate-400">
              <li>• Location lock: Ready</li>
              <li>• OSRM service: Connected</li>
              <li>• Maestro context: Awaiting prompt</li>
            </ul>
          </div>
        </section>

        <section className="space-y-5 rounded-2xl border border-slate-800 bg-slate-900/60 p-6 lg:col-span-2">
          <div>
            <p className="text-xs uppercase tracking-[0.3em] text-slate-400">
              Voice console
            </p>
            <h2 className="text-xl font-semibold text-white">Ask Navis</h2>
            <p className="text-sm text-slate-400">
              Hold the mic button, speak your question, and hear the Maestro +
              OSRM answer immediately.
            </p>
          </div>

          <div className="rounded-xl border border-slate-800 bg-slate-950/60 p-4">
            <p className="text-xs uppercase tracking-[0.3em] text-slate-500">
              Transcript
            </p>
            <p className="mt-3 text-base text-slate-100">
              {transcript || "Tap record to capture a question."}
            </p>
          </div>

          <div className="rounded-xl border border-slate-800 bg-slate-950/60 p-4">
            <p className="text-xs uppercase tracking-[0.3em] text-slate-500">
              Navis reply
            </p>
            <p className="mt-3 text-base text-emerald-100">{assistantReply}</p>
          </div>

          <button
            onClick={handleMicToggle}
            className={`flex w-full items-center justify-center gap-3 rounded-2xl border px-6 py-4 text-lg font-semibold transition ${
              isRecording
                ? "border-rose-500/40 bg-rose-500/10 text-rose-100"
                : "border-emerald-400/40 bg-emerald-400/10 text-emerald-100"
            }`}
          >
            <span
              className={`h-3 w-3 rounded-full ${
                isRecording ? "bg-rose-400 animate-pulse" : "bg-emerald-400"
              }`}
            />
            {isRecording ? "Listening..." : "Tap to Talk"}
          </button>

          <div className="rounded-xl border border-slate-800 bg-slate-900/40 p-4 text-xs text-slate-400">
            Tip: try asking “{nextSuggestion}”
          </div>
        </section>
      </main>
    </div>
  );
}

export default App;
