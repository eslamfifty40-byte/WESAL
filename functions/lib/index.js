"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.sabqApi = void 0;
const v2_1 = require("firebase-functions/v2");
const https_1 = require("firebase-functions/v2/https");
const sabqApp_1 = require("./sabqApp");
(0, v2_1.setGlobalOptions)({
    region: "europe-west1",
    maxInstances: 10,
});
const sabqExpressApp = (0, sabqApp_1.createSabqApiApp)();
exports.sabqApi = (0, https_1.onRequest)({
    region: "europe-west1",
    cors: false,
    timeoutSeconds: 120,
    memory: "1GiB",
    secrets: ["GEMINI_API_KEY"],
}, sabqExpressApp);
//# sourceMappingURL=index.js.map