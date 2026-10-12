// Device portal server: devices check in here, the web portal reads and sends commands.
// Office PC: no dependencies, run with: node server.js (data in ../server).
// Cloud: index.js runs it as a Firebase Cloud Function, data in the Firebase Storage bucket.
// ponytail: everything kept in memory and saved as two JSON files by one instance (maxInstances 1);
// move to Firestore past a few thousand devices or if more than one instance is ever needed.
const http = require("node:http");
const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");

const CLOUD = !!process.env.K_SERVICE; // set by Cloud Functions / Cloud Run
const PORT = Number(process.env.PORT || 8080);
const ADMIN_USER = process.env.ADMIN_USER || "admin";
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || "admin1234";
// Shared secret the devices send; must match server_key in the app's res/values/server.xml.
const DEVICE_KEY = process.env.DEVICE_KEY || "change-me-device-key";
const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, "..", "server");
const DASHBOARD_DIR = path.join(__dirname, "..", "dashboard");
const STATIC = {
  "/": ["index.html", "text/html; charset=utf-8"], "/login": ["login.html", "text/html; charset=utf-8"],
  "/logo.png": ["logo.png", "image/png"], "/manifest.json": ["manifest.json", "application/manifest+json"],
};
const PUBLIC_PAGES = ["/login", "/logo.png", "/manifest.json"];
const SESSION_MS = 12 * 60 * 60 * 1000;
const ONLINE_MS = Number(process.env.ONLINE_MS || 90 * 1000);
const OFFLINE_ALERT_MS = Number(process.env.OFFLINE_ALERT_MS || 2 * 60 * 1000); // a restart takes about a minute, so no alarm for that
const LOW_BATTERY = 20;
const HISTORY_EVERY_MS = 5 * 60 * 1000, HISTORY_KEEP = 288; // 24 h of 5-minute samples
// Devices take the server's time zone (fixes terminals shipped on UTC). Override with TIME_ZONE=Asia/Singapore etc.
const TIME_ZONE = process.env.TIME_ZONE || Intl.DateTimeFormat().resolvedOptions().timeZone;
// A Date whose local fields show the wall clock in TIME_ZONE (cloud servers run on UTC).
const zoned = () => new Date(new Date().toLocaleString("en-US", { timeZone: TIME_ZONE }));
const MAX_EVENTS = 5000, MAX_UPLOAD = 2048 * 1024 * 1024;

// Commands each device type understands. A device's type comes from its own check-in; old tablets send none.
const ANDROID = ["lockscreen", "reboot", "ring", "screenshot", "install", "file", "logs"];
const COMMANDS = {
  "android-kiosk": ["unlock", "lock", "singleapp", "apps", "relay", ...ANDROID],
  android: ANDROID,
  telpo: ANDROID,
  windows: ["reboot"],
  linux: ["reboot"],
  iot: ["reboot"],
};
const typeOf = d => (COMMANDS[d.type] ? d.type : "android-kiosk");
const label = d => d.name || d.deviceName || d.id.slice(0, 8);
const rid = () => crypto.randomBytes(8).toString("hex");

// ---- storage: the same keys in the local data folder and in the bucket ----
// devices.json, portal.json, uploads/apps/<id>.apk, uploads/files/<id>, logs/<deviceId>.txt|.jpg
const bucket = CLOUD ? require("firebase-admin/storage").getStorage().bucket() : null;
const local = key => path.join(DATA_DIR, key);
const store = {
  async read(key) {
    try { return CLOUD ? (await bucket.file(key).download())[0] : await fs.promises.readFile(local(key)); } catch { return null; }
  },
  async write(key, buf) {
    if (CLOUD) return bucket.file(key).save(buf, { resumable: false });
    await fs.promises.mkdir(path.dirname(local(key)), { recursive: true });
    await fs.promises.writeFile(local(key) + ".tmp", buf);
    await fs.promises.rename(local(key) + ".tmp", local(key));
  },
  remove: key => (CLOUD ? bucket.file(key).delete() : fs.promises.rm(local(key))).catch(() => {}),
  // Cloud: a Firebase Storage download link, so big APKs never pass through the function (32 MB limit).
  async downloadUrl(key) {
    const f = bucket.file(key), [meta] = await f.getMetadata();
    let token = meta.metadata?.firebaseStorageDownloadTokens?.split(",")[0];
    if (!token) await f.setMetadata({ metadata: { firebaseStorageDownloadTokens: token = crypto.randomUUID() } });
    return `https://firebasestorage.googleapis.com/v0/b/${bucket.name}/o/${encodeURIComponent(key)}?alt=media&token=${token}`;
  },
};
const appKey = id => `uploads/apps/${id}.apk`, fileKey = id => `uploads/files/${id}`;
if (!CLOUD) for (const dir of ["uploads/apps", "uploads/files", "logs"]) fs.mkdirSync(local(dir), { recursive: true });

let devices = {};
const portal = { events: [], alerts: [], tasks: [], tokens: [], apps: [], files: [], sites: [], pending: [], users: [], announcements: [], whatsapp: [], waOutbox: [], waHistory: [], kioskApp: null };
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
// A device's site is stored in d.group (the portal calls groups "sites"). Sites can also exist before any device is in them.

let dirty = false;
async function flush() {
  if (!dirty) return;
  dirty = false;
  try {
    await Promise.all([store.write("devices.json", JSON.stringify(devices)), store.write("portal.json", JSON.stringify(portal))]);
  } catch (e) {
    dirty = true;
    console.error("save failed:", e.message);
  }
}

function log(deviceId, kind, text) {
  portal.events.push({ at: Date.now(), deviceId, kind, text });
  if (portal.events.length > MAX_EVENTS) portal.events.splice(0, portal.events.length - MAX_EVENTS);
  dirty = true;
}

const same = (a, b) => {
  const x = Buffer.from(String(a)), y = Buffer.from(String(b));
  return x.length === y.length && crypto.timingSafeEqual(x, y);
};

