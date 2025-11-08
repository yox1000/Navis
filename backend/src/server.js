import express from "express";
import cors from "cors";
import dotenv from "dotenv";

dotenv.config();

const app = express();
const PORT = process.env.PORT || 4000;

app.use(
  cors({
    origin: process.env.CORS_ORIGIN || "*",
  })
);
app.use(express.json({ limit: "5mb" }));

app.get("/api/health", (req, res) => {
  res.json({
    status: "ok",
    timestamp: new Date().toISOString(),
  });
});

app.post("/api/query", (req, res) => {
  const { message, coordinates } = req.body || {};

  if (!message) {
    return res.status(400).json({ error: "message is required" });
  }

  const [lat, lon] = coordinates || [];

  return res.json({
    reply:
      "Navis is ready. This is a placeholder response until NeuralSeek Maestro is wired up.",
    debug: {
      receivedMessage: message,
      coordinates: { lat: lat ?? null, lon: lon ?? null },
    },
  });
});

app.listen(PORT, () => {
  console.log(`Navis backend listening on http://localhost:${PORT}`);
});
