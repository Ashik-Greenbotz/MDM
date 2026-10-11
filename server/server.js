// Device portal server: devices check in here, the web portal reads and sends commands.
// No dependencies, run with: node server.js
// ponytail: everything kept in memory and saved to JSON files; move to a real database past a few thousand devices.
const http = require("node:http");
const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");

const PORT = Number(process.env.PORT || 8080);
const ADMIN_USER = process.env.ADMIN_USER || "admin";
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || "admin1234";
// Shared secret the devices send; must match server_key in the app's res/values/server.xml.
const DEVICE_KEY = process.env.DEVICE_KEY || "change-me-device-key";
const DEVICES_FILE = path.join(__dirname, "devices.json");
const PORTAL_FILE = path.join(__dirname, "portal.json");
const UPLOADS = path.join(__dirname, "uploads");
const LOGS_DIR = path.join(__dirname, "logs");
const DASHBOARD_DIR = path.join(__dirname, "..", "dashboard");
const STATIC = {
  "/": ["index.html", "text/html; charset=utf-8"], "/login": ["login.html", "text/html; charset=utf-8"],
  "/logo.png": ["logo.png", "image/png"], "/manifest.json": ["manifest.json", "application/manifest+json"],
};
const PUBLIC_PAGES = ["/login", "/logo.png", "/manifest.json"];
const SESSION_MS = 12 * 60 * 60 * 1000;
const ONLINE_MS = 90 * 1000;
const OFFLINE_ALERT_MS = 2 * 60 * 1000; // a restart takes about a minute, so no alarm for that
const LOW_BATTERY = 20;
const HISTORY_EVERY_MS = 5 * 60 * 1000, HISTORY_KEEP = 288; // 24 h of 5-minute samples
// Devices take the server's time zone (fixes terminals shipped on UTC). Override with TIME_ZONE=Asia/Singapore etc.
const TIME_ZONE = process.env.TIME_ZONE || Intl.DateTimeFormat().resolvedOptions().timeZone;
const MAX_EVENTS = 5000, MAX_UPLOAD = 2048 * 1024 * 1024;

// Commands each device type understands. A device's type comes from its own check-in; old tablets send none.
const ANDROID = ["lockscreen", "reboot", "ring", "screenshot", "install", "file", "logs"];
const COMMANDS = {
  "android-kiosk": ["unlock", "lock", "singleapp", ...ANDROID],
  android: ANDROID,
  telpo: ANDROID,
  windows: ["reboot"],
  linux: ["reboot"],
  iot: ["reboot"],
};
const typeOf = d => (COMMANDS[d.type] ? d.type : "android-kiosk");
const label = d => d.name || d.deviceName || d.id.slice(0, 8);
const rid = () => crypto.randomBytes(8).toString("hex");

for (const dir of [UPLOADS, path.join(UPLOADS, "apps"), path.join(UPLOADS, "files"), LOGS_DIR]) fs.mkdirSync(dir, { recursive: true });
const load = (file, empty) => { try { return JSON.parse(fs.readFileSync(file, "utf8")); } catch { return empty; } };
let devices = load(DEVICES_FILE, {});
const portal = Object.assign({ events: [], alerts: [], tasks: [], tokens: [], apps: [], files: [], sites: [], pending: [], kioskApp: null }, load(PORTAL_FILE, {}));
// Every device has a 4-digit Device ID (d.code) shown on its screen. An admin can create one in the portal first
// ("Add device", kept in portal.pending) and type it on the device to link it.
const usedCodes = () => new Set([...Object.values(devices).map(d => d.code), ...portal.pending.map(x => x.code)]);
function newCode() {
  const used = usedCodes();
  if (used.size >= 9000) throw new Error("all 4-digit IDs are in use");
  for (;;) {
    const c = String(1000 + crypto.randomInt(9000));
    if (!used.has(c)) return c;
  }
}
for (const d of Object.values(devices)) if (!d.code) d.code = newCode();
// A device's site is stored in d.group (the portal calls groups "sites"). Sites can also exist before any device is in them.

let dirty = false;
const save = (file, data) => fs.writeFile(file + ".tmp", JSON.stringify(data), err => { if (!err) fs.rename(file + ".tmp", file, () => {}); });
setInterval(() => {
  if (!dirty) return;
  dirty = false;
  save(DEVICES_FILE, devices);
  save(PORTAL_FILE, portal);
}, 5000);

