// Checks device model / relay profiles and the technical team tickets end to end. Run: node test-tickets.js
// - an offline onsite device gets one automatic ticket; offline again = same ticket; back online = a note, not closed
// - technicians within the radius, available and skilled are suggested first; far / unavailable ones are not eligible
// - the technician's tablet can accept, work and resolve but never close; only the portal closes
// - invalid moves, missing completion summary, bad file types, retried updates, relay on a model without one
// Uses a temporary data folder and a fake CallMeBot; real data is not touched.
const http = require("node:http"), fs = require("node:fs"), os = require("node:os"), path = require("node:path"), assert = require("node:assert");
const { spawn } = require("node:child_process");

const sent = [];
const fake = http.createServer((req, res) => { sent.push(new URL(req.url, "http://x").searchParams.get("text")); res.end("Message queued"); }).listen(0);
const PORT = 18098, KEY = "test-key", PW = "test-password-1";
const dir = fs.mkdtempSync(path.join(os.tmpdir(), "ticket-test-"));
const server = spawn(process.execPath, [path.join(__dirname, "server.js")], {
  env: { ...process.env, PORT, DATA_DIR: dir, DEVICE_KEY: KEY, ADMIN_PASSWORD: PW, ONLINE_MS: "2000", OFFLINE_ALERT_MS: "3000",
    WA_GAP_MS: "0", CALLMEBOT_URL: `http://127.0.0.1:${fake.address().port}/`, ANTHROPIC_API_KEY: "", ONEMAP: "off" },
  stdio: "inherit",
});
const sleep = ms => new Promise(ok => setTimeout(ok, ms));
const base = `http://127.0.0.1:${PORT}`;
const admin = { Authorization: "Basic " + Buffer.from("admin:" + PW).toString("base64") };
const req = async (p, body, headers = {}, raw) => {
  const r = await fetch(base + p, { method: body === undefined ? "GET" : "POST", body: raw || (body === undefined ? undefined : JSON.stringify(body)),
    headers: { "Content-Type": "application/json", ...headers } });
  return { status: r.status, json: await r.json().catch(() => null) };
};
const portal = (p, body, raw) => req(p, body, admin, raw);
const dev = (id, extra = {}) => req("/api/report", { id, deviceName: id, ...extra }, { "X-Device-Key": KEY });
const tech = (p, body, raw) => req(p, body, { "X-Device-Key": KEY, "X-Device-Id": "tech-tablet-0001" }, raw);
const tickets = async () => (await portal("/api/tickets?size=100")).json.items;
const waitFor = async (what, ok, keepOnline = [], ms = 30000) => {
  for (const end = Date.now() + ms; Date.now() < end; await sleep(500)) {
    for (const id of keepOnline) await dev(id);
    await portal("/api/state"); // drives the server's 5 s tick
    if (await ok()) return;
  }
  throw new Error("timed out waiting for " + what);
};

