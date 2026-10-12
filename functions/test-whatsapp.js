// Checks the WhatsApp offline alerts end to end against a fake CallMeBot: first alert, hourly reminder,
// retry after a failed send, back-online message and no reminders after that. Run: node test-whatsapp.js
// Times are shortened with env vars (1 "hour" = 8 s). Uses a temporary data folder; real data is not touched.
const http = require("node:http"), fs = require("node:fs"), os = require("node:os"), path = require("node:path"), assert = require("node:assert");
const { spawn } = require("node:child_process");

const sent = [];
let failNext = 1; // the first send fails, to check the retry
const fake = http.createServer((req, res) => {
  const q = new URL(req.url, "http://x").searchParams;
  if (failNext-- > 0) { res.writeHead(500); return res.end("busy"); }
  sent.push(q.get("text"));
  res.end("Message queued");
}).listen(0);

const PORT = 18099, KEY = "test-key", PW = "test-password-1";
const dir = fs.mkdtempSync(path.join(os.tmpdir(), "wa-test-"));
const server = spawn(process.execPath, [path.join(__dirname, "server.js")], {
  env: { ...process.env, PORT, DATA_DIR: dir, DEVICE_KEY: KEY, ADMIN_PASSWORD: PW, ONLINE_MS: "2000", OFFLINE_ALERT_MS: "3000",
    WA_REMIND_MS: "8000", WA_RETRY_MS: "2000", WA_GAP_MS: "0", CALLMEBOT_URL: `http://127.0.0.1:${fake.address().port}/` },
  stdio: "inherit",
});
const sleep = ms => new Promise(ok => setTimeout(ok, ms));
const call = (p, body, headers = {}) => fetch(`http://127.0.0.1:${PORT}${p}`, { method: "POST", body: JSON.stringify(body),
  headers: { "Content-Type": "application/json", ...headers } }).then(r => r.json());
const admin = { Authorization: "Basic " + Buffer.from("admin:" + PW).toString("base64") };
const report = () => call("/api/report", { id: "test-device-0001", deviceName: "Entrance Panel" }, { "X-Device-Key": KEY });
const ping = () => fetch(`http://127.0.0.1:${PORT}/api/state`, { headers: admin }); // the server's 5 s tick
const waitFor = async (what, ok, online = false, ms = 30000) => {
  for (const end = Date.now() + ms; Date.now() < end; await sleep(500)) { if (online) await report(); await ping(); if (ok()) return; }
  throw new Error("timed out waiting for " + what + "; sent so far:\n" + sent.join("\n---\n"));
};

(async () => {
  await sleep(1500);
  assert.deepEqual(await call("/api/whatsapp", { list: [{ name: "Ops", phone: "+6591234567", apikey: "abc" }] }, admin), { ok: true });
  await report();
  // goes offline: first alert, after one failed try that is retried
  await waitFor("offline alert", () => sent.some(t => t.startsWith("🚨 DEVICE OFFLINE ALERT")));
  const first = sent.find(t => t.startsWith("🚨 DEVICE OFFLINE ALERT"));
  assert.match(first, /Device Name: Entrance Panel/);
  assert.match(first, /Status: OFFLINE/);
  assert.match(first, /Last Device Online Time: \d+ \w+ \d{4}, \d+:\d\d [AP]M/);
  // still offline an "hour" later: one reminder
  await waitFor("reminder", () => sent.some(t => t.startsWith("🚨 DEVICE OFFLINE REMINDER")));
  // back online: recovery message, then no more reminders
  await waitFor("back online", () => sent.some(t => t.startsWith("✅ DEVICE BACK ONLINE")), true); // devices report every 10 s
  for (let i = 0; i < 6; i++) { await report(); await sleep(1000); } // stay online
  const n = sent.length;
  for (let i = 0; i < 10; i++) { await report(); await ping(); await sleep(1000); }
  assert.equal(sent.length, n, "nothing more is sent while the device is online");
  assert.equal(sent.filter(t => t.startsWith("🚨 DEVICE OFFLINE ALERT")).length, 1, "first alert sent once despite the retry");
  console.log(`PASS: ${sent.length} messages\n` + sent.join("\n---\n"));
})().catch(e => { console.error("FAIL:", e.message); process.exitCode = 1; })
  .finally(() => { server.kill(); fake.close(); fs.rmSync(dir, { recursive: true, force: true }); });