function log(deviceId, kind, text) {
  portal.events.push({ at: Date.now(), deviceId, kind, text });
  if (portal.events.length > MAX_EVENTS) portal.events.splice(0, portal.events.length - MAX_EVENTS);
  dirty = true;
}

const same = (a, b) => {
  const x = Buffer.from(String(a)), y = Buffer.from(String(b));
  return x.length === y.length && crypto.timingSafeEqual(x, y);
};

// Portal login sessions: random token in an HttpOnly cookie. Lost on server restart (admins sign in again).
const sessions = new Map();
const sessionId = req => (/(?:^|;\s*)sid=([a-f0-9]{64})/.exec(req.headers.cookie || "") || [])[1];

function isAdmin(req) {
  const sid = sessionId(req);
  if (sid && sessions.get(sid) > Date.now()) return true;
  // Basic auth still accepted for scripts (curl -u admin:password).
  const m = /^Basic (.+)$/.exec(req.headers.authorization || "");
  if (!m) return false;
  const [user, ...rest] = Buffer.from(m[1], "base64").toString().split(":");
  return same(user, ADMIN_USER) && same(rest.join(":"), ADMIN_PASSWORD);
}
const isDevice = req => same(req.headers["x-device-key"] || "", DEVICE_KEY);

// Slow down password guessing: 5 wrong tries per IP, then locked out for a minute.
const failures = new Map();
function loginBlocked(ip) {
  const f = failures.get(ip);
  return f && f.count >= 5 && Date.now() - f.at < 60 * 1000;
}
function loginFailed(ip) {
  const f = failures.get(ip);
  const fresh = !f || Date.now() - f.at > 60 * 1000;
  failures.set(ip, { count: fresh ? 1 : f.count + 1, at: Date.now() });
}

function send(res, status, body, type = "application/json") {
  res.writeHead(status, { "Content-Type": type, "Cache-Control": "no-store" });
  res.end(type === "application/json" ? JSON.stringify(body) : body);
}

function readBody(req, limit = 64 * 1024) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on("data", c => {
      size += c.length;
      if (size > limit) { reject(new Error("too large")); req.destroy(); } else chunks.push(c);
    });
    req.on("end", () => resolve(Buffer.concat(chunks)));
    req.on("error", reject);
  });
}
const readJson = async (req, limit) => JSON.parse((await readBody(req, limit)).toString() || "{}");

// Streams an upload to disk, returning its size and sha256 (hex + base64url for Android provisioning).
// Any failure (too big, browser cancelled, disk error) deletes the partial file and rejects; it never throws.
function receiveFile(req, dest) {
  return new Promise((resolve, reject) => {
    const hash = crypto.createHash("sha256"), out = fs.createWriteStream(dest + ".part");
    let size = 0, failed = false;
    const fail = err => {
      if (failed) return;
      failed = true;
      req.unpipe(out);
      out.destroy();
      fs.rm(dest + ".part", () => {});
      reject(err);
    };
    out.on("error", fail);
    req.on("error", fail);
    req.on("aborted", () => fail(new Error("upload cancelled")));
    req.on("data", c => {
      size += c.length;
      if (size > MAX_UPLOAD) { fail(new Error(`file too large (max ${MAX_UPLOAD / 1024 / 1024} MB)`)); req.destroy(); return; }
      hash.update(c);
    });
    out.on("finish", () => {
      if (failed) return;
      fs.rename(dest + ".part", dest, err => {
        if (err) return fail(err);
        const digest = hash.digest();
        resolve({ size, sha256: digest.toString("hex"), checksum: digest.toString("base64url") });
      });
    });
    req.pipe(out);
  });
}

function sendFile(res, file, name) {
  fs.stat(file, (err, st) => {
    if (err) return send(res, 404, { error: "not found" });
    res.writeHead(200, { "Content-Type": "application/octet-stream", "Content-Length": st.size,
      "Content-Disposition": `attachment; filename="${String(name).replace(/[^\w.-]/g, "_")}"` });
    fs.createReadStream(file).pipe(res);
  });
}

