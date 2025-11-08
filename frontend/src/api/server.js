import "./config.js";   //load ENV first
import express from "express";
import cors from "cors";
import { handleQuery } from "./router.js";

console.log("ENV URL SERVER:", process.env.NEURALSEEK_URL);

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

app.post("/api/query", async (req, res) => {
  try {
    const { message, coordinates } = req.body;

    const reply = await handleQuery(message, {
      lat: coordinates?.[0],
      lon: coordinates?.[1],
    });

    res.json(reply);
  } catch (err) {
    console.error(err);
    res.status(500).json({ error: "Internal failure" });
  }
});

app.listen(PORT, () => {
  console.log(`Navis backend listening on http://localhost:${PORT}`);
});