// ---- portal accounts ----
// The ADMIN_USER / ADMIN_PASSWORD account is always a full admin (can't be locked out). More accounts live in portal.users:
// { username, name, hash, admin, sites: ["*"] | [site names], modules: [MODULES...], readOnly, disabled, createdAt }
const MODULES = ["overview", "devices", "sites", "alerts", "tasks", "apps", "enroll", "activity", "features"];
const ROOT = { username: ADMIN_USER, name: "Administrator", admin: true, root: true };
const hashPw = (pw, salt = crypto.randomBytes(16).toString("hex")) => salt + ":" + crypto.scryptSync(String(pw), salt, 32).toString("hex");
const checkPw = (pw, stored) => typeof stored === "string" && same(hashPw(pw, stored.split(":")[0]), stored);
const allSites = u => u.admin || (u.sites || []).includes("*");
const sees = (u, d) => !!d && (allSites(u) || (u.sites || []).includes(d.group || "")); // "No site" devices: all-sites accounts only
const siteOk = (u, g) => allSites(u) || (!!g && (u.sites || []).includes(g));
const can = (u, ...mods) => u.admin || mods.some(m => (u.modules || []).includes(m));
const publicUser = ({ hash, ...u }) => u;

// Login sessions: random token in an HttpOnly cookie -> { exp, user } (user null = the ADMIN_USER account). Lost on restart.
// The cookie must be called __session: Firebase Hosting drops every other cookie.
const sessions = new Map();
const sessionId = req => (/(?:^|;\s*)__session=([a-f0-9]{64})/.exec(req.headers.cookie || "") || [])[1];
const cookie = (value, maxAge) => `__session=${value}; HttpOnly; SameSite=Strict; Path=/; Max-Age=${maxAge}${CLOUD ? "; Secure" : ""}`;
// Behind Firebase Hosting every request comes from Google's proxy; the client address is in these headers.
// Behind the Cloudflare tunnel (start-server.bat) requests come from this PC itself, the client is in CF-Connecting-IP.
// Only trusted from loopback, so a LAN device can't fake it.
const ipOf = req => {
  if (CLOUD) return String(req.headers["fastly-client-ip"] || req.headers["x-forwarded-for"] || "").split(",")[0].trim();
  const ip = req.socket.remoteAddress;
  return /^(::1|127\.|::ffff:127\.)/.test(ip) && req.headers["cf-connecting-ip"] ? String(req.headers["cf-connecting-ip"]) : ip;
};

// The signed-in account, looked up on every request so edits / disabling / deleting apply at once. null = not signed in.
function whoIs(req) {
  const s = sessions.get(sessionId(req));
  if (s && s.exp > Date.now()) return s.user === null ? ROOT : portal.users.find(u => u.username === s.user && !u.disabled) || null;
  // Basic auth still accepted for scripts (curl -u admin:password), ADMIN_USER only.
  const m = /^Basic (.+)$/.exec(req.headers.authorization || "");
  if (!m) return null;
  const [user, ...rest] = Buffer.from(m[1], "base64").toString().split(":");
  return same(user, ADMIN_USER) && same(rest.join(":"), ADMIN_PASSWORD) ? ROOT : null;
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
  // Cloud Functions has already read the body into req.rawBody.
  if (req.rawBody) return req.rawBody.length > limit ? Promise.reject(new Error("too large")) : Promise.resolve(req.rawBody);
  if (CLOUD) return Promise.resolve(Buffer.alloc(0));
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

const digestInfo = (size, hash) => {
  const digest = hash.digest();
  return { size, sha256: digest.toString("hex"), checksum: digest.toString("base64url") };
};

// Office PC: streams an upload to disk, returning its size and sha256 (hex + base64url for Android provisioning).
// Any failure (too big, browser cancelled, disk error) deletes the partial file and rejects; it never throws.
function receiveFile(req, key) {
  const dest = local(key);
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
      fs.rename(dest + ".part", dest, err => (err ? fail(err) : resolve(digestInfo(size, hash))));
    });
    req.pipe(out);
  });
}

// Cloud: the browser sends the file straight to the bucket (see /api/upload/start), then the function hashes it there.
async function checkUploaded(key, name) {
  const f = bucket.file(key), [meta] = await f.getMetadata();
  if (Number(meta.size) > MAX_UPLOAD) { await store.remove(key); throw new Error(`file too large (max ${MAX_UPLOAD / 1024 / 1024} MB)`); }
  const hash = crypto.createHash("sha256");
  await new Promise((resolve, reject) => f.createReadStream().on("data", c => hash.update(c)).on("end", resolve).on("error", reject));
  await f.setMetadata({ contentType: "application/octet-stream", contentDisposition: disposition(name) });
  return digestInfo(Number(meta.size), hash);
}

const disposition = name => `attachment; filename="${String(name).replace(/[^\w.-]/g, "_")}"`;
async function sendFile(res, key, name) {
  if (CLOUD) {
    // ponytail: each download link stays valid until the file is deleted; fine while apps are public anyway.
    res.writeHead(302, { Location: await store.downloadUrl(key), "Cache-Control": "no-store" });
    return res.end();
  }
  fs.stat(local(key), (err, st) => {
    if (err) return send(res, 404, { error: "not found" });
    res.writeHead(200, { "Content-Type": "application/octet-stream", "Content-Length": st.size, "Content-Disposition": disposition(name) });
    fs.createReadStream(local(key)).pipe(res);
  });
}

// Fields a device may set about itself.
const REPORT_FIELDS = ["type", "deviceName", "model", "serial", "battery", "charging", "kioskLocked",
  "lat", "lng", "accuracy", "locationTime", "appVersion", "os", "uptime", "localIp", "info",
  "cpu", "ram", "disk", "temp", "signal", "mode", "singleApp", "launcherApps", "allowedApps", "relay"];
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
  if (type === "apps") {
    const packages = Array.isArray(body.packages) ? body.packages.map(String) : null;
    if (!packages || packages.length > 300 || packages.some(x => !/^[\w.]{3,200}$/.test(x))) throw new Error("bad app list");
    return { type, packages, name: `${packages.length} allowed app${packages.length === 1 ? "" : "s"}` };
  }
  if (type === "relay") {
    // on/off, or a pulse: on for `seconds`, then back off (door release)
    const on = !!body.on, seconds = Math.round(Number(body.seconds) || 0);
    if (seconds < 0 || seconds > 600) throw new Error("pulse must be 1-600 seconds");
    return { type, on, seconds, name: seconds ? `pulse ${seconds} s` : on ? "on" : "off" };
  }
  if (!Object.values(COMMANDS).flat().includes(type)) throw new Error("unknown command");
  return { type };
}

