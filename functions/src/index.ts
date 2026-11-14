import { setGlobalOptions } from "firebase-functions/v2";
import { onRequest } from "firebase-functions/v2/https";
import { createSabqApiApp } from "./sabqApp";

setGlobalOptions({
  region: "europe-west1",
  maxInstances: 10,
});

const sabqExpressApp = createSabqApiApp();

export const sabqApi = onRequest(
  {
    region: "europe-west1",
    cors: false,
    timeoutSeconds: 120,
    memory: "1GiB",
    secrets: ["GEMINI_API_KEY"],
  },
  sabqExpressApp
);