// Fields a device may set about itself.
const REPORT_FIELDS = ["type", "deviceName", "model", "serial", "battery", "charging", "kioskLocked",
  "lat", "lng", "accuracy", "locationTime", "appVersion", "os", "uptime", "localIp", "info",
  "cpu", "ram", "disk", "temp", "signal", "mode", "singleApp", "launcherApps"];
const HEALTH = ["battery", "cpu", "ram", "disk", "temp"];

// Queues a command for every device in the list that supports it. Returns how many got it.
function queue(list, cmd, by) {
  let n = 0;
  for (const d of list) {
    if (!COMMANDS[typeOf(d)].includes(cmd.type)) continue;
    (d.queue ||= []).push({ ...cmd, id: rid(), at: Date.now() });
    if (d.queue.length > 20) d.queue.shift();
    log(d.id, "command", `${cmd.type}${cmd.name ? " " + cmd.name : ""} queued${by ? " by " + by : ""}`);
    n++;
  }
  dirty = true;
  return n;
}

// target: { ids: [...] } | { group: "x" } | { type: "telpo" } | { all: true }
function pick(target = {}) {
  const all = Object.values(devices);
  if (Array.isArray(target.ids)) return target.ids.map(id => devices[id]).filter(Boolean);
  if (target.group != null) return all.filter(d => (d.group || "") === target.group);
  if (target.type) return all.filter(d => typeOf(d) === target.type);
  return target.all ? all : [];
}

function commandFrom(body) {
  const type = String(body.type || "");
  if (type === "install") {
    const app = portal.apps.find(a => a.id === body.appId);
    if (!app) throw new Error("pick an uploaded app");
    return { type, url: `/download/app/${app.id}.apk`, sha256: app.sha256, name: app.label };
  }
  if (type === "file") {
    const f = portal.files.find(x => x.id === body.fileId);
    if (!f) throw new Error("pick an uploaded file");
    return { type, url: `/download/file/${f.id}`, sha256: f.sha256, name: f.name };
  }
  if (type === "singleapp") {
    const pkg = String(body.package || "");
    if (pkg && !/^[\w.]{3,200}$/.test(pkg)) throw new Error("bad app");
    return { type, package: pkg, name: pkg ? body.appName || pkg : "off" };
  }
  if (!Object.values(COMMANDS).flat().includes(type)) throw new Error("unknown command");
  return { type };
}

// ---- alerts: opened when a condition starts, resolved when it ends (crashes stay until acknowledged) ----
function setAlert(d, kind, on, text) {
  const open = portal.alerts.find(a => a.deviceId === d.id && a.kind === kind && !a.resolvedAt);
  if (on && !open) {
    portal.alerts.push({ id: rid(), deviceId: d.id, kind, text, at: Date.now() });
    log(d.id, "alert", text);
  } else if (!on && open) {
    open.resolvedAt = Date.now();
    log(d.id, "resolved", `${kind} alert cleared`);
  }
}
function checkAlerts() {
  const now = Date.now();
  for (const d of Object.values(devices)) {
    const online = now - d.lastSeen < ONLINE_MS;
    if (d.wasOnline && !online) log(d.id, "offline", `${label(d)} went offline`);
    d.wasOnline = online;
    const offline = now - d.lastSeen > OFFLINE_ALERT_MS;
    setAlert(d, "offline", offline, `${label(d)} offline since ${new Date(d.lastSeen).toLocaleString()}`);
    setAlert(d, "battery", !offline && d.battery != null && d.battery < LOW_BATTERY && !d.charging, `${label(d)} battery low (${d.battery}%)`);
  }
  // Keep the last 1000 resolved alerts.
  const resolved = portal.alerts.filter(a => a.resolvedAt);
  if (resolved.length > 1000) {
    const drop = new Set(resolved.slice(0, resolved.length - 1000));
    portal.alerts = portal.alerts.filter(a => !drop.has(a));
  }
}
setInterval(checkAlerts, 15 * 1000);

