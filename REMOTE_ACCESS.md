# Remote access plan

How the device portal and devices go from "office Wi-Fi only" to "reachable from anywhere over HTTPS".

## Today (stage 0)

- `server/server.js` runs on the office PC (`192.168.68.50:8080`), plain HTTP, started by `start-server.bat`.
- Devices reach it on the LAN: `server_url` in `app/src/main/res/values/server.xml` = `http://192.168.68.50:8080`.
- Admin login `admin / admin1234`, device key `change-me-device-key`: the defaults. **Fine on a closed LAN, not OK once anything is reachable from outside.**

## Stage 1: admin can open the portal from anywhere, devices stay local (now)

Goal: open the portal from phone/laptop outside the office. Devices keep talking to the server over the LAN, nothing on them changes.

Pick one:

| | Tailscale (recommended for now) | Cloudflare Tunnel |
|---|---|---|
| Who can reach it | Only your own logged-in devices (private network) | Anyone with the URL (public internet, login page protects it) |
| Setup | Install Tailscale on the office PC + your phone/laptop, same account | Install `cloudflared` on the office PC |
| URL | `http://<pc-name>:8080` or `http://100.x.y.z:8080` | `https://<random>.trycloudflare.com` (quick) or `https://portal.<yourdomain>` (with a domain) |
| HTTPS | Encrypted by Tailscale (WireGuard) | HTTPS by Cloudflare |
| Cost | Free (personal, up to 100 devices) | Free |

Steps (Tailscale):
1. `winget install Tailscale.Tailscale` on the office PC, sign in.
2. Install Tailscale on your phone/laptop, same account.
3. Open `http://<office-pc-tailscale-name>:8080` from anywhere.
4. In Tailscale admin: disable key expiry for the office PC so it stays reachable.

Steps (Cloudflare quick tunnel, no account):
1. `winget install Cloudflare.cloudflared`
2. Change the admin password first (below), restart the server.
3. `cloudflared tunnel --url http://localhost:8080` and use the printed `https://….trycloudflare.com` URL. The URL changes every time it restarts; use a named tunnel + own domain for a fixed one.

Either way, do this before going outside the LAN:
- Strong `ADMIN_PASSWORD` in `start-server.bat` (12+ chars), restart the server.
- Office PC must stay on, not sleep (Power settings: Sleep = Never).

## Stage 2: devices at other sites (future)

Goal: tablets at customer sites (other networks) report to one portal.

1. **Host the server on a small cloud VM** (DigitalOcean / Lightsail / Azure, ~$5/month, Ubuntu). `server.js` has no npm dependencies: copy `server/` + `dashboard/`, install Node 20+, run as a systemd service.
2. **HTTPS**: Caddy in front as reverse proxy (`portal.<yourdomain> { reverse_proxy localhost:8080 }`), automatic Let's Encrypt certificates. Only ports 80/443 open in the firewall; 8080 stays closed to the internet.
3. **Secrets**: new `ADMIN_PASSWORD` and new random `DEVICE_KEY` (`openssl rand -hex 24`), set as environment variables in the service file.
4. **Devices**: set `server_url = https://portal.<yourdomain>` and `server_key = <new DEVICE_KEY>` in `server.xml`, bump `versionCode`, build, push the update to all devices with **Install / update app** *while they still reach the old server*, then switch. Devices that can't update: re-link from the device admin menu.
5. **Move data**: copy the server's data files (devices, sites, history, uploads) from the office PC to the VM before switching.
6. **Backups**: daily copy of the data folder (cron + rclone or VM snapshots).

Server code changes needed for stage 2 (small):
- Session cookie: add `Secure` when served over HTTPS.
- Behind a proxy every request comes from `127.0.0.1`: read the client IP from `X-Forwarded-For` (only when the request comes from the local proxy) so login lockout and "Seen from IP" work per client.
- Optional: separate admin accounts instead of one shared login.

## Stage 3: live screen view / control (future)

The portal already does commands + screenshots. For live remote control:

- **RustDesk, self-hosted** (recommended): run `hbbs/hbbr` on the same cloud VM, install the RustDesk APK on devices via **Install / update app**, Windows PCs too. Free, your own relay.
- TeamViewer / AnyDesk: quickest, paid for business use.
- `scrcpy` over ADB (via Tailscale): for developers / troubleshooting only.
- Building it into the portal (screenshot stream + tap commands via the accessibility service) is possible, but large; only if RustDesk doesn't fit.

## Checklist

- [ ] Stage 1: Tailscale or tunnel on office PC, strong admin password, PC never sleeps
- [ ] Stage 2: cloud VM + Caddy HTTPS, new secrets, data copied, app updated with new `server_url`/`server_key`
- [ ] Stage 2: `Secure` cookie + `X-Forwarded-For` handling in `server.js`
- [ ] Stage 2: daily backups
- [ ] Stage 3: RustDesk server + app on devices
