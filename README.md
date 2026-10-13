# Interlock kiosk MDM (phase 2): tablet app, portal, server

- **Production portal**: https://mdm.gbotz.site (live). Tablets set up with **Production** talk to it.
- **Test portal**: the office PC, http://localhost:8080. Tablets set up with **Debug** talk to it.

Phase 1 (the finished kiosk app on its own) is kept unchanged in `F:\kisok`.

## Daily use (office PC test server)
1. Double-click **`start-server.bat`**. It starts the server and opens the dashboard.
   Keep the black window open; closing it stops the dashboard and tablet check-ins.
   It also opens a free Cloudflare tunnel window with a temporary `https://….trycloudflare.com` address for use outside the office.
2. Sign in at **http://localhost:8080** with the login in `functions/.env` (copy `functions/.env.example` first; default `admin` / `admin1234`).
   Other PCs on the same network: `http://192.168.68.50:8080`.

## Production and git
- Production runs in Docker behind Caddy (https) on the team's cloud VM.
- A push to **`main`** that changes `functions/server.js`, `dashboard/`, or the Docker/Caddy files is built and deployed automatically (GitHub Actions `docker-deploy.yml`; follow it in the repo's **Actions** tab). Other files (README, app) don't trigger a deploy.
- Work on **`dev`**, then merge `dev` into `main` to go live. `main` has merge commits that `dev` doesn't, so always merge (no rebase, no force-push).
- Commit only the files you changed (`git add <file>`, not `git add -A`). Keys never go to GitHub (see **Security**).

## Portal pages
- **Overview**: totals, devices per type and per site, open alerts, **🛠 Maintenance on all / off all**. **🗺 Map** (top bar) opens satellite view of all devices.
- **Devices**: search/filter, tick devices for bulk commands (including **🛠 Maintenance on / off**), click a row for its page.
- **Device page**: 24 h health chart, commands, activity, **Allowed apps** (the apps on the tablet's home screen), **Relay (door)** on the Telpo F8 (see below), and **logs**: pulled from the tablet every hour, kept 30 days, download a From/To range.
- **Sites**: **+ Add site**, **Edit name / location** on each site card, add devices to a site, send a command to a whole site. Only admins can edit or delete sites.
- **Alerts**: offline > 2 min, battery < 20 %, app crash (raised and cleared automatically). Open alerts sound a **fire-alarm siren** in the portal until someone clicks **Acknowledge** (the sound button mutes it; the browser only plays sound after the first click on the page).
  **💬 WhatsApp alerts** (admin): each number gets a WhatsApp message (CallMeBot, free) for every new alert, a reminder every hour while a device stays offline, and a message when it is back online. The dialog shows how to add a number. Check: `node functions/test-whatsapp.js`.
- **Scheduled tasks**: a command at a set time on chosen days (e.g. restart a site every night).
- **Apps & files**: upload an .apk (up to 2 GB) and install it silently on devices (OTA); send files to Download/Kiosk.
- **Enroll devices**: one form (site, mode, Wi-Fi) makes a token and a QR code for factory-reset Android devices.
- **Announcements** (admin): a message (text, optional image) shown full screen on the tablets until someone taps **Acknowledge**.
- **Activity log**, **Features** (status of every planned feature), **Users & access** (portal logins).
- **Maintenance mode** shows the System Maintenance screen on the tablets.
- Light and dark "Apple glass" look: theme button in the top bar. Device and site links can be bookmarked or shared.
- On a phone: open the portal and "Add to Home screen" to use it like an app.

Commands by device type: kiosk tablets (unlock, lock, single app, allowed apps, relay, lock screen, ring, restart, screenshot, install app, send file, get logs),
other Android / Telpo (lock screen, ring, restart, screenshot, install app, send file, get logs), Windows / Linux / IoT agents (restart).

## Adding a tablet
1. In the portal: **Devices → Add device** creates a 4-digit **Device ID**.
2. On the tablet: remove all accounts, turn on USB debugging, connect by USB, tap **Allow**.
3. Double-click **`setup-tablet.bat`**. It installs `final apk\mdmkiosk app.apk` and makes it device owner (`setup-tablet.bat monitor` for monitor-only devices).
4. The tablet shows **Set up this device**:
   - **Production** fills in https://mdm.gbotz.site. **Debug** asks for the office PC's IP (e.g. `192.168.68.50`) and uses `http://<IP>:8080`.
   - Type the Device ID, tap **Connect**, pick the allowed apps, tap **Save and finish**.
   - Nothing is sent to any server before **Connect**.
5. In the portal, rename the device and put it in its site.

Tablet admin menu: 6 taps top-right → password. **Server address** changes the server; **Theme** is Auto (follows the system), Light or Dark.

## Door relay (Telpo F8, F10B)
The Telpo F8 and F10B intercoms have a relay for a door lock. On its device page, **Relay (door)**:
- **Relay ON** / **Relay OFF**, or **⚡ Pulse** N seconds (on, then off by itself: opens the door).
- **Maintenance mode switches the relay** (ticked by default): relay ON when maintenance starts, OFF when it ends.

The card shows the state the tablet reports. Needs app 10.10 or newer. The app writes `1` / `0` to `/sys/class/gpio-ctrl/relay1/enable` (on / off). The F10B is wired the other way, so on it the app writes `0` for on (door open) and `1` for off.

## Our devices (13 Oct 2026)
| Device | Android | Connection | Production Device ID |
|---|---|---|---|
| Telpo F8 intercom (door relay) | 11 | ADB over Wi-Fi `192.168.68.51:5555` | 5846 |
| Samsung Galaxy Tab A11 | 16 | ADB over USB (`R8YL405XLNY`) | 8177 |
| F10B intercom (rk3568, door relay) | 11 | ADB over Wi-Fi `192.168.1.181:5555` | |

Both run app 10.12 on Production. Still to do in the production portal: delete the old offline devices 6201 (old F8) and 7041 (old Samsung), rename 5846 and 8177 and put them in their sites, and test the relay (Relay OFF, ON, Pulse, maintenance on/off).

## Troubleshooting
- **Tablet still uses the old server after reinstalling the app**: the saved server address survives a reinstall. Change it in the admin menu → **Server address**.
- **Portal says "screenshot service is off" (Telpo)**: Telpo's RemoteAid accessibility service replaced ours (`setup-tablet.bat` sets only ours). Keep both, separated by a colon:
  `adb shell settings put secure enabled_accessibility_services com.greenbotz.kiosk/.ScreenshotService:com.telpo.remote/net.christianbeier.droidvnc_ng.InputService`
- **Stopping the app**: it is device owner, so `am force-stop` doesn't stop it (and Android 16 refuses `am crash`). Use `adb shell pidof com.greenbotz.kiosk`, then `adb shell run-as com.greenbotz.kiosk kill -9 <pid>`.
- **Back to the setup screen, same Device ID**: wiping the app data gives the tablet a new ID (a second entry in the portal). Instead, read `adb shell run-as com.greenbotz.kiosk cat shared_prefs/kiosk.xml`, write a `kiosk.xml` with the same `device_id`:
  ```xml
  <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
  <map>
      <string name="mode">kiosk</string>
      <boolean name="setup_done" value="false" />
      <string name="device_id">SAME device_id AS BEFORE</string>
      <boolean name="wallpaper_set" value="true" />
  </map>
  ```
  `adb push` it to `/data/local/tmp/`, then in one `adb shell`: `run-as com.greenbotz.kiosk` copy it into `shared_prefs/`, kill the app, copy it again (the running app can write its old prefs back). In Git Bash, `export MSYS_NO_PATHCONV=1` first so `/data/...` paths aren't rewritten.
- **Linking without Devices → Add device**: a tablet gets its own 4-digit ID on its first check-in (the first **Connect**). Type that ID (portal device list, or `code` in `shared_prefs/kiosk.xml`) and tap **Connect** again.

## Files
### Server and portal
| Path | What |
|---|---|
| `functions/server.js` | Portal server, plain Node.js. Office PC: `node server.js`, data in `server/`. |
| `functions/index.js` | Runs `server.js` as a Firebase Cloud Function (see `firebase.json`). |
| `functions/test-whatsapp.js` | End-to-end check of the WhatsApp offline alerts: `node test-whatsapp.js`. |
| `functions/.env.example` | Copy to `functions/.env` (not in git): admin login, `DEVICE_KEY`, time zone. |
| `server/` | Office PC data (not in git): `devices.json`, `portal.json` (incl. WhatsApp numbers), `logs/`, `uploads/`. |
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
| `final apk/mdmkiosk app.apk` | Final app 10.12. One APK for both servers: pick Production or Debug in setup. `setup-tablet.bat` installs this one. |
| `Cloud APK/Kiosk app v10.12.apk` | Same build, 10.12. |
| `Kiosk app cloud.apk`, `Kiosk app local.apk`, `Kiosk app phase2.apk` | Older builds. |

## Security
- Device keys live only in `local.properties` (not in git): `server.key` (production) and `server.key.debug` (= `DEVICE_KEY` in `functions/.env`, the office PC server). Gradle builds them into the APK, so APK files stay out of git too.
- Also not in git: `functions/.env` (admin login, `DEVICE_KEY`) and `server/` (`portal.json` holds the WhatsApp numbers and CallMeBot keys).
- Change the default `ADMIN_PASSWORD` and `DEVICE_KEY` on any server reachable from outside. Plain http is only acceptable on the office network.
- The tablet APK is debuggable (`run-as` works, which the troubleshooting steps use), so anyone with USB debugging on a tablet can read the app's data. Make a signed release build before tablets go to customers.
- Next phase: remote screen control (VNC), screen recording, remote ADB and the secure tunnel.