// ---- WhatsApp (CallMeBot, free): alerts go to each number in portal.whatsapp [{ name, phone, apikey }] ----
// Each person sends "I allow callmebot to send me messages" to +34 644 71 81 99 on WhatsApp and gets their API key.
// Messages wait in portal.waOutbox (saved with portal.json, so a restart keeps them) and go out one at a time.
// A failed send is retried every WA_RETRY_MS, up to WA_TRIES times. An item is only removed once its send works,
// so nothing is sent twice. Offline alerts repeat every WA_REMIND_MS until the device is back.
const WA_REMIND_MS = Number(process.env.WA_REMIND_MS || 60 * 60 * 1000), WA_RETRY_MS = Number(process.env.WA_RETRY_MS || 5 * 60 * 1000), WA_TRIES = 12;
const CALLMEBOT_URL = process.env.CALLMEBOT_URL || "https://api.callmebot.com/whatsapp.php";
const waTime = t => {
  const o = { timeZone: TIME_ZONE };
  return `${new Date(t).toLocaleDateString("en-GB", { ...o, day: "numeric", month: "long", year: "numeric" })}, ${
    new Date(t).toLocaleTimeString("en-US", { ...o, hour: "numeric", minute: "2-digit" })}`;
};
function waText(type, d, a) {
  const head = `Site: ${d.group || "No site"}\n\nDevice Name: ${label(d)}\n\nDevice ID: ${d.code || "-"}`;
  if (type === "online") return `✅ DEVICE BACK ONLINE\n\n${head}\n\nBack Online Time: ${waTime(a.resolvedAt)}\n\nStatus: ONLINE`;
  if (type === "offline" || type === "reminder") {
    const hours = Math.max(1, Math.round((Date.now() - d.lastSeen) / 3600000));
    return `🚨 DEVICE OFFLINE ${type === "reminder" ? `REMINDER (offline for ${hours} h)` : "ALERT"}\n\n${head}\n\nLast Device Online Time: ${waTime(d.lastSeen)}\n\nStatus: OFFLINE`;
  }
  return `🚨 ${a.text}\n\n${head}\n\nTime: ${waTime(a.at)}`; // battery, crash
}
// Queues one message per number. The key (alert + type + reminder number) is never queued or sent twice.
function waQueue(type, d, a, n = 0) {
  const key = `${a.id}:${type}:${n}`;
  if (portal.waHistory.some(h => h.key === key)) return;
  const text = waText(type, d, a);
  for (const r of portal.whatsapp) {
    if (portal.waOutbox.some(x => x.key === key && x.phone === r.phone)) continue;
    portal.waOutbox.push({ key, alertId: a.id, type, phone: r.phone, text, tries: 0, dueAt: Date.now() });
  }
  dirty = true;
}
async function callmebot(phone, apikey, text) {
  const res = await fetch(`${CALLMEBOT_URL}?phone=${encodeURIComponent(phone)}&text=${encodeURIComponent(text)}&apikey=${encodeURIComponent(apikey)}`,
    { signal: AbortSignal.timeout(20000) });
  const body = (await res.text()).replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim();
  // CallMeBot answers 200 with an error text for a wrong key or number
  if (!res.ok || /error|invalid|not (been )?(activated|registered)|wrong/i.test(body)) throw new Error(`HTTP ${res.status} ${body.slice(0, 150)}`);
}
let waBusy = false;
async function waSend() {
  if (waBusy || !portal.waOutbox.length) return;
  waBusy = true;
  try {
    for (const x of portal.waOutbox.filter(x => x.dueAt <= Date.now())) {
      const r = portal.whatsapp.find(r => r.phone === x.phone), name = r?.name || x.phone;
      try {
        if (!r) throw Object.assign(new Error("number removed"), { drop: true });
        await callmebot(r.phone, r.apikey, x.text);
        portal.waOutbox = portal.waOutbox.filter(y => y !== x);
        portal.waHistory.push({ key: x.key, type: x.type, phone: x.phone, at: Date.now() });
        if (portal.waHistory.length > 1000) portal.waHistory.splice(0, portal.waHistory.length - 1000);
        log(null, "whatsapp", `WhatsApp ${x.type} sent to ${name}`);
      } catch (e) {
        x.tries++;
        x.dueAt = Date.now() + WA_RETRY_MS;
        const giveUp = e.drop || x.tries >= WA_TRIES;
        if (giveUp) portal.waOutbox = portal.waOutbox.filter(y => y !== x);
        log(null, "whatsapp", `WhatsApp ${x.type} to ${name} failed (try ${x.tries}${giveUp ? ", gave up" : ", will retry"}): ${e.message}`);
      }
      dirty = true;
      await new Promise(ok => setTimeout(ok, Number(process.env.WA_GAP_MS ?? 3000))); // CallMeBot refuses messages sent too close together
    }
  } finally { waBusy = false; }
}
// Hourly reminders for offline devices. The schedule (a.waNextAt) is saved with the alert, so it survives a restart.
function waRemind() {
  if (!portal.whatsapp.length) return;
  for (const a of portal.alerts) {
    if (a.kind !== "offline" || a.resolvedAt || !a.waNextAt || Date.now() < a.waNextAt) continue;
    const d = devices[a.deviceId];
    if (!d) continue;
    a.waReminders = (a.waReminders || 0) + 1;
    a.waNextAt += WA_REMIND_MS * Math.max(1, Math.ceil((Date.now() - a.waNextAt) / WA_REMIND_MS)); // skip hours missed while the server was down
    waQueue("reminder", d, a, a.waReminders);
  }
}

