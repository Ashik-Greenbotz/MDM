// Minimal MQTT 3.1.1 client (this server ships without npm packages): connect with auto-reconnect,
// publish QoS 0/1 (QoS 1 waits for the broker's PUBACK), subscribe, keepalive. mqtt:// or mqtts:// (TLS).
// ponytail: no QoS 2, no websockets, no offline queue; switch to the mqtt npm package if those are ever needed.
const net = require("net"), tls = require("tls"), { EventEmitter } = require("events");

const str = s => { const b = Buffer.from(String(s)), l = Buffer.alloc(2); l.writeUInt16BE(b.length); return Buffer.concat([l, b]); };
const u16 = n => { const b = Buffer.alloc(2); b.writeUInt16BE(n); return b; };
function packet(type, flags, body) {
  const len = [];
  let n = body.length;
  do { let d = n % 128; n = Math.floor(n / 128); if (n) d |= 128; len.push(d); } while (n);
  return Buffer.concat([Buffer.from([(type << 4) | flags, ...len]), body]);
}
const REFUSED = ["", "unsupported protocol", "client ID rejected", "broker unavailable", "bad username or password", "not authorised"];

class Mqtt extends EventEmitter {
  constructor(url, { username, password, clientId } = {}) {
    super();
    this.url = new URL(url);
    this.opts = { username, password, clientId: clientId || "portal-" + Math.random().toString(16).slice(2, 10) };
    this.connected = false; this.error = "connecting…"; this.pid = 0; this.acks = new Map(); this.subs = new Set();
    this.connect();
    setInterval(() => this.connected && this.sock.write(Buffer.from([0xc0, 0])), 30000).unref(); // PINGREQ
  }
  connect() {
    const u = this.url, secure = u.protocol === "mqtts:", port = Number(u.port) || (secure ? 8883 : 1883);
    const s = this.sock = secure ? tls.connect({ host: u.hostname, port, servername: u.hostname }) : net.connect({ host: u.hostname, port });
    s.setTimeout(90000); // no PINGRESP for 90 s: dead connection
    s.once(secure ? "secureConnect" : "connect", () => {
      const { username, password, clientId } = this.opts;
      const flags = 0x02 | (username ? 0x80 : 0) | (password ? 0x40 : 0); // clean session
      s.write(packet(1, 0, Buffer.concat([str("MQTT"), Buffer.from([4, flags]), u16(60), str(clientId),
        ...(username ? [str(username)] : []), ...(password ? [str(password)] : [])])));
    });
    let buf = Buffer.alloc(0);
    s.on("data", chunk => {
      buf = Buffer.concat([buf, chunk]);
      for (;;) {
        let len = 0, mul = 1, i = 1;
        for (; i < buf.length && i < 5; i++) { len += (buf[i] & 127) * mul; mul *= 128; if (!(buf[i] & 128)) break; }
        if (i >= buf.length || buf.length < i + 1 + len) return; // wait for the rest
        const type = buf[0] >> 4, flags = buf[0] & 15, body = buf.subarray(i + 1, i + 1 + len);
        buf = buf.subarray(i + 1 + len);
        this.onPacket(type, flags, body);
      }
    });
    s.on("timeout", () => s.destroy(new Error("broker stopped answering")));
    s.on("error", e => { this.error = e.message; });
    s.on("close", () => {
      if (this.connected || !this.error) this.error = this.error || "connection closed";
      this.connected = false;
      for (const [, a] of this.acks) { clearTimeout(a.timer); a.reject(new Error("MQTT connection lost")); }
      this.acks.clear();
      this.emit("down", this.error);
      setTimeout(() => this.connect(), 5000).unref();
    });
  }
  onPacket(type, flags, body) {
    if (type === 2) { // CONNACK
      if (body[1]) { this.error = "broker refused: " + (REFUSED[body[1]] || "code " + body[1]); return this.sock.destroy(); }
      this.connected = true; this.error = null;
      for (const t of this.subs) this.sendSub(t);
      this.emit("up");
    } else if (type === 3) { // PUBLISH from the broker
      const qos = (flags >> 1) & 3, tl = body.readUInt16BE(0), topic = body.subarray(2, 2 + tl).toString();
      let off = 2 + tl;
      if (qos) { this.sock.write(packet(4, 0, body.subarray(off, off + 2))); off += 2; } // PUBACK
      this.emit("message", topic, body.subarray(off).toString());
    } else if (type === 4) { // PUBACK for our QoS 1 publish
      const a = this.acks.get(body.readUInt16BE(0));
      if (a) { clearTimeout(a.timer); this.acks.delete(body.readUInt16BE(0)); a.resolve(); }
    }
  }
  nextId() { return (this.pid = (this.pid % 65535) + 1); }
  sendSub(topic) { this.sock.write(packet(8, 2, Buffer.concat([u16(this.nextId()), str(topic), Buffer.from([0])]))); }
  subscribe(topic) { if (!topic || this.subs.has(topic)) return; this.subs.add(topic); if (this.connected) this.sendSub(topic); }
  // Resolves when the broker has the message (QoS 1 PUBACK); rejects if not connected or no PUBACK in time.
  publish(topic, payload, { qos = 1, timeout = 5000 } = {}) {
    if (!this.connected) return Promise.reject(new Error("MQTT broker not connected" + (this.error ? ` (${this.error})` : "")));
    const id = qos ? this.nextId() : 0;
    this.sock.write(packet(3, qos << 1, Buffer.concat([str(topic), qos ? u16(id) : Buffer.alloc(0), Buffer.from(String(payload))])));
    if (!qos) return Promise.resolve();
    return new Promise((resolve, reject) => {
      this.acks.set(id, { resolve, reject, timer: setTimeout(() => { this.acks.delete(id); reject(new Error("broker did not acknowledge in time")); }, timeout) });
    });
  }
}
module.exports = Mqtt;
