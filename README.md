# Kiosk phase 2 – local server + dashboard

Phase 1 (the finished kiosk app on its own) is kept unchanged in `F:\kisok`.

## Daily use
1. Double-click **`start-server.bat`**. It starts the server and opens the dashboard.
   Keep the black window open; closing it stops the dashboard and tablet check-ins.
2. Sign in at **http://localhost:8080** (default `admin` / `admin1234`, set in `start-server.bat`).
   Other PCs on the same network: `http://192.168.68.50:8080`.

## Dashboard
- Tiles at the top: total / online / offline / unlocked / low battery. Click one to filter the list.
- Per tablet: **Unlock** (2 minutes), **Lock**, and the **⋯** menu (show on map, rename, remove).
- Map shows each tablet's last GPS position (green = online, red = offline).
- Tablets check in every 10 seconds; "Offline" means no check-in for over a minute.

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
- Remote screen share is not built yet.
