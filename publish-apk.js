// Uploads a kiosk APK to a portal's Apps & files and makes it the latest kiosk app (QR enrollment + device "Update" button).
// Usage: node publish-apk.js <file.apk> <version, e.g. 10.20> ["release notes"]
// Reads PROD_URL (default https://mdm.gbotz.site), PROD_USER, PROD_PASSWORD from functions/.env.
const fs = require("fs"), path = require("path");
const env = Object.fromEntries(fs.readFileSync(path.join(__dirname, "functions/.env"), "utf8")
  .split(/\r?\n/).map(l => /^([A-Z_]+)=(.*)$/.exec(l)).filter(Boolean).map(m => [m[1], m[2].trim()]));
const [file, ver, notes = ""] = process.argv.slice(2);
if (!file || !ver) { console.error("usage: node publish-apk.js <file.apk> <version> [notes]"); process.exit(1); }
const base = process.env.PROD_URL || env.PROD_URL || "https://mdm.gbotz.site";
if (!(process.env.PROD_USER || env.PROD_USER) || !(process.env.PROD_PASSWORD || env.PROD_PASSWORD)) { console.error("add PROD_USER and PROD_PASSWORD to functions/.env"); process.exit(1); }
const auth = "Basic " + Buffer.from((process.env.PROD_USER || env.PROD_USER) + ":" + (process.env.PROD_PASSWORD || env.PROD_PASSWORD)).toString("base64");

async function api(p, body) {
  const r = await fetch(base + p, { method: "POST", headers: { authorization: auth, "content-type": "application/json" }, body: JSON.stringify(body) });
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(`${p}: HTTP ${r.status} ${j.error || ""}`);
  return j;
}

(async () => {
  const label = "Kiosk app v" + ver, data = fs.readFileSync(file);
  const q = new URLSearchParams({ kind: "app", name: label + ".apk", label, ...(notes ? { notes } : {}) });
  const s = await api("/api/upload/start?" + q, {});
  let id;
  if (s.uploadUrl) { // cloud: file goes to the bucket, then the server checks it
    const put = await fetch(s.uploadUrl, { method: "PUT", body: data });
    if (!put.ok) throw new Error("bucket upload HTTP " + put.status);
    id = (await api("/api/upload/finish?" + q, { id: s.id })).id;
  } else { // office PC takes the body directly
    const r = await fetch(base + "/api/upload?" + q, { method: "POST", headers: { authorization: auth }, body: data });
    if (!r.ok) throw new Error("upload HTTP " + r.status);
    id = (await r.json()).id;
  }
  await api(`/api/apps/${id}/kiosk`, {});
  console.log(`${label} uploaded to ${base} (${(data.length / 1048576).toFixed(1)} MB) and set as the latest kiosk app`);
})().catch(e => { console.error(e.message); process.exit(1); });
