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
- **Overview**: totals, devices per type and per site, open alerts, **🛠 Maintenance on all / off all**. **🗺 Map** (top bar) opens satellite view of all devices, with a **site** filter and a **device name / ID search**. A device without its own GPS shows at its site's coordinates (hollow marker); set them in Sites → Edit.
- **Devices**: search/filter, tick devices for bulk commands (including **🛠 Maintenance on / off**), click a row for its page.
- **Device page**: 24 h health chart, commands, activity, **Model…** (device model, onsite / technical team, technician), **Allowed apps** (the apps on the tablet's home screen), **Relay (door)** (see below), and **logs**: pulled from the tablet every hour, kept 30 days, download a From/To range.
- **Sites**: **+ Add site**, **Edit name / location / coordinates** on each site card (**Find address** searches OneMap), add devices to a site, send a command to a whole site. Only admins can edit or delete sites.
- **Alerts**: offline > 2 min, battery < 20 %, app crash (raised and cleared automatically). Open alerts sound a **battleship alarm** (`dashboard/alarm.wav`, mixkit) in the portal until someone clicks **Acknowledge** (the sound button mutes it; the browser only plays sound after the first click on the page).
  **Max announces** every new alert in the middle of the screen and reads it aloud (muted with the speaker button in his chat): which device, which site, and when the automatic ticket will be opened. When the automatic ticket is created he comes back: assigned to whom and what happens next, or "no technician is assigned yet, please check and assign" (click the message to open the ticket).
  **💬 WhatsApp alerts** (admin): each number gets a WhatsApp message (CallMeBot, free) for every new alert, a reminder every hour while a device stays offline, and a message when it is back online. The dialog shows how to add a number. Check: `node functions/test-whatsapp.js`.
- **Scheduled tasks**: a command at a set time on chosen days (e.g. restart a site every night).
- **Apps & files**: upload an .apk (up to 2 GB) and install it silently on devices (OTA); send files to Download/Kiosk.
- **Enroll devices**: one form (site, mode, Wi-Fi) makes a token and a QR code for factory-reset Android devices.
- **Announcements** (admin): a message (text, optional image) shown full screen on the tablets until someone taps **Acknowledge**.
- **Technical Team**: see [Technical team and tickets](#technical-team-and-tickets).
- **Activity log**, **Features** (status of every planned feature), **Users & access** (portal logins, **Can close tickets**, and the **Max** settings).
- **Max** (orange robot, bottom right): the AI assistant. **He speaks everything he shows** (answers, tips, pointing, alerts, tour; the speaker button in the chat mutes him). **"Show me around"** (chip, typed or spoken): he starts on Overview and opens every menu page one by one, then explains the top bar last (theme, speaker, Map, alerts, profile), moving on when he has finished speaking. Open another page meanwhile and he explains that one, then resumes the tour (click him to stop). **Click him to talk**: he says "Hi, I'm Max. How can I help you?" and listens; spoken questions get spoken answers and he keeps listening until you go quiet, type, or close the chat (Chrome / Edge, microphone allowed). See [Max](#max-the-ai-assistant).
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
   - Type the Device ID, check the **Device model** (the detected one is marked; it picks the door relay profile) and the **Usage** (Onsite device or Tech team), tap **Connect**, pick the allowed apps, tap **Save and finish**.
   - Nothing is sent to any server before **Connect**.
5. In the portal, rename the device and put it in its site.

Tablet admin menu: 6 taps top-right → password. **Server address** changes the server; **Theme** is Auto (follows the system), Light or Dark; **Device model & usage** changes the model and onsite / technical team.

## Door relay (Telpo F8, F10B, F10)
The Telpo F8, F10B and F10 intercoms have a relay for a door lock. On its device page, **Relay (door)**:
- **Relay ON** / **Relay OFF**, or **⚡ Pulse** N seconds (on, then off by itself: opens the door).
- **Maintenance mode switches the relay** (ticked by default): relay ON when maintenance starts, OFF when it ends.

Since app 10.14 the relay comes from the **device model**: one profile per model, in `RELAY_PROFILES` (`functions/server.js`) and `Relay.java` (app).

| Model | Relay | File | ON / OFF | Root | State |
|---|---|---|---|---|---|
| Telpo F8, Telpo F10B | yes (tested) | `/sys/class/gpio-ctrl/relay1/enable` | 0 / 1 | no | read back (confirmed) |
| Telpo F10 | yes (tested) | `/sys/class/telpoio/relay_en` | 0 / 1 | yes (`su`) | last value written (inferred) |
| Samsung Tablet | no | | | | relay commands refused |
| Telpo VCP, Akuvox, Others, or no model set | unknown | a known relay file if the tablet has one (as before 10.14) | 0 / 1 | | |

**Relay setup…** on the Relay card (admins) saves a tested mapping for one device. Only the two known relay files can be picked, and the app refuses any other path. The card shows:
- support, file, ON/OFF values and root
- whether the state is confirmed or inferred
- the last result, and who verified the mapping

ON / OFF / Pulse ask for confirmation, because they switch a real door. Detecting the model never writes to the relay.

The card shows the state the tablet reports. Needs app 10.10 or newer. The app writes to `/sys/class/gpio-ctrl/relay1/enable` (F8, F10B): `0` for on (door open) and `1` for off (locked), the same on every device since app 10.13.
The F10 (Android 9) has its relay at `/sys/class/telpoio/relay_en`, which only root can write and nobody can read: app 10.13 or newer writes it through `su` and reports the last value it wrote.

## Technical team and tickets
The portal menu **Technical Team** has these tabs: Dashboard, Team members, Team devices, Tickets, Assignment, Workload & availability, Ticket history, Settings.

- **Team members**: each member has:
  - name, region and skills (ticket categories; no skills = any job)
  - WhatsApp number and CallMeBot key (optional, for their ticket messages)
  - base location (**Find** searches OneMap)
  - active / inactive
  - **live location**, only if they agreed. Otherwise the tablet's location is not stored.
- **Team devices**: a tablet becomes a technical team device with **Usage: Tech team**. Set it on the setup screen, in the tablet's admin menu, or with Model… in the portal, then pick its technician here.
  - The tablet keeps its normal home screen and apps, with a **My tickets (n)** tile first (technician with a safety helmet, `tech_icon.xml`) that opens the technician's tickets (**← Home** or Back returns).
  - Simple screen (app 10.15) with the technician icon in the header and a glass look (`glass_hi.xml`, orange/blue glow `bg_tech.xml`): count tiles (Assigned, New / accepted, In progress, On hold, **Overall open tickets** = not resolved / closed / cancelled) and **My Tasks**. A ticket shows its next-step button right under it, then Details, **Add Work Summary & Updates** (work summary / progress, work completed, pending items, issue encountered, next steps, parts), Photos, History.
  - There is no login on the tablet: anyone holding it acts as that technician.
- **Automatic tickets**: an onsite device whose offline alert stays open past the set delay (default 10 min) gets one ticket.
  - It goes offline again while that ticket is open: a note on the same ticket, not a new ticket.
  - It comes back online: a note and a message to the technician. The ticket stays open until someone resolves and closes it.
- **Technician suggestions**: the ticket page lists technicians within the radius (default 10 km from the site's coordinates), with distances and a map.
  - Eligible: active technician with an **online** tablet (no Available / Busy / Off duty buttons since app 10.15), in range, and the right skill. Fewest active tickets first.
  - Nobody in range: a warning. Widen the radius or pick someone.
  - **Auto-assign** (Settings, off by default) only gives a ticket to an eligible technician.
- **Workflow**: New → Assigned → Accepted → In progress ↔ On hold → Resolved (awaiting closure) → Closed, or Cancelled.
  - On the tablet: accept, start, hold (reason required), add notes and photos, and submit as resolved (summary required, plus a photo for the categories under Settings → Evidence).
  - The portal ticket page shows the current status and its **remarks** on top (on hold reason, resolution, return reason).
  - **Only admins and accounts with "Can close tickets" can close a ticket.** The server refuses a close from a tablet.
  - Every change is in the ticket history.
- **Manual tickets**: **+ New ticket** takes:
  - title, category, priority, due date
  - site, device (or none) and technician
  - description, internal notes (never sent to the tablet) and photos / PDFs
- **Files**: JPEG, PNG, WebP or PDF, up to 10 MB each, checked by content. Stored under `uploads/tickets/`. Only signed-in portal users and the assigned tablet can open them.
- **WhatsApp**: the alert numbers hear about new and resolved tickets. The technician hears about assignments, returns, notes and the device coming back. It uses the same outbox and retries as the device alerts.
- **Offline tablet**: the last ticket list stays on screen. Updates made without network wait on the tablet and are sent once with the next check-in.
- **Search**: by ticket number, title, site, device or technician, with status, site, technician and date filters, 25 per page.
- **Check**: `node functions/test-tickets.js`. It covers the automatic ticket, no duplicates, suggestions, the tablet can't close, retries, file types, and the relay refused on a Samsung.

## Max, the AI assistant
Max is the 3D robot from `dashboard/max.glb` (`IPR_Mascot_v6`, 48 animations), drawn with three.js. He lives on the whole portal, not only in the chat:
- He docks bottom right, hovers and fidgets, and gets bored after a few quiet minutes.
- **Drag** him anywhere. **Click** him to open the chat. **Double-click** sends him home.
- **Chat**: he listens while you type or speak, thinks while the server works, and talks with gestures while answering.
- **Takes you to the answer**: every answer opens the matching page with its filters set, for example Devices with Offline, the Tickets tab with Awaiting closure, a device's or ticket's page, Sites, Alerts or Workload. He then flies to the exact card or list, outlines it, points at it and says the short answer there. The full answer stays in the chat. On a phone the chat closes so the page shows.
- **Actions**: when an action needs confirming, he opens that device or ticket and points at the **Confirm** button.
- **New alerts**: he flies to the red alarm bar, waves and says what happened.
- **Page tips**: on the first visit to a page in a session, he flies to the heading with a one-line tip.
- **Tour**: "Show me around" in the chat makes him fly down the menu, explaining each page.
- **Staying put**: ✈ in the chat header keeps him in his corner. He also stays still when the system asks for reduced motion.
- **Fallback**: without WebGL, or if the model can't load, the flat robot button is used.

Ask in words (or tap the microphone: the browser's speech recognition, Chrome / Edge / Safari) and Max answers from live portal data: offline devices, sites, alerts, tickets, team workload, activity. The speaker button reads answers aloud.
- It sees only what the signed-in account can see.
- It never changes anything itself. For a restart, maintenance, the door relay, or assigning or closing a ticket, it shows a **Confirm** button. That calls the normal portal action, with the normal permission checks.
- **Built-in (default, no key)**: Max runs on the portal server only and answers from our own data. No data leaves the server, and there's no cost. He recognises the usual questions: offline devices ("for more than 1 hour"), sites, open / overdue / unassigned / awaiting-closure tickets, a ticket number, a device ID or name, team availability and workload, alerts, today's work, recent activity, and actions such as "restart 5846" or "close T-00012". Anything else gets "I only answer questions about this portal's own data". The limit is 60 questions a minute per person.
- **Optional Claude API**: with `ANTHROPIC_API_KEY` in `functions/.env` (office PC) or `.env` (Docker), Max understands free-form questions. The questions and the data he reads are then sent to Anthropic, and he still refuses topics outside the portal.
  - The model is `ASSISTANT_MODEL` (default `claude-haiku-5-5`).
- **Admin settings**: Users & access → **Max** card: on/off, model, and questions per person per day. There is also a limit of 10 questions per minute per person.
- Accounts need the **Max** page ticked. Each question is written to the Activity log.

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
| `functions/test-tickets.js` | End-to-end check of device models, relay profiles and tickets: `node test-tickets.js`. |
| `functions/.env.example` | Copy to `functions/.env` (not in git): admin login, `DEVICE_KEY`, time zone. |
| `server/` | Office PC data (not in git): `devices.json`, `portal.json` (incl. WhatsApp numbers), `logs/`, `uploads/`. |
| `dashboard/` | Portal pages: `index.html` (portal), `login.html`, `logo.png`, `max.glb` (3D Max) and `max.png` (flat fallback), `manifest.json` (Add to Home screen). |
| `start-server.bat` / `start-server.sh` | Start the office PC server on port 8080. |
| `Dockerfile`, `docker-compose.yml`, `Caddyfile` | Cloud server: portal in Docker behind Caddy (https for mdm.gbotz.site). |
| `docker-compose.local.yml` | Same portal in Docker on the office PC, using `./server`. |
| `.env.compose.example` | Settings for `docker-compose.yml`. |
| `firebase.json`, `.firebaserc`, `storage.rules` | Firebase Hosting / Functions / Storage setup. |
| `.github/workflows/` | `docker-deploy.yml`: a push to `main` builds and deploys the cloud portal. `pr-check.yml`: checks PRs to `main`. |
| `REMOTE_ACCESS.md` | Plan for reaching the portal and devices from anywhere over https. |
| `agents/` | Monitoring agents: `agent.ps1` (Windows), `agent.sh` (Linux), `esp32.ino` (IoT). |

### Tablet app (`app/`, current version 10.15)
| Path | What |
|---|---|
| `app/build.gradle` | Version, and the device keys read from `local.properties` (`server.key`, `server.key.debug`, not in git). |
| `app/src/main/res/values/server.xml` | Built-in server address. Never commit a real key in it. |
| `KioskActivity.java` | Home screen: status pill (green online, yellow server offline, red offline / tap for Wi-Fi), clock, battery, Device ID, allowed apps, admin menu (6 taps top-right). |
| `SetupActivity.java` | First-run setup: Production/Debug server, Device ID, device model, usage, allowed apps. |
| `Relay.java` | Door relay per device model (file, ON/OFF values, root), or the portal's verified setup. |
| `TechHomeActivity.java` | Technical team tablets: the technician's tickets (My Tasks), work summary & updates, photos (camera / gallery), resolve. |
| `DeviceService.java` | Checks in with the portal every 10 s and runs its commands (apps, relay, lock screen, reboot, ring, screenshot, install, file, logs, unlock/lock, single app). |
| `AnnouncementActivity.java` | Full-screen announcements from the portal, with Acknowledge. |
| `MaintenanceActivity.java` | Maintenance-mode screen. |
| `ScreenshotService.java`, `ScreenshotActivity.java` | Portal screenshots: accessibility service, or screen capture on Android 9/10. |
| `PinOverlay.java` | App pinning override: invisible top-right square over the pinned app, 6 taps open the override PIN. |
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
