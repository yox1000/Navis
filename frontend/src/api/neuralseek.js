// backend/src/neuralseek.js
import axios from "axios";

// const NEURALSEEK_URL = process.env.NEURALSEEK_URL;
// const NEURALSEEK_KEY = process.env.NEURALSEEK_KEY;
const NEURALSEEK_URL = 'https://stagingapi.neuralseek.com/v1/stony13/maistro';
const NEURALSEEK_KEY = 'f4d488a0-b400d693-59e0b59f-1682b8db';

// Wrap the old-style Maistro API
export async function callNeuralSeek(agent, variables = {}) {
  try {
    // Convert variables object → param list
    const params = Object.entries(variables).map(([name, value]) => ({
      name,
      value
    }));

    const payload = {
      agent,   // e.g. "main", "navigation"
      params
    };

    const resp = await axios.post(
      NEURALSEEK_URL,
      payload,
      {
        headers: {
          apikey: NEURALSEEK_KEY,
          "Content-Type": "application/json",
          "accept": "application/json"
        }
      }
    );

    return resp.data; // { result: { textOutput, variables } }
  
  } catch (err) {
    console.error("NeuralSeek error:", err?.response?.data || err);
    throw new Error("NeuralSeek request failed");
  }
}
