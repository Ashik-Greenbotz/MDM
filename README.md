# Kiosk phase 2 – local server + dashboard

Phase 1 (the finished kiosk app on its own) is kept unchanged in `F:\kisok`.

## Daily use
1. Double-click **`start-server.bat`**. It starts the server and opens the dashboard.
   Keep the black window open; closing it stops the dashboard and tablet check-ins.
2. Sign in at **http://localhost:8080** (default `admin` / `admin1234`, set in `start-server.bat`).
   Other PCs on the same network: `http://192.168.68.50:8080`.

## Portal pages
- **Overview**: totals, devices per type and per site, open alerts. **🗺 Map** (top bar) opens satellite view of all devices.
- **Devices**: search/filter, tick devices for bulk commands, click a row for details, 24 h health chart, activity and logs.
- **Sites**: **+ Add site**, **Edit name / location** on each site card, add devices to a site, send a command to a whole site.
- **Alerts**: offline > 2 min (with alarm sound), battery < 20 %, app crash (raised and cleared automatically).
- **Scheduled tasks**: a command at a set time on chosen days (e.g. restart a site every night).
- **Apps & files**: upload an .apk (up to 2 GB) and install it silently on devices (OTA); send files to Download/Kiosk.
- **Enroll devices**: tokens (put new devices in a site + mode) and QR codes for factory-reset Android devices.
- **Activity log**, **Features** (status of every planned feature).
- On a phone: open the portal and "Add to Home screen" to use it like an app.

Commands by device type: kiosk tablets (unlock, lock, lock screen, ring, restart, install app, send file, get logs),
other Android / Telpo (same minus unlock/lock), Windows / Linux / IoT agents (restart).

## Adding a tablet
1. On the tablet: remove all accounts, turn on USB debugging, connect by USB, tap **Allow**.
2. Double-click **`setup-tablet.bat`**.
3. On the dashboard, use **⋯ → Rename** to give it its site name.

The tablet must reach this PC over the network (same Wi-Fi/LAN).
If the server moves, change it per tablet: 6 taps top-right → password → **Server address**.

## Files
### Server and portal
| Path | What |
|---|---|
| `functions/server.js` | Portal server, plain Node.js. Office PC: `node server.js`, data in `server/`. |
| `functions/index.js` | Runs `server.js` as a Firebase Cloud Function (see `firebase.json`). |
| `functions/test-whatsapp.js` | End-to-end check of the WhatsApp offline alerts: `node test-whatsapp.js`. |
| `functions/.env.example` | Copy to `functions/.env` (not in git): admin password, `DEVICE_KEY`, WhatsApp settings. |
| `server/` | Office PC data (not in git): `devices.json`, `portal.json`, `logs/`, `uploads/`. |
| `dashboard/` | Portal pages: `index.html` (portal), `login.html`, `logo.png`, `manifest.json` (Add to Home screen). |
| `start-server.bat` / `start-server.sh` | Start the office PC server on port 8080. |
| `Dockerfile`, `docker-compose.yml`, `Caddyfile` | Cloud server: portal in Docker behind Caddy (https for mdm.gbotz.site). |
| `docker-compose.local.yml` | Same portal in Docker on the office PC, using `./server`. |
| `.env.compose.example` | Settings for `docker-compose.yml`. |
| `firebase.json`, `.firebaserc`, `storage.rules` | Firebase Hosting / Functions / Storage setup. |
| `.github/workflows/` | `docker-deploy.yml`: a push to `main` builds and deploys the cloud portal. `pr-check.yml`: checks PRs to `main`. |
| `REMOTE_ACCESS.md` | Plan for reaching the portal and devices from anywhere over https. |
| `agents/` | Monitoring agents: `agent.ps1` (Windows), `agent.sh` (Linux), `esp32.ino` (IoT). |

### Tablet app (`app/`, current version 10.12)
| Path | What |
|---|---|
| `app/build.gradle` | Version, and the device keys read from `local.properties` (`server.key`, `server.key.debug`, not in git). |
| `app/src/main/res/values/server.xml` | Built-in server address. Never commit a real key in it. |
| `KioskActivity.java` | Home screen: status pill (green online, yellow server offline, red offline / tap for Wi-Fi), clock, battery, Device ID, allowed apps, admin menu (6 taps top-right). |
| `SetupActivity.java` | First-run setup: Production/Debug server, Device ID, allowed apps. |
| `DeviceService.java` | Checks in with the portal every 10 s and runs its commands (apps, relay, lock screen, reboot, ring, screenshot, install, file, logs, unlock/lock, single app). |
| `AnnouncementActivity.java` | Full-screen announcements from the portal, with Acknowledge. |
| `MaintenanceActivity.java` | Maintenance-mode screen. |
| `ScreenshotService.java`, `ScreenshotActivity.java` | Portal screenshots: accessibility service, or screen capture on Android 9/10. |
| `PinOverlay.java` | App pinning override: invisible bottom-left square over the pinned app, 6 taps open the override PIN. |
| `ProvisioningActivity.java`, `AdminReceiver.java`, `BootReceiver.java` | Device-owner setup by QR, device admin, start on boot. |
| `res/layout/main.xml` | Home screen layout. |
| `res/values(-night)/` | Light/dark colours and themes (glass look). |
| `res/drawable(-night)/` | Logo (light/dark), glass panels, gradient, Wi-Fi / Ethernet / battery icons, maintenance image. |
| `setup-tablet.bat` | One-time USB setup of a new tablet (`setup-tablet.bat monitor` for monitor-only devices). |

### Built apps (not in git, they contain device keys)
| Path | What |
|---|---|
| `final apk/mdmkiosk app.apk` | Final app 10.12. One APK for both servers: pick Production or Debug in setup. |
| `Cloud APK/Kiosk app v10.12.apk` | Same build, 10.12. |
| `Kiosk app cloud.apk`, `Kiosk app local.apk`, `Kiosk app phase2.apk` | Older builds. |

## Before moving to the own server / cloud
- Change `ADMIN_PASSWORD` and `DEVICE_KEY` (and `server_key` in `app/src/main/res/values/server.xml`, then rebuild).
- Put the server behind **https** (plain http is only acceptable on the local network).
- Remote screen control (VNC), screen recording, remote ADB and the secure tunnel are the next phase.
- Agents: `agents/agent.ps1` (Windows), `agents/agent.sh` (Linux), `agents/esp32.ino` (IoT).