// ---- alerts: opened when a condition starts, resolved when it ends (crashes stay until acknowledged) ----
function setAlert(d, kind, on, text) {
  const open = portal.alerts.find(a => a.deviceId === d.id && a.kind === kind && !a.resolvedAt);
  if (on && !open) {
    const a = { id: rid(), deviceId: d.id, kind, text, at: Date.now() };
    if (kind === "offline") a.waNextAt = a.at + WA_REMIND_MS;
    portal.alerts.push(a);
    log(d.id, "alert", text);
    waQueue(kind, d, a);
  } else if (!on && open) {
    open.resolvedAt = Date.now();
    log(d.id, "resolved", `${kind} alert cleared`);
    if (kind === "offline") {
      // back online: cancel whatever is still waiting for this alert, then send the recovery message
      portal.waOutbox = portal.waOutbox.filter(x => x.alertId !== open.id);
      waQueue("online", d, open);
    }
  }
}
function checkAlerts() {
  const now = Date.now();
  for (const d of Object.values(devices)) {
    const online = now - d.lastSeen < ONLINE_MS;
    if (d.wasOnline && !online) log(d.id, "offline", `${label(d)} went offline`);
    d.wasOnline = online;
    const offline = now - d.lastSeen > OFFLINE_ALERT_MS;
    setAlert(d, "offline", offline, `${label(d)} offline since ${new Date(d.lastSeen).toLocaleString("en-GB", { timeZone: TIME_ZONE })}`);
    setAlert(d, "battery", !offline && d.battery != null && d.battery < LOW_BATTERY && !d.charging, `${label(d)} battery low (${d.battery}%)`);
  }
  // Keep the last 1000 resolved alerts.
  const resolved = portal.alerts.filter(a => a.resolvedAt);
  if (resolved.length > 1000) {
    const drop = new Set(resolved.slice(0, resolved.length - 1000));
    portal.alerts = portal.alerts.filter(a => !drop.has(a));
  }
}

// ---- scheduled tasks: run at HH:MM (TIME_ZONE) on the chosen weekdays ----
function runTask(t, by) {
  const n = queue(pick(t.target), commandFrom(t), by);
  t.lastRun = Date.now();
  log(null, "task", `Task "${t.name}" sent ${t.type} to ${n} device${n === 1 ? "" : "s"}`);
  return n;
}
function runDueTasks() {
  const now = zoned(), hhmm = now.toTimeString().slice(0, 5), today = now.toDateString();
  for (const t of portal.tasks) {
    if (!t.enabled || t.time !== hhmm || !t.days.includes(now.getDay()) || t.lastRunDay === today) continue;
    t.lastRunDay = today;
    try { runTask(t, "schedule"); } catch (e) { log(null, "task", `Task "${t.name}" failed: ${e.message}`); }
  }
}

// ---- daily device logs: logs/<id>/<YYYY-MM-DD>.txt, filled from every log upload ----
// Tablets keep only their last 3000 log lines, so the portal asks every online Android device for logs at minute 55
// of every hour (23:55 is the last pull of the day). Each upload adds the lines newer than the last saved one.
// ponytail: lines that scroll out of the tablet's buffer between two pulls are lost; pull more often if that matters.
const LOG_DAYS = 30, LOG_PULL_MINUTE = Number(process.env.LOG_PULL_MINUTE ?? 55);
const dayKey = (id, day) => `logs/${id}/${day}.txt`;
async function saveDailyLogs(d, text) {
  const now = zoned(), year = now.getFullYear(), month = now.getMonth() + 1, byDay = new Map();
  let last = d.logLast || "";
  for (const line of text.split("\n")) {
    const m = /^(\d\d)-(\d\d) (\d\d:\d\d:\d\d\.\d{3})/.exec(line); // logcat -v time: "10-12 16:43:33.210 ..."
    if (!m) continue;
    const day = `${Number(m[1]) > month ? year - 1 : year}-${m[1]}-${m[2]}`, stamp = `${day} ${m[3]}`;
    if (stamp <= (d.logLast || "")) continue;
    if (stamp > last) last = stamp;
    if (!byDay.has(day)) byDay.set(day, []);
    byDay.get(day).push(line);
  }
  for (const [day, lines] of byDay) {
    const old = (await store.read(dayKey(d.id, day))) || "";
    await store.write(dayKey(d.id, day), Buffer.from(old + lines.join("\n") + "\n"));
    if (!(d.logDays ||= []).includes(day)) d.logDays.push(day);
  }
  d.logLast = last;
  d.logDays = (d.logDays || []).sort();
  while (d.logDays.length > LOG_DAYS) await store.remove(dayKey(d.id, d.logDays.shift()));
  dirty = true;
}
let lastLogPull = "";
function pullLogs() {
  const now = zoned(), hour = now.toDateString() + now.getHours();
  if (now.getMinutes() !== LOG_PULL_MINUTE || lastLogPull === hour) return;
  lastLogPull = hour;
  for (const d of Object.values(devices)) {
    if (Date.now() - d.lastSeen > ONLINE_MS || !COMMANDS[typeOf(d)].includes("logs") || d.queue?.some(c => c.type === "logs")) continue;
    (d.queue ||= []).push({ type: "logs", id: rid(), at: Date.now(), auto: true }); // not logged: would be 24 events a day per device
    dirty = true;
  }
}

// Alerts, scheduled tasks and saving, at most every 5 s. The office PC runs it on a timer. In the cloud the
// function only has CPU while handling a request, so it runs at the start of requests (devices check in every 10 s).
// ponytail: a task only fires if some request arrives during its minute; add a Cloud Scheduler ping if no device is online then.
let lastTick = 0;
async function tick() {
  if (Date.now() - lastTick < 5000) return;
  lastTick = Date.now();
  checkAlerts();
  runDueTasks();
  pullLogs();
  waRemind();
  waSend().catch(e => console.error("whatsapp:", e.message));
  await flush();
}

