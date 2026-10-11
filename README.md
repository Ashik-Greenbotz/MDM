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
| Path | What |
|---|---|
| `server/server.js` | Server, plain Node.js, no packages to install. Saves tablets to `server/devices.json`. |
| `dashboard/` | Dashboard and login pages. |
| `app/` | Tablet app source. `Kiosk app phase2.apk` is the built app. |

## Before moving to the own server / cloud
- Change `ADMIN_PASSWORD` and `DEVICE_KEY` (and `server_key` in `app/src/main/res/values/server.xml`, then rebuild).
- Put the server behind **https** (plain http is only acceptable on the local network).
- Remote screen control (VNC), screen recording, remote ADB and the secure tunnel are the next phase.
- Agents: `agents/agent.ps1` (Windows), `agents/agent.sh` (Linux), `agents/esp32.ino` (IoT).