(async () => {
  await sleep(1500);
  // site with coordinates, three technicians: near + available, far, near but never said available
  assert.equal((await portal("/api/sites", { name: "Tower A", address: "1 Test Rd", lat: 1.3000, lng: 103.8000 })).status, 200);
  assert.equal((await portal("/api/sites", { name: "Bad", lat: 200, lng: 1 })).status, 400, "bad latitude refused");
  const near = (await portal("/api/technicians", { name: "Near Nadia", base: { lat: 1.3100, lng: 103.8100 }, skills: [] })).json.id; // ~1.6 km
  const far = (await portal("/api/technicians", { name: "Far Farid", base: { lat: 1.4500, lng: 104.0500 } })).json.id; // ~32 km
  await portal("/api/technicians", { name: "Idle Ivan", base: { lat: 1.3010, lng: 103.8010 } });
  await portal("/api/whatsapp", { list: [{ name: "Ops", phone: "+6591234567", apikey: "abc" }] });
  assert.equal((await portal("/api/team-settings", { ticketAfterMin: 0, radiusKm: 10, autoPriority: "urgent", evidence: ["Hardware replacement"] })).status, 200);

  // model + usage from the tablet; the report reply carries them back with the relay profile
  let r = await dev("onsite-panel-0001", { config: { model: "Telpo F8", usage: "onsite" } });
  assert.equal(r.json.config.model, "Telpo F8");
  assert.equal(r.json.relayCfg.iface, "/sys/class/gpio-ctrl/relay1/enable");
  assert.equal(r.json.relayCfg.on, "0");
  const state0 = (await portal("/api/state")).json, panel = state0.devices.find(d => d.id === "onsite-panel-0001");
  await portal(`/api/devices/${panel.id}/group`, { group: "Tower A" });
  r = await dev("tech-tablet-0001", { config: { model: "Samsung Tablet", usage: "tech" }, lat: 1.2, lng: 103.7 });
  assert.equal(r.json.relayCfg.none, true, "Samsung: no relay");
  assert.equal((await portal("/api/devices/tech-tablet-0001/command", { type: "relay", on: true })).status, 400, "relay refused on a model without one");
  assert.equal((await tech("/api/tech/home")).status, 403, "tablet not assigned yet");
  assert.equal((await portal("/api/devices/tech-tablet-0001/config", { techId: near })).status, 200);
  assert.equal((await tech("/api/tech/availability", { status: "available" })).status, 200);
  const st = (await portal("/api/state")).json;
  assert.equal(st.devices.find(d => d.id === "tech-tablet-0001").lat, undefined, "tech tablet location not kept without consent");
  assert.equal(st.technicians.find(t => t.id === near).availability, "available");

  // ticket system on the tablet: off for this technician hides My tickets (config.ticketsOff), on again brings it back
  assert.equal((await dev("tech-tablet-0001")).json.config.ticketsOff, false);
  const nadia = st.technicians.find(t => t.id === near);
  await portal("/api/technicians", { ...nadia, ticketsOff: true });
  assert.equal((await dev("tech-tablet-0001")).json.config.ticketsOff, true, "ticket system off reaches the tablet");
  await portal("/api/technicians", { ...nadia, ticketsOff: false });
  assert.equal((await dev("tech-tablet-0001")).json.config.ticketsOff, false, "and back on");
  assert.equal((await portal("/api/state")).json.technicians.find(t => t.id === near).base?.lat, 1.31, "other fields kept");

  // portal edit of the model wins over the tablet until the tablet changes it again
  await portal(`/api/devices/${panel.id}/config`, { model: "Telpo F10B" });
  r = await dev("onsite-panel-0001");
  assert.equal(r.json.config.model, "Telpo F10B");
  assert.equal((await portal(`/api/devices/${panel.id}/relaycfg`, { iface: "/etc/passwd", on: "0", off: "1" })).status, 400, "only known relay files");

  // panel goes offline (tablet keeps checking in): one automatic ticket
  await waitFor("automatic ticket", async () => (await tickets()).length === 1, ["tech-tablet-0001"]);
  let [t] = await tickets();
  assert.equal(t.source, "auto");
  assert.equal(t.priority, "urgent");
  assert.equal(t.site, "Tower A");
  assert.match(t.no, /^T-\d{5}$/);
  // back online: a note, the ticket stays open
  await dev("onsite-panel-0001");
  await waitFor("back-online note", async () => (await tickets())[0].timeline.some(e => e.kind === "online"), ["tech-tablet-0001", "onsite-panel-0001"]);
  assert.equal((await tickets())[0].status, "new", "not closed when the device comes back");
  // offline again: still one ticket, with an "offline again" note
  await waitFor("offline again", async () => (await tickets())[0].timeline.some(e => e.kind === "offline"), ["tech-tablet-0001"]);
  assert.equal((await tickets()).length, 1, "no duplicate ticket");

  // technician suggestions
  const c = (await portal(`/api/tickets/${t.id}/candidates`)).json.candidates;
  assert.equal(c[0].id, near);
  assert.ok(c[0].eligible && c[0].distanceKm > 1 && c[0].distanceKm < 2.5, `near is ~1.6 km: ${c[0].distanceKm}`);
  assert.ok(!c.find(x => x.id === far).eligible && c.find(x => x.id === far).distanceKm > 25, "far one is out of the radius");
  assert.ok(!c.find(x => x.name === "Idle Ivan").eligible, "unknown availability is not eligible");
  const wide = (await portal(`/api/tickets/${t.id}/candidates?radius=50`)).json.candidates;
  assert.ok(wide.find(x => x.id === far).why.every(w => !/km away/.test(w)), "a wider radius includes the far technician");

  // assign; the tablet works the ticket but can't close it
  assert.equal((await portal(`/api/tickets/${t.id}/assign`, { techId: near })).status, 200);
  let home = (await tech("/api/tech/home")).json;
  assert.equal(home.tickets.length, 1);
  assert.equal(home.tickets[0].status, "assigned");
  assert.equal((await tech(`/api/tech/tickets/${t.id}/status`, { to: "in_progress" })).status, 400, "must accept first");
  assert.equal((await tech(`/api/tech/tickets/${t.id}/status`, { to: "accepted", op: "op-1" })).status, 200);
  assert.equal((await tech(`/api/tech/tickets/${t.id}/status`, { to: "accepted", op: "op-1" })).json.repeat, true, "retried update not applied twice");
  await tech(`/api/tech/tickets/${t.id}/status`, { to: "in_progress" });
  await tech(`/api/tech/tickets/${t.id}/note`, { kind: "finding", text: "Power adapter loose", op: "op-2" });
  await tech(`/api/tech/tickets/${t.id}/note`, { kind: "finding", text: "Power adapter loose", op: "op-2" });
  assert.equal((await tickets())[0].timeline.filter(e => e.kind === "finding").length, 1, "note sent twice by a retry is kept once");
  assert.equal((await tech(`/api/tech/tickets/${t.id}/files?name=x.txt&op=f0`, null, "hello, not a photo plain text")).status, 400, "text file refused");
  const jpeg = Buffer.concat([Buffer.from([0xff, 0xd8, 0xff, 0xe0]), Buffer.alloc(64)]);
  assert.equal((await tech(`/api/tech/tickets/${t.id}/files?name=photo.jpg&op=f1`, null, jpeg)).status, 200);
  assert.equal((await tech(`/api/tech/tickets/${t.id}/status`, { to: "resolved", text: "done" })).status, 400, "summary required");
  assert.equal((await tech(`/api/tech/tickets/${t.id}/status`, { to: "resolved", text: "Reseated the power adapter, device back online." })).status, 200);
  assert.equal((await tech(`/api/tech/tickets/${t.id}/status`, { to: "closed" })).status, 403, "the tablet can't close");
  // a view-only portal account can't either; the admin can
  await portal("/api/users", { isNew: true, username: "viewer", password: "viewer-pass-1", readOnly: true, modules: ["team"], sites: ["*"] });
  const login = await fetch(base + "/api/login", { method: "POST", body: JSON.stringify({ user: "viewer", password: "viewer-pass-1" }) });
  const viewer = { Cookie: login.headers.get("set-cookie").split(";")[0] };
  assert.equal((await req(`/api/tickets/${t.id}/status`, { to: "closed" }, viewer)).status, 403, "view-only account can't close");
  await portal("/api/users", { isNew: true, username: "lead", password: "lead-pass-12", modules: ["team"], sites: ["*"] });
  const lead = { Cookie: (await fetch(base + "/api/login", { method: "POST", body: JSON.stringify({ user: "lead", password: "lead-pass-12" }) })).headers.get("set-cookie").split(";")[0] };
  assert.equal((await req(`/api/tickets/${t.id}/status`, { to: "closed" }, lead)).status, 403, "team account without close permission can't close");
  assert.equal((await portal(`/api/tickets/${t.id}/status`, { to: "new" })).status, 400, "invalid move");
  assert.equal((await portal(`/api/tickets/${t.id}/status`, { to: "closed", text: "Checked" })).status, 200);
  t = (await tickets())[0];
  assert.equal(t.status, "closed");
  assert.deepEqual(t.timeline.filter(e => e.kind === "status").map(e => e.to), ["accepted", "in_progress", "resolved", "closed"]);

  // manual ticket with no device, evidence rule
  r = await portal("/api/tickets", { title: "Replace gate reader", category: "Hardware replacement", priority: "normal", site: "Tower A", techId: near });
  assert.equal(r.status, 200);
  const id2 = r.json.id;
  for (const to of ["accepted", "in_progress"]) await tech(`/api/tech/tickets/${id2}/status`, { to });
  assert.equal((await tech(`/api/tech/tickets/${id2}/status`, { to: "resolved", text: "Replaced the reader with a new unit." })).status, 400, "evidence required");
  assert.equal((await tech(`/api/tech/tickets/${id2}/status`, { to: "on_hold" })).status, 400, "hold needs a reason");
  assert.equal((await portal("/api/tickets?q=gate")).json.total, 1, "search");
  assert.equal((await portal("/api/tickets?status=closed")).json.total, 1, "filter");

  // Max without a key: the built-in engine answers from our data, proposes actions, refuses other topics
  const ask = async q => (await portal("/api/assistant", { messages: [{ role: "user", text: q }] })).json;
  assert.match((await ask("Which devices are offline?")).text, /offline/);
  assert.match((await ask(`What's happening with ${t.no}?`)).text, /Closed/);
  const restart = await ask(`Restart device ${panel.code}`);
  assert.equal(restart.proposals.length, 1, "restart is only proposed");
  assert.match((await ask("What's the capital of France?")).text, /only answer questions about this portal/);
  await waitFor("ticket WhatsApp messages", () => sent.some(x => x.includes("NEW TICKET")) && sent.some(x => x.includes("TICKET RESOLVED")), [], 15000);
  console.log(`PASS (${sent.length} WhatsApp messages)`);
})().catch(e => { console.error("FAIL:", e.stack || e.message); process.exitCode = 1; })
  .finally(() => { server.kill(); fake.close(); fs.rmSync(dir, { recursive: true, force: true }); });