// The kiosk app used for QR enrollment: the one picked in the portal, else the first app named "kiosk".
function kioskApk() {
  const app = portal.apps.find(a => a.id === portal.kioskApp) || portal.apps.find(a => /kiosk/i.test(a.label));
  return app ? { url: `/download/app/${app.id}.apk`, checksum: app.checksum, label: app.label } : null;
}
// Loads the saved data. The office PC also adds the built kiosk APK on first run.
const ready = (async () => {
  devices = JSON.parse((await store.read("devices.json")) || "{}");
  Object.assign(portal, JSON.parse((await store.read("portal.json")) || "{}"));
  for (const d of Object.values(devices)) if (!d.code) { d.code = newCode(); dirty = true; }
  const built = path.join(__dirname, "..", "Kiosk app phase2.apk");
  if (CLOUD || portal.apps.length || !fs.existsSync(built)) return;
  const buf = fs.readFileSync(built), id = rid();
  await store.write(appKey(id), buf);
  portal.apps.push({ id, label: "Kiosk app", ...digestInfo(buf.length, crypto.createHash("sha256").update(buf)), at: Date.now() });
  portal.kioskApp = id;
  dirty = true;
})();

// Uploads started in the browser but not finished yet (cloud only): id -> "app" | "file".
const uploading = new Map();