// ---- scheduled tasks: run at HH:MM (server's local time) on the chosen weekdays ----
function runTask(t, by) {
  const n = queue(pick(t.target), commandFrom(t), by);
  t.lastRun = Date.now();
  log(null, "task", `Task "${t.name}" sent ${t.type} to ${n} device${n === 1 ? "" : "s"}`);
  return n;
}
setInterval(() => {
  const now = new Date(), hhmm = now.toTimeString().slice(0, 5), today = now.toDateString();
  for (const t of portal.tasks) {
    if (!t.enabled || t.time !== hhmm || !t.days.includes(now.getDay()) || t.lastRunDay === today) continue;
    t.lastRunDay = today;
    try { runTask(t, "schedule"); } catch (e) { log(null, "task", `Task "${t.name}" failed: ${e.message}`); }
  }
}, 20 * 1000);

// The kiosk app used for QR enrollment: the one picked in the portal, else the first app named "kiosk".
function kioskApk() {
  const app = portal.apps.find(a => a.id === portal.kioskApp) || portal.apps.find(a => /kiosk/i.test(a.label));
  return app ? { url: `/download/app/${app.id}.apk`, checksum: app.checksum, label: app.label } : null;
}
(function importBuiltApk() {
  const built = path.join(__dirname, "..", "Kiosk app phase2.apk");
  if (portal.apps.length || !fs.existsSync(built)) return;
  const buf = fs.readFileSync(built), id = rid(), digest = crypto.createHash("sha256").update(buf).digest();
  fs.writeFileSync(path.join(UPLOADS, "apps", id + ".apk"), buf);
  portal.apps.push({ id, label: "Kiosk app", size: buf.length, sha256: digest.toString("hex"), checksum: digest.toString("base64url"), at: Date.now() });
  portal.kioskApp = id;
  dirty = true;
})();

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, "http://x");
  const p = url.pathname;
  try {
    // ---------------- device endpoints (device key) ----------------
    if (req.method === "POST" && p === "/api/report") {
      if (!isDevice(req)) return send(res, 401, { error: "bad device key" });
      const body = await readJson(req);
      if (typeof body.id !== "string" || !/^[\w-]{8,64}$/.test(body.id)) return send(res, 400, { error: "bad id" });
      const fresh = !devices[body.id];
      const d = devices[body.id] || (devices[body.id] = { id: body.id, firstSeen: Date.now() });
      const wasOnline = d.lastSeen && Date.now() - d.lastSeen < ONLINE_MS;
      for (const k of REPORT_FIELDS) if (k in body) d[k] = body[k];
      if (d.info !== undefined && (typeof d.info !== "object" || Array.isArray(d.info))) delete d.info;
      d.lastSeen = Date.now();
      d.ip = req.socket.remoteAddress;
      if (!d.code) d.code = newCode();
      if (fresh) log(d.id, "enroll", `${label(d)} registered (${typeOf(d)}), Device ID ${d.code}`);
      else if (!wasOnline) log(d.id, "online", `${label(d)} came online`);
      d.wasOnline = true;
      // Enrollment token (from the QR code or typed on the device): puts the device in the token's group once.
      if (typeof body.token === "string" && body.token && body.token !== d.token) {
        const t = portal.tokens.find(x => same(x.token, body.token));
        if (t) {
          d.token = body.token;
          if (t.group) d.group = t.group;
          t.uses = (t.uses || 0) + 1;
          log(d.id, "enroll", `${label(d)} enrolled with token "${t.label}"${t.group ? " into site " + t.group : ""}`);
        }
      }
      for (const e of Array.isArray(body.events) ? body.events.slice(0, 20) : []) log(d.id, "device", String(e).slice(0, 500));
      if (typeof body.crash === "string" && body.crash) {
        portal.alerts.push({ id: rid(), deviceId: d.id, kind: "crash", text: `App crashed on ${label(d)}`, detail: body.crash.slice(0, 4000), at: Date.now() });
        log(d.id, "alert", `App crashed on ${label(d)}: ${body.crash.split("\n")[0].slice(0, 200)}`);
      }
      if (!d.historyAt || Date.now() - d.historyAt >= HISTORY_EVERY_MS) {
        d.historyAt = Date.now();
        (d.history ||= []).push([d.historyAt, ...HEALTH.map(k => d[k] ?? null)]);
        if (d.history.length > HISTORY_KEEP) d.history.shift();
      }
      delete d.command; // old single-command format
      const command = d.queue?.shift() || null;
      if (command) {
        d.lastCommand = command.type;
        d.lastCommandAt = Date.now();
        log(d.id, "delivered", `${command.type} delivered`);
      }
      dirty = true;
      return send(res, 200, { command, code: d.code, timeZone: TIME_ZONE });
    }
    // Device side of "Add device": the admin typed a 4-digit ID on the device.
    if (req.method === "POST" && p === "/api/link") {
      if (!isDevice(req)) return send(res, 401, { error: "bad device key" });
      const body = await readJson(req);
      const d = devices[body.id], code = String(body.code || "");
      if (!d) return send(res, 404, { error: "This device hasn't checked in yet. Check the server address and Wi-Fi." });
      if (!/^\d{4}$/.test(code)) return send(res, 400, { error: "The Device ID is 4 digits" });
      if (d.code === code) return send(res, 200, { ok: true, code, name: label(d) });
      const owner = Object.values(devices).find(x => x.code === code);
      if (owner) return send(res, 409, { error: `ID ${code} already belongs to ${label(owner)}` });
      const slot = portal.pending.find(x => x.code === code);
      if (!slot) return send(res, 404, { error: `ID ${code} not found. Create it in the portal first: Devices → Add device.` });
      portal.pending = portal.pending.filter(x => x !== slot);
      d.code = code;
      if (slot.name) d.name = slot.name;
      if (slot.group) d.group = slot.group;
      log(d.id, "enroll", `${label(d)} linked with Device ID ${code}`);
      dirty = true;
      return send(res, 200, { ok: true, code, name: label(d) });
    }
    if (req.method === "POST" && p === "/api/device-logs") {
      if (!isDevice(req)) return send(res, 401, { error: "bad device key" });
      const id = String(req.headers["x-device-id"] || "");
      if (!devices[id]) return send(res, 404, { error: "unknown device" });
      fs.writeFileSync(path.join(LOGS_DIR, id + ".txt"), await readBody(req, 2 * 1024 * 1024));
      devices[id].logsAt = Date.now();
      log(id, "logs", "Logs uploaded");
      return send(res, 200, { ok: true });
    }
    // Screenshot from a device (JPEG body), or ?error=... when it couldn't take one.
    if (req.method === "POST" && p === "/api/screenshot") {
      if (!isDevice(req)) return send(res, 401, { error: "bad device key" });
      const id = String(req.headers["x-device-id"] || "");
      const d = devices[id];
      if (!d) return send(res, 404, { error: "unknown device" });
      const error = url.searchParams.get("error");
      if (error) {
        d.screenshotError = { at: Date.now(), text: error.slice(0, 300) };
      } else {
        const jpeg = await readBody(req, 8 * 1024 * 1024);
        if (jpeg.length < 4 || jpeg[0] !== 0xff || jpeg[1] !== 0xd8) return send(res, 400, { error: "not a JPEG" });
        fs.writeFileSync(path.join(LOGS_DIR, id + ".jpg"), jpeg);
        d.screenshotAt = Date.now();
        log(id, "screenshot", "Screenshot received");
      }
      dirty = true;
      return send(res, 200, { ok: true });
    }
    // Apps are public so a factory-reset device can download the kiosk app while enrolling by QR.
    let m = /^\/download\/app\/([a-f0-9]{16})\.apk$/.exec(p);
    if (req.method === "GET" && m) {
      const app = portal.apps.find(a => a.id === m[1]);
      return app ? sendFile(res, path.join(UPLOADS, "apps", app.id + ".apk"), app.label + ".apk") : send(res, 404, { error: "not found" });
    }
    m = /^\/download\/file\/([a-f0-9]{16})$/.exec(p);
    if (req.method === "GET" && m) {
      if (!isDevice(req) && !isAdmin(req)) return send(res, 401, { error: "login required" });
      const f = portal.files.find(x => x.id === m[1]);
      return f ? sendFile(res, path.join(UPLOADS, "files", f.id), f.name) : send(res, 404, { error: "not found" });
    }

    // ---------------- login ----------------
    if (req.method === "POST" && p === "/api/login") {
      const ip = req.socket.remoteAddress;
      if (loginBlocked(ip)) return send(res, 429, { error: "Too many attempts, wait a minute" });
      const body = await readJson(req);
      if (!same(body.user || "", ADMIN_USER) || !same(body.password || "", ADMIN_PASSWORD)) {
        loginFailed(ip);
        return send(res, 401, { error: "Wrong username or password" });
      }
      failures.delete(ip);
      const sid = crypto.randomBytes(32).toString("hex");
      sessions.set(sid, Date.now() + SESSION_MS);
      res.setHeader("Set-Cookie", `sid=${sid}; HttpOnly; SameSite=Strict; Path=/; Max-Age=${SESSION_MS / 1000}`);
      return send(res, 200, { ok: true });
    }
    if (req.method === "POST" && p === "/api/logout") {
      sessions.delete(sessionId(req));
      res.setHeader("Set-Cookie", "sid=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0");
      return send(res, 200, { ok: true });
    }

    const page = req.method === "GET" && STATIC[p];
    if (page && (PUBLIC_PAGES.includes(p) || isAdmin(req))) {
      return send(res, 200, fs.readFileSync(path.join(DASHBOARD_DIR, page[0])), page[1]);
    }
    if (page) {
      res.writeHead(302, { Location: "/login" });
      return res.end();
    }

    // ---------------- portal API: admin only ----------------
    if (!isAdmin(req)) return send(res, 401, { error: "login required" });
    const body = req.method === "POST" && p !== "/api/upload" ? await readJson(req) : {};
    const ok = extra => { dirty = true; send(res, 200, { ok: true, ...extra }); };

    for (const d of Object.values(devices)) {
      if (d.group && !portal.sites.some(x => x.name === d.group)) portal.sites.push({ name: d.group, createdAt: Date.now() });
    }
    if (req.method === "GET" && p === "/api/state") {
      return send(res, 200, {
        now: Date.now(), commands: COMMANDS,
        devices: Object.values(devices).map(({ history, token, queue: q, ...d }) => ({ ...d, type: typeOf(d), pending: q?.[0]?.type || null })),
        alerts: portal.alerts.filter(a => !a.resolvedAt || Date.now() - a.resolvedAt < 7 * 86400000),
        tasks: portal.tasks, tokens: portal.tokens, apps: portal.apps, files: portal.files, kioskApp: portal.kioskApp, sites: portal.sites,
        pending: portal.pending,
      });
    }
    if (req.method === "GET" && p === "/api/events") {
      const dev = url.searchParams.get("device");
      const list = dev ? portal.events.filter(e => e.deviceId === dev) : portal.events;
      return send(res, 200, { events: list.slice(-1000).reverse() });
    }
    m = /^\/api\/devices\/([\w-]+)\/screenshot\.jpg$/.exec(p);
    if (req.method === "GET" && m) {
      const file = path.join(LOGS_DIR, m[1] + ".jpg");
      if (!devices[m[1]] || !fs.existsSync(file)) return send(res, 404, { error: "no screenshot yet" });
      return send(res, 200, fs.readFileSync(file), "image/jpeg");
    }
    m = /^\/api\/devices\/([\w-]+)\/(history|logs)$/.exec(p);
    if (req.method === "GET" && m) {
      const d = devices[m[1]];
      if (!d) return send(res, 404, { error: "no such device" });
      if (m[2] === "history") return send(res, 200, { fields: HEALTH, history: d.history || [] });
      const file = path.join(LOGS_DIR, d.id + ".txt");
      return fs.existsSync(file) ? send(res, 200, fs.readFileSync(file), "text/plain; charset=utf-8") : send(res, 404, { error: "no logs yet" });
    }
    if (req.method === "POST" && p === "/api/devices/new") {
      const slot = { code: newCode(), name: String(body.name || "").slice(0, 80).trim(), group: String(body.group || "").slice(0, 60).trim(), createdAt: Date.now() };
      portal.pending.push(slot);
      log(null, "enroll", `Device ID ${slot.code} created${slot.name ? " for " + slot.name : ""}, waiting for the device to link`);
      return ok({ code: slot.code });
    }
    if (req.method === "POST" && p === "/api/devices/pending/delete") {
      portal.pending = portal.pending.filter(x => x.code !== body.code);
      return ok();
    }
    m = /^\/api\/devices\/([\w-]+)\/(command|name|group|delete)$/.exec(p);
    if (req.method === "POST" && m) {
      const d = devices[m[1]];
      if (!d) return send(res, 404, { error: "no such device" });
      if (m[2] === "command") {
        const cmd = commandFrom(body);
        if (!queue([d], cmd, "admin")) return send(res, 400, { error: `${typeOf(d)} devices can't do ${cmd.type}` });
      } else if (m[2] === "name") {
        d.name = String(body.name || "").slice(0, 80).trim();
      } else if (m[2] === "group") {
        d.group = String(body.group || "").slice(0, 60).trim();
      } else {
        delete devices[m[1]];
        log(m[1], "removed", `${label(d)} removed from the portal`);
      }
      return ok();
    }
    // Bulk: same action on many devices.
    if (req.method === "POST" && p === "/api/bulk") {
      const list = pick(body.target);
      if (!list.length) return send(res, 400, { error: "no devices selected" });
      if (body.action === "command") return ok({ count: queue(list, commandFrom(body), "admin (bulk)") });
      if (body.action === "group") {
        for (const d of list) d.group = String(body.group || "").slice(0, 60).trim();
        return ok({ count: list.length });
      }
      if (body.action === "delete") {
        for (const d of list) delete devices[d.id];
        log(null, "removed", `${list.length} devices removed from the portal`);
        return ok({ count: list.length });
      }
      return send(res, 400, { error: "unknown action" });
    }
    if (req.method === "POST" && p === "/api/sites") {
      const name = String(body.name || "").slice(0, 60).trim();
      if (!name) return send(res, 400, { error: "site name required" });
      const site = portal.sites.find(x => x.name.toLowerCase() === name.toLowerCase());
      const fields = { address: String(body.address || "").slice(0, 200).trim(), contact: String(body.contact || "").slice(0, 120).trim() };
      if (body.oldName) {
        // Rename / edit: move devices and tasks along with it.
        const old = portal.sites.find(x => x.name === body.oldName);
        if (!old) return send(res, 404, { error: "no such site" });
        if (site && site !== old) return send(res, 400, { error: "a site with that name already exists" });
        for (const d of Object.values(devices)) if (d.group === old.name) d.group = name;
        for (const t of portal.tasks) if (t.target?.group === old.name) t.target.group = name;
        for (const t of portal.tokens) if (t.group === old.name) t.group = name;
        Object.assign(old, { name }, fields);
        return ok();
      }
      if (site) return send(res, 400, { error: "a site with that name already exists" });
      portal.sites.push({ name, ...fields, createdAt: Date.now() });
      log(null, "site", `Site "${name}" added`);
      return ok();
    }
    if (req.method === "POST" && p === "/api/sites/delete") {
      portal.sites = portal.sites.filter(x => x.name !== body.name);
      for (const d of Object.values(devices)) if (d.group === body.name) d.group = "";
      log(null, "site", `Site "${body.name}" deleted`);
      return ok();
    }
    m = /^\/api\/alerts\/([a-f0-9]{16})\/ack$/.exec(p);
    if (req.method === "POST" && m) {
      const a = portal.alerts.find(x => x.id === m[1]);
      if (!a) return send(res, 404, { error: "no such alert" });
      a.ackAt = Date.now();
      if (a.kind === "crash") a.resolvedAt = a.ackAt;
      return ok();
    }
    if (req.method === "POST" && p === "/api/tasks") {
      const days = (Array.isArray(body.days) ? body.days : []).map(Number).filter(n => n >= 0 && n <= 6);
      if (!/^\d\d:\d\d$/.test(body.time || "") || !days.length) return send(res, 400, { error: "pick a time and at least one day" });
      const t = { id: /^[a-f0-9]{16}$/.test(body.id || "") ? body.id : rid(), name: String(body.name || body.type).slice(0, 80),
        type: body.type, appId: body.appId, fileId: body.fileId, target: body.target || { all: true }, time: body.time, days,
        enabled: body.enabled !== false };
      commandFrom(t); // validates the command
      const i = portal.tasks.findIndex(x => x.id === t.id);
      if (i >= 0) portal.tasks[i] = { ...portal.tasks[i], ...t }; else portal.tasks.push(t);
      return ok({ id: t.id });
    }
    m = /^\/api\/tasks\/([a-f0-9]{16})\/(run|delete)$/.exec(p);
    if (req.method === "POST" && m) {
      const t = portal.tasks.find(x => x.id === m[1]);
      if (!t) return send(res, 404, { error: "no such task" });
      if (m[2] === "run") return ok({ count: runTask(t, "admin (run now)") });
      portal.tasks = portal.tasks.filter(x => x !== t);
      return ok();
    }
    if (req.method === "POST" && p === "/api/tokens") {
      const t = { token: crypto.randomBytes(12).toString("base64url"), label: String(body.label || "Enrollment").slice(0, 60),
        group: String(body.group || "").slice(0, 60), mode: body.mode === "monitor" ? "monitor" : "kiosk", createdAt: Date.now(), uses: 0 };
      portal.tokens.push(t);
      return ok({ token: t.token });
    }
    if (req.method === "POST" && p === "/api/tokens/delete") {
      portal.tokens = portal.tokens.filter(t => t.token !== body.token);
      return ok();
    }
    // QR code contents for Android Enterprise provisioning (factory-reset device: tap the welcome screen 6 times, scan).
    if (req.method === "POST" && p === "/api/qr") {
      const t = portal.tokens.find(x => x.token === body.token);
      const apk = kioskApk();
      if (!t || !apk) return send(res, 400, { error: !apk ? "upload the kiosk app first (Apps page)" : "pick a token" });
      const server = String(body.server || "").replace(/\/$/, "");
      if (!/^https?:\/\//.test(server)) return send(res, 400, { error: "server address must start with http:// or https://" });
      const qr = {
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": "com.greenbotz.kiosk/.AdminReceiver",
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": server + apk.url,
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM": apk.checksum,
        "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": true,
        "android.app.extra.PROVISIONING_SKIP_ENCRYPTION": true,
        "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE": { server_url: server, mode: t.mode, token: t.token },
      };
      if (body.wifiSsid) {
        qr["android.app.extra.PROVISIONING_WIFI_SSID"] = String(body.wifiSsid);
        qr["android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE"] = body.wifiPassword ? "WPA" : "NONE";
        if (body.wifiPassword) qr["android.app.extra.PROVISIONING_WIFI_PASSWORD"] = String(body.wifiPassword);
      }
      return send(res, 200, { qr: JSON.stringify(qr) });
    }
    // Uploads: raw file body, name in the query string.
    if (req.method === "POST" && p === "/api/upload") {
      const kind = url.searchParams.get("kind"), name = String(url.searchParams.get("name") || "file").slice(0, 120);
      const id = rid();
      if (kind === "app") {
        const info = await receiveFile(req, path.join(UPLOADS, "apps", id + ".apk"));
        portal.apps.push({ id, label: String(url.searchParams.get("label") || name.replace(/\.apk$/i, "")).slice(0, 80), ...info, at: Date.now() });
        log(null, "upload", `App "${name}" uploaded`);
      } else if (kind === "file") {
        const info = await receiveFile(req, path.join(UPLOADS, "files", id));
        portal.files.push({ id, name, ...info, at: Date.now() });
        log(null, "upload", `File "${name}" uploaded`);
      } else return send(res, 400, { error: "kind must be app or file" });
      return ok({ id });
    }
    m = /^\/api\/(apps|files)\/([a-f0-9]{16})\/(delete|kiosk)$/.exec(p);
    if (req.method === "POST" && m) {
      if (m[3] === "kiosk" && m[1] === "apps") { portal.kioskApp = m[2]; return ok(); }
      portal[m[1]] = portal[m[1]].filter(x => x.id !== m[2]);
      fs.rm(path.join(UPLOADS, m[1], m[2] + (m[1] === "apps" ? ".apk" : "")), () => {});
      return ok();
    }
    send(res, 404, { error: "not found" });
  } catch (e) {
    if (!res.headersSent) send(res, 400, { error: String(e.message || e) });
  }
});

server.listen(PORT, () => {
  console.log(`Device portal running. Open http://localhost:${PORT}  (login: ${ADMIN_USER})`);
  if (!process.env.ADMIN_PASSWORD) console.log("WARNING: default admin password in use, set ADMIN_PASSWORD");
  if (!process.env.DEVICE_KEY) console.log("WARNING: default device key in use, set DEVICE_KEY (and server_key in the app)");
});
