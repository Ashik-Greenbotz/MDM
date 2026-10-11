// Kiosk device server: tablets report here, the dashboard reads and sends commands.
// No dependencies, run with: node server.js
// ponytail: devices kept in memory and saved to devices.json; move to a real database past a few hundred tablets.
const http = require("node:http");
const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");

const PORT = Number(process.env.PORT || 8080);
const ADMIN_USER = process.env.ADMIN_USER || "admin";
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || "admin1234";
// Shared secret the tablets send; must match server_key in the app's res/values/server.xml.
const DEVICE_KEY = process.env.DEVICE_KEY || "change-me-device-key";
const DATA_FILE = path.join(__dirname, "devices.json");
const DASHBOARD = path.join(__dirname, "..", "dashboard", "index.html");
const COMMANDS = ["unlock", "lock"];

let devices = {};
try { devices = JSON.parse(fs.readFileSync(DATA_FILE, "utf8")); } catch { /* first run */ }
let dirty = false;
setInterval(() => {
  if (!dirty) return;
  dirty = false;
  fs.writeFile(DATA_FILE + ".tmp", JSON.stringify(devices, null, 1), err => {
    if (!err) fs.rename(DATA_FILE + ".tmp", DATA_FILE, () => {});
  });
}, 5000);

const same = (a, b) => {
  const x = Buffer.from(String(a)), y = Buffer.from(String(b));
  return x.length === y.length && crypto.timingSafeEqual(x, y);
};

function isAdmin(req) {
  const m = /^Basic (.+)$/.exec(req.headers.authorization || "");
  if (!m) return false;
  const [user, ...rest] = Buffer.from(m[1], "base64").toString().split(":");
  return same(user, ADMIN_USER) && same(rest.join(":"), ADMIN_PASSWORD);
}

function send(res, status, body, type = "application/json") {
  res.writeHead(status, { "Content-Type": type, "Cache-Control": "no-store" });
  res.end(type === "application/json" ? JSON.stringify(body) : body);
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    let data = "";
    req.on("data", c => {
      data += c;
      if (data.length > 64 * 1024) { reject(new Error("too large")); req.destroy(); }
    });
    req.on("end", () => { try { resolve(JSON.parse(data || "{}")); } catch (e) { reject(e); } });
  });
}

// Fields a tablet may set about itself.
const REPORT_FIELDS = ["deviceName", "model", "serial", "battery", "charging", "kioskLocked",
  "lat", "lng", "accuracy", "locationTime", "appVersion"];

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, "http://x");
  try {
    // Tablet check-in: save status, hand back any pending command.
    if (req.method === "POST" && url.pathname === "/api/report") {
      if (!same(req.headers["x-device-key"] || "", DEVICE_KEY)) return send(res, 401, { error: "bad device key" });
      const body = await readJson(req);
      if (typeof body.id !== "string" || !/^[\w-]{8,64}$/.test(body.id)) return send(res, 400, { error: "bad id" });
      const d = devices[body.id] || (devices[body.id] = { id: body.id });
      for (const k of REPORT_FIELDS) if (k in body) d[k] = body[k];
      d.lastSeen = Date.now();
      d.ip = req.socket.remoteAddress;
      const command = d.command || null;
      if (command) {
        delete d.command;
        d.lastCommand = command.type;
        d.lastCommandAt = Date.now();
      }
      dirty = true;
      return send(res, 200, { command });
    }

    // Everything else is the dashboard: admin only.
    if (!isAdmin(req)) {
      res.writeHead(401, { "WWW-Authenticate": 'Basic realm="Kiosk Dashboard"' });
      return res.end("Login required");
    }

    if (req.method === "GET" && url.pathname === "/") {
      return send(res, 200, fs.readFileSync(DASHBOARD), "text/html; charset=utf-8");
    }
    if (req.method === "GET" && url.pathname === "/api/devices") {
      return send(res, 200, { now: Date.now(), devices: Object.values(devices) });
    }
    const m = /^\/api\/devices\/([\w-]+)\/(command|name|delete)$/.exec(url.pathname);
    if (req.method === "POST" && m) {
      const d = devices[m[1]];
      if (!d) return send(res, 404, { error: "no such device" });
      const body = await readJson(req);
      if (m[2] === "command") {
        if (!COMMANDS.includes(body.type)) return send(res, 400, { error: "unknown command" });
        d.command = { type: body.type, at: Date.now() };
      } else if (m[2] === "name") {
        d.name = String(body.name || "").slice(0, 80).trim();
      } else {
        delete devices[m[1]];
      }
      dirty = true;
      return send(res, 200, { ok: true });
    }
    send(res, 404, { error: "not found" });
  } catch (e) {
    send(res, 400, { error: String(e.message || e) });
  }
});

server.listen(PORT, () => {
  console.log(`Kiosk server on http://0.0.0.0:${PORT}  (dashboard login: ${ADMIN_USER})`);
  if (!process.env.ADMIN_PASSWORD) console.log("WARNING: default admin password in use, set ADMIN_PASSWORD");
  if (!process.env.DEVICE_KEY) console.log("WARNING: default device key in use, set DEVICE_KEY (and server_key in the app)");
});