async function handler(req, res) {
  const url = new URL(req.url, "http://x");
  const p = url.pathname;
  try {
    await ready;
    await tick();
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
      d.ip = ipOf(req);
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
      for (const id of Array.isArray(body.acks) ? body.acks.slice(0, 50) : []) {
        const a = portal.announcements.find(x => x.id === id);
        if (a && a.deviceIds.includes(d.id) && !a.acks[d.id]) { a.acks[d.id] = Date.now(); log(d.id, "announcement", `${label(d)} acknowledged "${a.text.slice(0, 60) || "image"}"`); }
      }
      if (typeof body.crash === "string" && body.crash) {
        portal.alerts.push({ id: rid(), deviceId: d.id, kind: "crash", text: `App crashed on ${label(d)}`, detail: body.crash.slice(0, 4000), at: Date.now() });
        waQueue("crash", d, portal.alerts[portal.alerts.length - 1]);
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
        if (command.auto) d.autoLogsAt = Date.now();
        else log(d.id, "delivered", `${command.type} delivered`);
      }
      // Oldest announcement this device hasn't acknowledged: sent on every check-in until it is.
      const an = portal.announcements.find(a => a.deviceIds.includes(d.id) && !a.acks[d.id]);
      const announcement = an ? { id: an.id, text: an.text, image: an.image ? `/download/file/${an.image}` : null } : null;
      dirty = true;
      return send(res, 200, { command, code: d.code, timeZone: TIME_ZONE, maintenance: !!d.maintenance, relayLink: d.relayLink !== false, announcement });
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
      const text = await readBody(req, 2 * 1024 * 1024);
      await store.write(`logs/${id}.txt`, text);
      devices[id].logsAt = Date.now();
      await saveDailyLogs(devices[id], text.toString());
      if (Date.now() - (devices[id].autoLogsAt || 0) > 120000) log(id, "logs", "Logs uploaded"); // hourly pulls stay quiet
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
        await store.write(`logs/${id}.jpg`, jpeg);
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
      return app ? sendFile(res, appKey(app.id), app.label + ".apk") : send(res, 404, { error: "not found" });
    }
    m = /^\/download\/file\/([a-f0-9]{16})$/.exec(p);
    if (req.method === "GET" && m) {
      if (!isDevice(req) && !whoIs(req)) return send(res, 401, { error: "login required" });
      const f = portal.files.find(x => x.id === m[1]);
      return f ? sendFile(res, fileKey(f.id), f.name) : send(res, 404, { error: "not found" });
    }

    // ---------------- login ----------------
    if (req.method === "POST" && p === "/api/login") {
      const ip = ipOf(req);
      if (loginBlocked(ip)) return send(res, 429, { error: "Too many attempts, wait a minute" });
      const body = await readJson(req);
      const name = String(body.user || ""), pw = String(body.password || "");
      const acct = portal.users.find(u => u.username.toLowerCase() === name.toLowerCase());
      const user = same(name, ADMIN_USER) && same(pw, ADMIN_PASSWORD) ? null
        : acct && !acct.disabled && checkPw(pw, acct.hash) ? acct.username : undefined;
      if (user === undefined) {
        loginFailed(ip);
        return send(res, 401, { error: acct?.disabled ? "This account is disabled" : "Wrong username or password" });
      }
      failures.delete(ip);
      const sid = crypto.randomBytes(32).toString("hex");
      sessions.set(sid, { exp: Date.now() + SESSION_MS, user });
      if (acct && user) { acct.lastLogin = Date.now(); dirty = true; }
      res.setHeader("Set-Cookie", cookie(sid, SESSION_MS / 1000));
      return send(res, 200, { ok: true });
    }
    if (req.method === "POST" && p === "/api/logout") {
      sessions.delete(sessionId(req));
      res.setHeader("Set-Cookie", cookie("", 0));
      return send(res, 200, { ok: true });
    }

    const page = req.method === "GET" && STATIC[p];
    if (page && (PUBLIC_PAGES.includes(p) || whoIs(req))) {
      return send(res, 200, fs.readFileSync(path.join(DASHBOARD_DIR, page[0])), page[1]);
    }
    if (page) {
      res.writeHead(302, { Location: "/login" });
      return res.end();
    }

    // ---------------- portal API: signed-in accounts, limited to their sites and modules ----------------
    const u = whoIs(req);
    if (!u) return send(res, 401, { error: "login required" });
    const deny = (error = "Your account doesn't have access to this") => send(res, 403, { error });
    if (req.method === "POST" && u.readOnly) return deny("This account is view-only");
    const body = req.method === "POST" && p !== "/api/upload" ? await readJson(req) : {};
    const ok = extra => { dirty = true; send(res, 200, { ok: true, ...extra }); };
    const mine = list => list.filter(d => sees(u, d));
    const by = u.username;

    for (const d of Object.values(devices)) {
      if (d.group && !portal.sites.some(x => x.name === d.group)) portal.sites.push({ name: d.group, createdAt: Date.now() });
    }
    if (req.method === "GET" && p === "/api/state") {
      const all = allSites(u);
      return send(res, 200, {
        now: Date.now(), commands: COMMANDS,
        me: { ...publicUser(u), allSites: all, modules: u.admin ? MODULES : u.modules || [] },
        devices: mine(Object.values(devices)).map(({ history, token, queue: q, ...d }) => ({ ...d, type: typeOf(d), pending: q?.[0]?.type || null })),
        alerts: portal.alerts.filter(a => (!a.resolvedAt || Date.now() - a.resolvedAt < 7 * 86400000) && sees(u, devices[a.deviceId])),
        tasks: can(u, "tasks") ? portal.tasks.filter(t => all || siteOk(u, t.target?.group)) : [],
        tokens: can(u, "enroll") ? portal.tokens.filter(t => siteOk(u, t.group)) : [],
        apps: can(u, "apps", "devices", "tasks") ? portal.apps : [], files: can(u, "apps", "devices", "tasks") ? portal.files : [],
        kioskApp: portal.kioskApp, sites: portal.sites.filter(x => siteOk(u, x.name)),
        pending: portal.pending.filter(x => siteOk(u, x.group)),
        users: u.admin ? portal.users.map(publicUser) : undefined, modules: MODULES,
        announcements: u.admin ? portal.announcements : [],
        whatsapp: u.admin ? portal.whatsapp : undefined,
        lan: CLOUD ? null : Object.values(require("os").networkInterfaces()).flat().find(x => x.family === "IPv4" && /^192\.168\./.test(x.address))?.address,
      });
    }
    if (req.method === "GET" && p === "/api/events") {
      const dev = url.searchParams.get("device");
      // events without a device (sites, uploads...) and those of removed devices: all-sites accounts only
      const list = portal.events.filter(e => (!dev || e.deviceId === dev) && (allSites(u) || sees(u, devices[e.deviceId])));
      return send(res, 200, { events: list.slice(-1000).reverse() });
    }
    m = /^\/api\/devices\/([\w-]+)\/screenshot\.jpg$/.exec(p);
    if (req.method === "GET" && m) {
      if (!can(u, "devices") || !sees(u, devices[m[1]])) return deny();
      const jpeg = devices[m[1]] && await store.read(`logs/${m[1]}.jpg`);
      return jpeg ? send(res, 200, jpeg, "image/jpeg") : send(res, 404, { error: "no screenshot yet" });
    }
    m = /^\/api\/devices\/([\w-]+)\/(history|logs)$/.exec(p);
    if (req.method === "GET" && m) {
      const d = devices[m[1]];
      if (!d) return send(res, 404, { error: "no such device" });
      if (!can(u, "devices") || !sees(u, d)) return deny();
      if (m[2] === "history") return send(res, 200, { fields: HEALTH, history: d.history || [] });
      const logs = await store.read(`logs/${d.id}.txt`);
      return logs ? send(res, 200, logs, "text/plain; charset=utf-8") : send(res, 404, { error: "no logs yet" });
    }
    // Saved daily logs between two times: ?from=2026-10-12T08:00&to=2026-10-12T17:30 (TIME_ZONE wall clock)
    m = /^\/api\/devices\/([\w-]+)\/logs\/download$/.exec(p);
    if (req.method === "GET" && m) {
      const d = devices[m[1]];
      if (!d) return send(res, 404, { error: "no such device" });
      if (!can(u, "devices") || !sees(u, d)) return deny();
      const from = String(url.searchParams.get("from") || "").replace("T", " "), to = String(url.searchParams.get("to") || "").replace("T", " ");
      if (!/^\d{4}-\d\d-\d\d \d\d:\d\d$/.test(from) || !/^\d{4}-\d\d-\d\d \d\d:\d\d$/.test(to) || from > to) return send(res, 400, { error: "pick a From and To time" });
      let out = `${label(d)} (Device ID ${d.code}) logs ${from} to ${to}\n\n`;
      for (const day of (d.logDays || []).filter(x => x >= from.slice(0, 10) && x <= to.slice(0, 10))) {
        const text = ((await store.read(dayKey(d.id, day))) || "").toString();
        for (const line of text.split("\n")) {
          const t = `${day} ${line.slice(6, 11)}`;
          if (line && t >= from && t <= to) out += line + "\n";
        }
      }
      res.writeHead(200, { "Content-Type": "text/plain; charset=utf-8", "Content-Disposition": disposition(`${label(d)}-logs-${from}-${to}.txt`) });
      return res.end(out);
    }
    if (req.method === "POST" && p === "/api/devices/new") {
      if (!can(u, "devices", "enroll") || !siteOk(u, String(body.group || "").trim())) return deny("Pick one of your sites");
      const slot = { code: newCode(), name: String(body.name || "").slice(0, 80).trim(), group: String(body.group || "").slice(0, 60).trim(), createdAt: Date.now() };
      portal.pending.push(slot);
      log(null, "enroll", `Device ID ${slot.code} created${slot.name ? " for " + slot.name : ""}, waiting for the device to link`);
      return ok({ code: slot.code });
    }
    if (req.method === "POST" && p === "/api/devices/pending/delete") {
      if (!can(u, "devices", "enroll")) return deny();
      portal.pending = portal.pending.filter(x => x.code !== body.code || !siteOk(u, x.group));
      return ok();
    }
    m = /^\/api\/devices\/([\w-]+)\/(command|name|group|relaylink|delete)$/.exec(p);
    if (req.method === "POST" && m) {
      const d = devices[m[1]];
      if (!d || !sees(u, d)) return send(res, 404, { error: "no such device" });
      if (!can(u, "devices")) return deny();
      if (m[2] === "command") {
        const cmd = commandFrom(body);
        if (!queue([d], cmd, by)) return send(res, 400, { error: `${typeOf(d)} devices can't do ${cmd.type}` });
      } else if (m[2] === "relaylink") {
        // Maintenance mode switches the relay too (on when maintenance starts, off when it ends). Default on.
        d.relayLink = !!body.on;
        log(d.id, "relay", `Relay follows maintenance mode: ${d.relayLink ? "yes" : "no"} (by ${by})`);
      } else if (m[2] === "name") {
        d.name = String(body.name || "").slice(0, 80).trim();
      } else if (m[2] === "group") {
        if (!siteOk(u, String(body.group || "").trim())) return deny("Pick one of your sites");
        d.group = String(body.group || "").slice(0, 60).trim();
      } else {
        delete devices[m[1]];
        log(m[1], "removed", `${label(d)} removed from the portal`);
      }
      return ok();
    }
    // Bulk: same action on many devices.
    if (req.method === "POST" && p === "/api/bulk") {
      if (!can(u, "devices", "sites")) return deny();
      const list = mine(pick(body.target));
      if (!list.length) return send(res, 400, { error: "no devices selected" });
      if (body.action === "command") return ok({ count: queue(list, commandFrom(body), by + " (bulk)") });
      if (body.action === "group") {
        if (!siteOk(u, String(body.group || "").trim())) return deny("Pick one of your sites");
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
    // Maintenance mode: show the full-screen maintenance image on the chosen devices until turned off.
    // Announcements: a message and/or image shown on the devices until someone taps Acknowledge. Admins only.
    if (req.method === "POST" && p === "/api/announcements") {
      if (!u.admin) return deny();
      const text = String(body.text || "").trim().slice(0, 1000), image = body.image ? String(body.image) : null;
      if (image && !portal.files.some(f => f.id === image)) return send(res, 400, { error: "image not found, upload it again" });
      if (!text && !image) return send(res, 400, { error: "write a message or pick an image" });
      const list = pick(body.target).filter(d => COMMANDS[typeOf(d)].includes("unlock"));
      if (!list.length) return send(res, 400, { error: "no kiosk devices selected" });
      portal.announcements.unshift({ id: rid(), text, image, at: Date.now(), by, deviceIds: list.map(d => d.id), acks: {} });
      portal.announcements = portal.announcements.slice(0, 200);
      log(null, "announcement", `Announcement sent to ${list.length} device${list.length === 1 ? "" : "s"} by ${by}`);
      return ok({ count: list.length });
    }
    if (req.method === "POST" && p === "/api/announcements/delete") {
      if (!u.admin) return deny();
      portal.announcements = portal.announcements.filter(a => a.id !== body.id);
      return ok();
    }
    // WhatsApp numbers for alerts. Admins only (the API keys are secrets).
    if (req.method === "POST" && p === "/api/whatsapp") {
      if (!u.admin) return deny();
      const list = (Array.isArray(body.list) ? body.list : []).slice(0, 20).map(r => ({
        name: String(r.name || "").trim().slice(0, 60), phone: String(r.phone || "").replace(/[^\d+]/g, ""), apikey: String(r.apikey || "").trim().slice(0, 40) }));
      const bad = list.find(r => !/^\+\d{8,15}$/.test(r.phone) || !r.apikey);
      if (bad) return send(res, 400, { error: `Check ${bad.name || bad.phone || "a row"}: phone needs + and country code (e.g. +6591234567), and an API key` });
      portal.whatsapp = list;
      if (body.test) {
        for (const r of list) {
          try { await callmebot(r.phone, r.apikey, `✅ Test from the device portal: WhatsApp alerts are on (sent by ${by}).`); }
          catch (e) { return send(res, 400, { error: `${r.name || r.phone}: ${e.message}` }); }
        }
      }
      log(null, "whatsapp", `WhatsApp alert numbers updated by ${by} (${list.length})`);
      return ok();
    }
    if (req.method === "POST" && p === "/api/maintenance") {
      if (!can(u, "devices", "sites", "overview")) return deny();
      const list = mine(pick(body.target));
      if (!list.length) return send(res, 400, { error: "no devices selected" });
      const on = !!body.on;
      for (const d of list) d.maintenance = on;
      log(null, "maintenance", `Maintenance mode ${on ? "ON" : "off"} for ${list.length} device${list.length === 1 ? "" : "s"} by ${by}`);
      return ok({ count: list.length });
    }
    if (req.method === "POST" && p === "/api/sites") {
      if (!can(u, "sites") || (body.oldName ? !u.admin : !allSites(u))) return deny(); // only admins edit sites
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
        for (const x of portal.users) x.sites = (x.sites || []).map(s => s === old.name ? name : s);
        Object.assign(old, { name }, fields);
        return ok();
      }
      if (site) return send(res, 400, { error: "a site with that name already exists" });
      portal.sites.push({ name, ...fields, createdAt: Date.now() });
      log(null, "site", `Site "${name}" added`);
      return ok();
    }
    if (req.method === "POST" && p === "/api/sites/delete") {
      if (!u.admin) return deny(); // only admins delete sites
      for (const x of portal.users) x.sites = (x.sites || []).filter(s => s !== body.name);
      portal.sites = portal.sites.filter(x => x.name !== body.name);
      for (const d of Object.values(devices)) if (d.group === body.name) d.group = "";
      log(null, "site", `Site "${body.name}" deleted`);
      return ok();
    }
    m = /^\/api\/alerts\/([a-f0-9]{16})\/ack$/.exec(p);
    if (req.method === "POST" && m) {
      const a = portal.alerts.find(x => x.id === m[1]);
      if (!can(u, "alerts", "overview")) return deny();
      if (!a || !sees(u, devices[a.deviceId])) return send(res, 404, { error: "no such alert" });
      a.ackAt = Date.now();
      if (a.kind === "crash") a.resolvedAt = a.ackAt;
      return ok();
    }
    if (req.method === "POST" && p === "/api/tasks") {
      if (!can(u, "tasks") || !(allSites(u) || siteOk(u, body.target?.group))) return deny("Pick one of your sites as the target");
      const old = portal.tasks.find(x => x.id === body.id);
      if (old && !(allSites(u) || siteOk(u, old.target?.group))) return deny();
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
      if (!t || !can(u, "tasks") || !(allSites(u) || siteOk(u, t.target?.group))) return send(res, 404, { error: "no such task" });
      if (m[2] === "run") return ok({ count: runTask(t, by + " (run now)") });
      portal.tasks = portal.tasks.filter(x => x !== t);
      return ok();
    }
    if (req.method === "POST" && p === "/api/tokens") {
      if (!can(u, "enroll") || !siteOk(u, String(body.group || ""))) return deny("Pick one of your sites");
      const t = { token: crypto.randomBytes(12).toString("base64url"), label: String(body.label || "Enrollment").slice(0, 60),
        group: String(body.group || "").slice(0, 60), mode: body.mode === "monitor" ? "monitor" : "kiosk", createdAt: Date.now(), uses: 0 };
      portal.tokens.push(t);
      return ok({ token: t.token });
    }
    if (req.method === "POST" && p === "/api/tokens/delete") {
      if (!can(u, "enroll")) return deny();
      portal.tokens = portal.tokens.filter(t => t.token !== body.token || !siteOk(u, t.group));
      return ok();
    }
    // QR code contents for Android Enterprise provisioning (factory-reset device: tap the welcome screen 6 times, scan).
    if (req.method === "POST" && p === "/api/qr") {
      const t = portal.tokens.find(x => x.token === body.token && siteOk(u, x.group));
      if (!can(u, "enroll")) return deny();
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
    // Uploads. Office PC: POST /api/upload with the raw file body, name in the query string.
    // Cloud (requests max 32 MB): POST /api/upload/start gives a bucket upload link, the browser PUTs the file there,
    // then POST /api/upload/finish {id} hashes it and adds it to the list.
    if (req.method === "POST" && /^\/api\/upload(\/start|\/finish)?$/.test(p)) {
      if (!can(u, "apps")) return deny();
      const kind = url.searchParams.get("kind"), name = String(url.searchParams.get("name") || "file").slice(0, 120);
      if (kind !== "app" && kind !== "file") return send(res, 400, { error: "kind must be app or file" });
      const keyOf = id => (kind === "app" ? appKey(id) : fileKey(id));
      let id = rid(), info;
      if (p === "/api/upload/start") {
        if (!CLOUD) return send(res, 200, {}); // the office PC takes the file directly
        const [uploadUrl] = await bucket.file(keyOf(id)).createResumableUpload({ origin: req.headers.origin });
        uploading.set(id, kind);
        return send(res, 200, { id, uploadUrl });
      }
      if (p === "/api/upload/finish") {
        if (uploading.get(body.id) !== kind) return send(res, 400, { error: "upload not found, try again" });
        uploading.delete(id = body.id);
        info = await checkUploaded(keyOf(id), kind === "app" ? name.replace(/\.apk$/i, "") + ".apk" : name);
      } else {
        if (CLOUD) return send(res, 400, { error: "use /api/upload/start" });
        info = await receiveFile(req, keyOf(id));
      }
      if (kind === "app") portal.apps.push({ id, label: String(url.searchParams.get("label") || name.replace(/\.apk$/i, "")).slice(0, 80), ...info, at: Date.now() });
      else portal.files.push({ id, name, ...info, at: Date.now() });
      log(null, "upload", `${kind === "app" ? "App" : "File"} "${name}" uploaded`);
      return ok({ id });
    }
    m = /^\/api\/(apps|files)\/([a-f0-9]{16})\/(delete|kiosk)$/.exec(p);
    if (req.method === "POST" && m) {
      if (!can(u, "apps")) return deny();
      if (m[3] === "kiosk" && m[1] === "apps") { portal.kioskApp = m[2]; return ok(); }
      portal[m[1]] = portal[m[1]].filter(x => x.id !== m[2]);
      store.remove(m[1] === "apps" ? appKey(m[2]) : fileKey(m[2]));
      return ok();
    }
    // ---------------- accounts (admins only) ----------------
    if (req.method === "POST" && p === "/api/users") {
      if (!u.admin) return deny();
      const username = String(body.username || "").trim();
      if (!/^[\w.@-]{2,40}$/.test(username)) return send(res, 400, { error: "Username: 2–40 letters, digits, . _ @ -" });
      if (username.toLowerCase() === ADMIN_USER.toLowerCase()) return send(res, 400, { error: "That username is reserved" });
      const old = portal.users.find(x => x.username.toLowerCase() === username.toLowerCase());
      if (body.isNew && old) return send(res, 400, { error: "That username is taken" });
      if (!body.isNew && !old) return send(res, 404, { error: "no such account" });
      const pw = String(body.password || "");
      if ((body.isNew || pw) && pw.length < 8) return send(res, 400, { error: "Password: at least 8 characters" });
      const sites = Array.isArray(body.sites) ? body.sites.map(String).filter(s => s === "*" || portal.sites.some(x => x.name === s)) : [];
      const acct = Object.assign(old || { username, createdAt: Date.now() }, {
        name: String(body.name || "").slice(0, 80).trim(), admin: !!body.admin, readOnly: !!body.readOnly && !body.admin, disabled: !!body.disabled,
        sites: sites.includes("*") ? ["*"] : sites,
        modules: (Array.isArray(body.modules) ? body.modules : []).filter(x => MODULES.includes(x)),
      });
      if (pw) {
        acct.hash = hashPw(pw);
        for (const [sid, s] of sessions) if (s.user === acct.username) sessions.delete(sid); // new password signs them out everywhere
      }
      if (!old) portal.users.push(acct);
      log(null, "user", `Account "${acct.username}" ${old ? "updated" : "created"} by ${by}`);
      return ok();
    }
    if (req.method === "POST" && p === "/api/users/delete") {
      if (!u.admin) return deny();
      if (body.username === u.username) return send(res, 400, { error: "You can't delete your own account" });
      const before = portal.users.length;
      portal.users = portal.users.filter(x => x.username !== body.username);
      if (portal.users.length === before) return send(res, 404, { error: "no such account" });
      log(null, "user", `Account "${body.username}" deleted by ${by}`);
      return ok();
    }
    send(res, 404, { error: "not found" });
  } catch (e) {
    if (!res.headersSent) send(res, 400, { error: String(e.message || e) });
  }
}

// Save before the process stops (Ctrl+C on the PC, Cloud Run shutting the instance down).
for (const sig of ["SIGINT", "SIGTERM"]) process.once(sig, () => flush().finally(() => process.exit(0)));

if (require.main === module) {
  ready.then(() => {
    setInterval(tick, 1000); // tick itself waits 5 s between runs
    http.createServer(handler).listen(PORT, () => {
      console.log(`Device portal running. Open http://localhost:${PORT}  (login: ${ADMIN_USER})`);
      if (!process.env.ADMIN_PASSWORD) console.log("WARNING: default admin password in use, set ADMIN_PASSWORD");
      if (!process.env.DEVICE_KEY) console.log("WARNING: default device key in use, set DEVICE_KEY (and server_key in the app)");
    });
  });
}
module.exports = { handler };
