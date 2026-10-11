// Firebase entry point: runs server.js as one Cloud Function behind Firebase Hosting (see ../firebase.json).
const { onRequest } = require("firebase-functions/v2/https");
require("firebase-admin/app").initializeApp();
const { handler } = require("./server");

// One instance on purpose: server.js keeps the data in memory (see the ponytail note at its top).
exports.api = onRequest({ region: "asia-southeast1", maxInstances: 1, concurrency: 80, cpu: 1, memory: "512MiB", timeoutSeconds: 300 }, handler);
