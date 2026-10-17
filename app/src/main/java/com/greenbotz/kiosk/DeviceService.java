package com.greenbotz.kiosk;

import android.Manifest;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.location.Location;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.DropBoxManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.StatFs;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Checks in with the portal server every few seconds: sends status, health + location, runs the commands it gets back.
public class DeviceService extends Service {
    private static final String TAG = "DeviceService";
    // ponytail: polling; 10s keeps commands snappy for a few hundred tablets, use a push channel beyond that
    private static final long REPORT_MS = 10 * 1000;
    static final String PREF_SERVER = "server_url";
    private static final String PREF_DEVICE_ID = "device_id";
    static final String PREF_TOKEN = "token";
    static final String PREF_CODE = "code";
    static final String PREF_MAINTENANCE = "maintenance";
    static final String PREF_LOST = "lost"; // JSON {message, phone}; empty = not lost
    static final String PREF_SIGNAGE = "signage"; // JSON {urls, seconds, idleMin, enabled}; empty = off
    static final String PREF_MAINT_IMG = "maint_img"; // version (portal time) of files/maintenance.jpg; 0 = built-in image
    static final String PREF_SERVER_OK = "server_ok", PREF_ACKS = "acks";
    // Set by MaintenanceActivity so we only relaunch it when it is not already on screen.
    static volatile boolean maintenanceShowing;
    private static final String PREF_EVENTS = "events", PREF_CRASH = "crash", PREF_BUGS_SINCE = "bugs_since";
    private static boolean triedLogGrant;
    private static boolean crashHandlerSet;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private Location lastLocation;
    // Not a lambda: before Android 11 the other three methods have no default and calling them crashes.
    private final LocationListener locationListener = new LocationListener() {
        @Override public void onLocationChanged(Location loc) { lastLocation = loc; }
        @Override public void onProviderEnabled(String provider) { }
        @Override public void onProviderDisabled(String provider) { }
        @Override public void onStatusChanged(String provider, int status, android.os.Bundle extras) { }
    };

    static void start(Context ctx) {
        try {
            ctx.startForegroundService(new Intent(ctx, DeviceService.class));
        } catch (RuntimeException e) {
            Log.w(TAG, "could not start", e);
        }
    }

    // Messages for the portal's event log (install results etc.), sent with the next check-in.
    static synchronized void event(Context ctx, String text) {
        SharedPreferences p = ctx.getSharedPreferences("kiosk", MODE_PRIVATE);
        try {
            JSONArray a = new JSONArray(p.getString(PREF_EVENTS, "[]"));
            if (a.length() < 20) a.put(text);
            p.edit().putString(PREF_EVENTS, a.toString()).commit();
        } catch (Exception ignored) {
        }
    }

    // Production = the built-in server (cloud key); any other server is a local/debug one (local key).
    static boolean production(Context ctx) {
        String prod = Uri.parse(ctx.getString(R.string.server_url)).getHost();
        return prod != null && prod.equals(Uri.parse(serverUrl(ctx)).getHost());
    }

    // Device IP: the IPv4 address of the network the tablet actually uses (Wi-Fi or Ethernet). WifiManager's
    // getIpAddress() says 0.0.0.0 on some devices, and "first interface" picked tunnels / mobile data before.
    private String localIp() {
        try {
            android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
            android.net.LinkProperties lp = cm == null ? null : cm.getLinkProperties(cm.getActiveNetwork());
            if (lp != null) for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                java.net.InetAddress a = la.getAddress();
                if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) return a.getHostAddress();
            }
            // No active network reported: a real LAN interface (Wi-Fi / Ethernet), never a tunnel or mobile data.
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || !ni.getName().matches("(wlan|eth|en).*")) continue;
                for (java.net.InetAddress a : java.util.Collections.list(ni.getInetAddresses()))
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) return a.getHostAddress();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    static String key(Context ctx) {
        return ctx.getString(production(ctx) ? R.string.server_key : R.string.server_key_debug);
    }

    static String serverUrl(Context ctx) {
        return ctx.getSharedPreferences("kiosk", MODE_PRIVATE)
                .getString(PREF_SERVER, ctx.getString(R.string.server_url));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("device", "Device monitoring", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "device")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Device monitoring active")
                .build();
        appPrefs = getSharedPreferences("kiosk", MODE_PRIVATE);
        self = this;
        catchCrashes();
        grantLocation();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        else startForeground(1, n);
        startLocation();
        handler.post(new Runnable() {
            @Override
            public void run() {
                net.execute(DeviceService.this::report);
                PinOverlay.sync(DeviceService.this);
                handler.postDelayed(this, REPORT_MS);
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        net.shutdownNow();
        getSystemService(LocationManager.class).removeUpdates(locationListener);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // Saves the stack trace of any crash in this app; the next check-in reports it as a crash alert.
    private void catchCrashes() {
        if (crashHandlerSet) return;
        crashHandlerSet = true;
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            getSharedPreferences("kiosk", MODE_PRIVATE).edit().putString(PREF_CRASH, sw.toString()).commit();
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    // Bug Finder: crashes and freezes of every app, from Android's DropBox. Needs READ_LOGS and the usage-stats app-op
    // (adb once, see setup-tablet.bat; rooted terminals grant them through su). Returns the per-tag "read up to" times,
    // saved only after the server has the report.
    private static final String[][] BUG_TAGS = {{"data_app_crash", "crash"}, {"system_app_crash", "crash"}, {"data_app_anr", "anr"},
            {"system_app_anr", "anr"}, {"data_app_native_crash", "native"}, {"system_server_crash", "system"}, {"system_server_anr", "system"}};
    private static final java.util.regex.Pattern BUG_APP = java.util.regex.Pattern.compile("^(?:Process|Package): *(\\S+)", java.util.regex.Pattern.MULTILINE);
    private JSONObject putBugs(JSONObject d, SharedPreferences prefs) throws Exception {
        JSONObject since = new JSONObject(prefs.getString(PREF_BUGS_SINCE, "{}"));
        if (!triedLogGrant && checkSelfPermission("android.permission.READ_LOGS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            triedLogGrant = true;
            try {
                Process p = new ProcessBuilder("su", "0", "sh", "-c", "pm grant " + getPackageName() + " android.permission.READ_LOGS; pm grant " + getPackageName()
                        + " android.permission.READ_DROPBOX_DATA; appops set "
                        + getPackageName() + " GET_USAGE_STATS allow").redirectErrorStream(true).start();
                if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) p.destroy();
            } catch (Exception ignored) { // no root: needs the adb grant
            }
        }
        JSONArray out = new JSONArray();
        try {
            DropBoxManager db = getSystemService(DropBoxManager.class);
            long dayAgo = System.currentTimeMillis() - 86400000L; // first run: the last day
            for (String[] t : BUG_TAGS) {
                long at = since.optLong(t[0], dayAgo);
                // ponytail: 5 per tag per check-in, the rest follow on the next ones (every 10 s)
                for (int n = 0; n < 5; n++) {
                    DropBoxManager.Entry e = db.getNextEntry(t[0], at);
                    if (e == null) break;
                    at = e.getTimeMillis();
                    String text = e.getText(8000);
                    e.close();
                    if (text == null) continue;
                    java.util.regex.Matcher m = BUG_APP.matcher(text);
                    String app = m.find() ? m.group(1) : t[0].startsWith("system_server") ? "android (system)" : "unknown";
                    if (app.equals(getPackageName())) continue; // our own crashes come from catchCrashes()
                    out.put(new JSONObject().put("kind", t[1]).put("app", app).put("at", at).put("text", text));
                }
                since.put(t[0], at);
            }
            d.put("bugAccess", true);
        } catch (SecurityException e) {
            Log.w(TAG, "no crash log access: " + e.getMessage());
            d.put("bugAccess", false);
        }
        if (out.length() > 0) d.put("bugs", out);
        return since;
    }

    // Device owner can grant its own permissions, so no prompt appears on the tablet.
    private void grantLocation() {
        DevicePolicyManager dpm = getSystemService(DevicePolicyManager.class);
        if (!dpm.isDeviceOwnerApp(getPackageName())) return;
        ComponentName admin = new ComponentName(this, AdminReceiver.class);
        for (String p : new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                Manifest.permission.READ_PHONE_STATE /* Build.getSerial() */,
                Manifest.permission.WRITE_EXTERNAL_STORAGE /* "Send file" on Android 9 */}) {
            dpm.setPermissionGrantState(admin, getPackageName(), p, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
        }
        enableLocation(dpm, admin);
    }

    // setLocationEnabled() is Android 11+; older versions take the secure setting (allowed for device owners until 11).
    @SuppressWarnings("deprecation")
    static void enableLocation(DevicePolicyManager dpm, ComponentName admin) {
        if (Build.VERSION.SDK_INT >= 30) dpm.setLocationEnabled(admin, true);
        else dpm.setSecureSetting(admin, Settings.Secure.LOCATION_MODE, String.valueOf(Settings.Secure.LOCATION_MODE_HIGH_ACCURACY));
    }

    private void startLocation() {
        LocationManager lm = getSystemService(LocationManager.class);
        try {
            for (String p : new String[]{LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                if (!lm.getAllProviders().contains(p)) continue; // hasProvider() is Android 12+
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (lastLocation == null || l.getTime() > lastLocation.getTime())) lastLocation = l;
                lm.requestLocationUpdates(p, 60 * 1000, 0, locationListener, Looper.getMainLooper());
            }
        } catch (SecurityException e) {
            Log.w(TAG, "no location permission (not device owner?)", e);
        }
    }

    // Some Telpo models report the chip maker (e.g. QUALCOMM) as manufacturer; Telpo's own service is on all of them.
    private boolean isTelpo() {
        if (Build.MANUFACTURER.toLowerCase().contains("telpo")) return true;
        try {
            getPackageManager().getPackageInfo("com.telpo.omcservice", 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String deviceId() {
        SharedPreferences p = getSharedPreferences("kiosk", MODE_PRIVATE);
        String id = p.getString(PREF_DEVICE_ID, null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            p.edit().putString(PREF_DEVICE_ID, id).apply();
        }
        return id;
    }

    // Runs on the `net` thread.
    private void report() {
        // Fresh install: no check-ins until setup picks a server (or QR provisioning set one), else every
        // Debug install would first appear on production as a junk device. Commands only arrive via report().
        SharedPreferences sp = getSharedPreferences("kiosk", MODE_PRIVATE);
        if (!sp.getBoolean(SetupActivity.PREF_SETUP_DONE, true) && !sp.contains(PREF_SERVER)) return;
        try {
            BatteryManager bm = getSystemService(BatteryManager.class);
            SharedPreferences prefs = getSharedPreferences("kiosk", MODE_PRIVATE);
            long unlockUntil = prefs.getLong(KioskActivity.PREF_UNLOCK_UNTIL, 0);
            JSONObject d = new JSONObject();
            d.put("id", deviceId());
            d.put("token", prefs.getString(PREF_TOKEN, ""));
            String events = prefs.getString(PREF_EVENTS, "[]"), crash = prefs.getString(PREF_CRASH, "");
            d.put("events", new JSONArray(events));
            d.put("crash", crash);
            putHealth(d);
            JSONObject bugsSince = putBugs(d, prefs);
            boolean monitor = KioskActivity.monitorOnly(this);
            d.put("type", !monitor ? "android-kiosk" : isTelpo() ? "telpo" : "android");
            d.put("mode", monitor ? "monitor" : "kiosk");
            d.put("singleApp", prefs.getString(KioskActivity.PREF_SINGLE_APP, ""));
            java.util.Set<String> acks = new java.util.HashSet<>(prefs.getStringSet(PREF_ACKS, java.util.Collections.emptySet()));
            d.put("acks", new JSONArray(acks));
            // Allowed apps for the portal; sent when changed (sentAllowed starts null, so always on the first report).
            String allowed = new JSONArray(new java.util.TreeSet<>(prefs.getStringSet(KioskActivity.PREF_APPS, java.util.Collections.emptySet()))).toString();
            if (!allowed.equals(sentAllowed)) d.put("allowedApps", new JSONArray(allowed));
            // Installed apps, so the portal can offer them for app pinning. Sent when the list changes.
            JSONArray apps = launcherApps();
            if (!apps.toString().equals(sentApps)) d.put("launcherApps", apps);
            d.put("os", "Android " + Build.VERSION.RELEASE);
            d.put("patch", Build.VERSION.SECURITY_PATCH);
            d.put("deviceName", Settings.Global.getString(getContentResolver(), Settings.Global.DEVICE_NAME));
            d.put("model", Build.MODEL);
            try {
                d.put("serial", Build.getSerial());
            } catch (SecurityException ignored) {
            }
            int battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            if (battery >= 0) d.put("battery", battery); // mains-powered terminals report Integer.MIN_VALUE
            d.put("charging", bm.isCharging());
            Relay rl = Relay.current(this);
            Boolean relay = rl == null ? null : rl.state(this);
            if (relay != null) d.put("relay", relay);
            if (rl != null) d.put("relayInfo", rl.info(this));
            // Model / usage picked on this tablet: sent until the server has it, so a portal edit isn't overwritten later.
            boolean configDirty = prefs.getBoolean(PREF_CONFIG_DIRTY, false);
            String sentModel = prefs.getString(Relay.PREF_MODEL, ""), sentUsage = prefs.getString(Relay.PREF_USAGE, "onsite");
            if (configDirty) d.put("config", new JSONObject().put("model", sentModel).put("usage", sentUsage));
            d.put("kioskLocked", !monitor && System.currentTimeMillis() >= unlockUntil);
            d.put("uptime", android.os.SystemClock.elapsedRealtime() / 1000);
            d.put("appVersion", getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
            putUsage(d);
            d.put("lost", LostActivity.on(this));
            Location loc = lastLocation;
            if (loc != null) {
                d.put("lat", loc.getLatitude());
                d.put("lng", loc.getLongitude());
                d.put("accuracy", loc.getAccuracy());
                d.put("locationTime", loc.getTime());
            }
            JSONObject reply = post(serverUrl(this) + "/api/report", d);
            clearSent(events, crash);
            prefs.edit().putString(PREF_BUGS_SINCE, bugsSince.toString()).apply();
            clearAcks(acks);
            setServerOk(prefs, true);
            if (d.has("launcherApps")) sentApps = apps.toString();
            if (d.has("allowedApps")) sentAllowed = allowed;
            String code = reply.optString("code");
            if (!code.isEmpty() && !code.equals(prefs.getString(PREF_CODE, ""))) prefs.edit().putString(PREF_CODE, code).apply();
            applyConfig(prefs, reply, configDirty, sentModel, sentUsage);
            if (reply.has("settings")) applySettings(reply.optJSONObject("settings"));
            if (reply.has("signage")) getSharedPreferences("kiosk", MODE_PRIVATE).edit()
                    .putString(KioskActivity.PREF_SIGNAGE_KEY, reply.isNull("signage") ? "" : reply.optJSONObject("signage").toString()).apply();
            flushTechOutbox();
            boolean maint = reply.optBoolean("maintenance");
            if (maint != prefs.getBoolean(PREF_MAINTENANCE, false)) {
                prefs.edit().putBoolean(PREF_MAINTENANCE, maint).commit();
                // Door follows maintenance on the change only, so manual relay commands win until the next change.
                if (reply.optBoolean("relayLink", true) && relayState() != null) handler.post(() -> setRelay(maint, 0));
            }
            // The maintenance image set on the portal's Configuration page: fetched once per change.
            long img = reply.optLong("maintImage", 0);
            if (img != prefs.getLong(PREF_MAINT_IMG, 0) && !fetchingMaintImage) {
                fetchingMaintImage = true;
                new Thread(() -> fetchMaintImage(img)).start();
            }
            // Keep the lost-device screen up if lost mode is on (priority over everything).
            if (LostActivity.on(this) && !KioskActivity.monitorOnly(this)) {
                handler.post(() -> startActivity(new Intent(this, LostActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));
            }
            // Keep the maintenance screen up (also covers reboots and an app coming to the front).
            if (maint && !maintenanceShowing && !KioskActivity.monitorOnly(this)) {
                handler.post(() -> startActivity(new Intent(this, MaintenanceActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));
            }
            // Announcement: re-sent by the server until acked; the maintenance screen has priority.
            JSONObject ann = reply.optJSONObject("announcement");
            if (ann != null && !maint && !KioskActivity.monitorOnly(this)) {
                String aid = ann.optString("id");
                if (!aid.isEmpty() && !aid.equals(AnnouncementActivity.showing)
                        && !prefs.getStringSet(PREF_ACKS, java.util.Collections.emptySet()).contains(aid)) {
                    AnnouncementActivity.showing = aid;
                    handler.post(() -> startActivity(new Intent(this, AnnouncementActivity.class)
                            .putExtra("id", aid).putExtra("text", ann.optString("text"))
                            .putExtra("image", ann.isNull("image") ? "" : ann.optString("image"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));
                }
            }
            String tz = reply.optString("timeZone");
            if (!tz.isEmpty() && !tz.equals(java.util.TimeZone.getDefault().getID())) handler.post(() -> setTimeZone(tz));
            JSONObject cmd = reply.optJSONObject("command");
            if (cmd != null) handler.post(() -> runCommand(cmd));
        } catch (Exception e) {
            Log.w(TAG, "report failed: " + e);
            setServerOk(getSharedPreferences("kiosk", MODE_PRIVATE), false);
        }
    }

    // ---- tablet settings from the portal (the site's, or this tablet's own): brightness, volume, system updates are
    // applied when they change; the night window (screen off / on) is checked on every check-in.
    private String appliedSettings = "";
    private Boolean wasNight;

    private void applySettings(JSONObject s) {
        if (s == null) s = new JSONObject();
        DevicePolicyManager dpm = getSystemService(DevicePolicyManager.class);
        ComponentName admin = new ComponentName(this, AdminReceiver.class);
        if (!s.toString().equals(appliedSettings)) {
            boolean first = appliedSettings.isEmpty();
            appliedSettings = s.toString();
            try {
                if (!s.isNull("brightness")) {
                    dpm.setSystemSetting(admin, Settings.System.SCREEN_BRIGHTNESS_MODE, "0");
                    dpm.setSystemSetting(admin, Settings.System.SCREEN_BRIGHTNESS, String.valueOf(Math.round(s.optInt("brightness") * 2.55f)));
                }
                if (!s.isNull("volume")) {
                    AudioManager am = getSystemService(AudioManager.class);
                    int[] streams = {AudioManager.STREAM_MUSIC, AudioManager.STREAM_RING, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM, AudioManager.STREAM_ALARM};
                    for (int st : streams) {
                        try {
                            am.setStreamVolume(st, Math.round(am.getStreamMaxVolume(st) * s.optInt("volume") / 100f), 0);
                        } catch (SecurityException ignored) { // ring / notification while Do Not Disturb is on
                        }
                    }
                }
                String u = s.optString("updates");
                dpm.setSystemUpdatePolicy(admin, u.equals("auto") ? android.app.admin.SystemUpdatePolicy.createAutomaticInstallPolicy()
                        : u.equals("night") ? android.app.admin.SystemUpdatePolicy.createWindowedInstallPolicy(60, 300)
                        : u.equals("postpone") ? android.app.admin.SystemUpdatePolicy.createPostponeInstallPolicy() : null);
                if (!first) event(this, "Tablet settings applied");
            } catch (SecurityException e) {
                event(this, "Tablet settings need device owner");
            }
        }
        // Night mode: screen off once when the window starts, back on once when it ends.
        boolean night = inWindow(s.optString("screenOff"), s.optString("screenOn"));
        if (wasNight != null && night != wasNight) {
            if (night) {
                try {
                    dpm.lockNow();
                } catch (SecurityException ignored) {
                }
            } else {
                android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
                @SuppressWarnings("deprecation") android.os.PowerManager.WakeLock wl = pm.newWakeLock(
                        android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK | android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP, "kiosk:morning");
                wl.acquire(3000);
                if (!KioskActivity.monitorOnly(this)) handler.post(() -> startActivity(new Intent(this, KioskActivity.class)
                        .putExtra(KioskActivity.EXTRA_WAKE, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            }
        }
        wasNight = night;
    }

    // "22:00".."07:00" also works across midnight. Both times needed.
    private static boolean inWindow(String off, String on) {
        if (!off.matches("\\d\\d:\\d\\d") || !on.matches("\\d\\d:\\d\\d") || off.equals(on)) return false;
        java.util.Calendar c = java.util.Calendar.getInstance();
        int m = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
        int a = Integer.parseInt(off.substring(0, 2)) * 60 + Integer.parseInt(off.substring(3));
        int b = Integer.parseInt(on.substring(0, 2)) * 60 + Integer.parseInt(on.substring(3));
        return a < b ? m >= a && m < b : m >= a || m < b;
    }

    static final String PREF_CONFIG_DIRTY = "config_dirty", PREF_TECH_NAME = "tech_name";

    // Model / usage set on the tablet (setup or admin menu): sent with the next check-ins until the server has them.
    static void setConfig(Context ctx, String model, String usage) {
        ctx.getSharedPreferences("kiosk", MODE_PRIVATE).edit().putString(Relay.PREF_MODEL, model).putString(Relay.PREF_USAGE, usage)
                .putBoolean(PREF_CONFIG_DIRTY, true).commit();
        event(ctx, "Model set on the device: " + model + ", " + ("tech".equals(usage) ? "technical team device" : "onsite device"));
        reportNow();
    }

    // The server's copy of model / usage (a portal edit) and the relay setup to use. Old servers send neither.
    private void applyConfig(SharedPreferences prefs, JSONObject reply, boolean sentDirty, String sentModel, String sentUsage) throws Exception {
        SharedPreferences.Editor e = prefs.edit();
        boolean stillDirty = sentDirty && !(sentModel.equals(prefs.getString(Relay.PREF_MODEL, "")) && sentUsage.equals(prefs.getString(Relay.PREF_USAGE, "onsite")));
        if (sentDirty && !stillDirty) e.putBoolean(PREF_CONFIG_DIRTY, false);
        JSONObject cfg = reply.optJSONObject("config");
        String oldUsage = prefs.getString(Relay.PREF_USAGE, "onsite");
        if (cfg != null && !stillDirty) {
            e.putString(Relay.PREF_MODEL, cfg.optString("model")).putString(Relay.PREF_USAGE, cfg.optString("usage", "onsite"))
                    .putString(PREF_TECH_NAME, cfg.isNull("tech") ? "" : cfg.optString("tech"));
        }
        if (cfg != null) e.putBoolean(TechHomeActivity.PREF_TICKETS_OFF, cfg.optBoolean("ticketsOff"));
        if (reply.has("relayCfg")) {
            JSONObject rc = reply.optJSONObject("relayCfg");
            if (rc == null) e.remove(Relay.PREF_CFG); else e.putString(Relay.PREF_CFG, rc.toString());
        }
        e.commit();
        // Usage changed in the portal: reopen the home screen, which shows the kiosk or the technician screen.
        if (!oldUsage.equals(prefs.getString(Relay.PREF_USAGE, "onsite")) && !KioskActivity.monitorOnly(this)) {
            handler.post(() -> startActivity(new Intent(this, KioskActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        }
    }

    // ---- technical team tablet: ticket API (device key + this device's id; the server checks whose tablet it is) ----
    // Updates that fail (no network) wait in PREF_TECH_OUTBOX and are retried with every check-in. Each carries an op id,
    // so the server ignores one that already arrived.
    static final String PREF_TECH_OUTBOX = "tech_outbox";

    static JSONObject techCall(Context ctx, String method, String path, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(serverUrl(ctx) + path).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setRequestMethod(method);
            c.setRequestProperty("X-Device-Key", key(ctx));
            c.setRequestProperty("X-Device-Id", ctx.getSharedPreferences("kiosk", MODE_PRIVATE).getString(PREF_DEVICE_ID, ""));
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            return readReply(c);
        } finally {
            c.disconnect();
        }
    }

    // Photo / PDF upload with progress (0-100). Throws with the server's message on failure.
    static JSONObject techUpload(Context ctx, String path, byte[] data, java.util.function.IntConsumer progress) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(serverUrl(ctx) + path).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(60000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(data.length);
            c.setRequestProperty("Content-Type", "application/octet-stream");
            c.setRequestProperty("X-Device-Key", key(ctx));
            c.setRequestProperty("X-Device-Id", ctx.getSharedPreferences("kiosk", MODE_PRIVATE).getString(PREF_DEVICE_ID, ""));
            try (OutputStream out = c.getOutputStream()) {
                for (int i = 0; i < data.length; i += 16384) {
                    out.write(data, i, Math.min(16384, data.length - i));
                    progress.accept((int) (100L * Math.min(data.length, i + 16384) / data.length));
                }
            }
            return readReply(c);
        } finally {
            c.disconnect();
        }
    }

    // JSON reply; a 4xx/5xx becomes an exception with the server's error text (HttpError.status tells them apart).
    static final class HttpError extends Exception {
        final int status;
        HttpError(int status, String msg) { super(msg); this.status = status; }
    }
    private static JSONObject readReply(HttpURLConnection c) throws Exception {
        int status = c.getResponseCode();
        InputStream in = status < 400 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        if (in != null) try (InputStream i = in) {
            byte[] chunk = new byte[4096];
            for (int n; (n = i.read(chunk)) > 0; ) buf.write(chunk, 0, n);
        }
        JSONObject j;
        try {
            j = new JSONObject(buf.toString("UTF-8"));
        } catch (Exception e) {
            // A redirect or proxy page is not a reply: read as {} it meant "maintenance off" and dropped the maintenance
            // screen until the next check-in. A plain Exception, so callers treat it like a network failure and retry.
            if (status < 400) throw new Exception("Bad reply from server: HTTP " + status);
            j = new JSONObject();
        }
        if (status >= 400) throw new HttpError(status, j.optString("error", "HTTP " + status));
        return j;
    }

    // Queues a ticket update to send later (no network now).
    static synchronized void techQueue(Context ctx, String path, JSONObject body) {
        SharedPreferences p = ctx.getSharedPreferences("kiosk", MODE_PRIVATE);
        try {
            JSONArray a = new JSONArray(p.getString(PREF_TECH_OUTBOX, "[]"));
            if (a.length() < 100) a.put(new JSONObject().put("path", path).put("body", body));
            p.edit().putString(PREF_TECH_OUTBOX, a.toString()).commit();
        } catch (Exception ignored) {
        }
    }

    // Sends queued updates in order. A refused one (4xx: e.g. the ticket was reassigned) is dropped and noted; a network
    // failure stops here and the rest wait for the next check-in. Runs on the net thread.
    private synchronized void flushTechOutbox() {
        SharedPreferences p = getSharedPreferences("kiosk", MODE_PRIVATE);
        try {
            JSONArray a = new JSONArray(p.getString(PREF_TECH_OUTBOX, "[]"));
            int done = 0;
            for (; done < a.length(); done++) {
                JSONObject x = a.getJSONObject(done);
                try {
                    techCall(this, "POST", x.getString("path"), x.getJSONObject("body"));
                } catch (HttpError e) {
                    if (e.status >= 500) break;
                    event(this, "Ticket update not accepted: " + e.getMessage());
                } catch (Exception e) {
                    break; // network: try again on the next check-in
                }
            }
            if (done == 0) return;
            JSONArray rest = new JSONArray(p.getString(PREF_TECH_OUTBOX, "[]")), keep = new JSONArray();
            for (int i = done; i < rest.length(); i++) keep.put(rest.get(i));
            p.edit().putString(PREF_TECH_OUTBOX, keep.toString()).commit();
        } catch (Exception e) {
            Log.w(TAG, "tech outbox: " + e);
        }
    }

    private static void setServerOk(SharedPreferences p, boolean ok) {
        if (p.getBoolean(PREF_SERVER_OK, !ok) != ok) p.edit().putBoolean(PREF_SERVER_OK, ok).apply();
    }

    // Drops acks the server has now received (a new one may have been added meanwhile).
    private static synchronized void clearAcks(java.util.Set<String> sent) {
        SharedPreferences p = appPrefs;
        if (p == null || sent.isEmpty()) return;
        java.util.Set<String> now = new java.util.HashSet<>(p.getStringSet(PREF_ACKS, java.util.Collections.emptySet()));
        now.removeAll(sent);
        p.edit().putStringSet(PREF_ACKS, now).commit();
    }

    // Acknowledge pressed: remembered until the server has it, then sent with an immediate check-in.
    static synchronized void ack(Context ctx, String id) {
        SharedPreferences p = ctx.getSharedPreferences("kiosk", MODE_PRIVATE);
        java.util.Set<String> now = new java.util.HashSet<>(p.getStringSet(PREF_ACKS, java.util.Collections.emptySet()));
        now.add(id);
        p.edit().putStringSet(PREF_ACKS, now).commit();
        reportNow();
    }

    // Check in now instead of waiting for the next 10 s tick.
    static void reportNow() {
        DeviceService s = self;
        if (s != null) s.net.execute(s::report);
    }

    // Announcement image: plain GET with the device key (no checksum, unlike installs). Off the main thread.
    static android.graphics.Bitmap fetchImage(Context ctx, String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(serverUrl(ctx) + path).openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(60000);
            c.setRequestProperty("X-Device-Key", key(ctx));
            if (c.getResponseCode() != 200) throw new Exception("image HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                return android.graphics.BitmapFactory.decodeStream(in);
            }
        } finally {
            c.disconnect();
        }
    }

    // Drops events and the crash report the server has now received (new ones may have arrived meanwhile).
    private static synchronized void clearSent(String events, String crash) throws Exception {
        SharedPreferences p = appPrefs;
        if (p == null) return;
        SharedPreferences.Editor e = p.edit();
        JSONArray now = new JSONArray(p.getString(PREF_EVENTS, "[]")), sent = new JSONArray(events), rest = new JSONArray();
        for (int i = sent.length(); i < now.length(); i++) rest.put(now.get(i));
        e.putString(PREF_EVENTS, rest.toString());
        if (crash.equals(p.getString(PREF_CRASH, ""))) e.remove(PREF_CRASH);
        e.commit();
    }
    private static SharedPreferences appPrefs;
    private static DeviceService self;
    // Android 9/10 screenshots: kept for the life of the process once the user allowed it (ScreenshotActivity).
    static android.media.projection.MediaProjection projection;
    private static boolean screenshotWaiting;
    private String sentApps, sentAllowed;

    private JSONArray launcherApps() {
        android.content.pm.PackageManager pm = getPackageManager();
        java.util.TreeMap<String, String> byLabel = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            if (!ri.activityInfo.packageName.equals(getPackageName())) byLabel.put(ri.loadLabel(pm).toString(), ri.activityInfo.packageName);
        }
        JSONArray a = new JSONArray();
        for (java.util.Map.Entry<String, String> e : byLabel.entrySet()) a.put(new JSONArray().put(e.getValue()).put(e.getKey()));
        return a;
    }

    // CPU load %: real use since the last check-in from /proc/stat where the system allows it (Android 7 and many
    // terminals, e.g. Telpo F8), otherwise an estimate from each core's clock speed (newer Android blocks /proc/stat).
    private long prevBusy = -1, prevTotal, prevIdle = -1, prevIdleAt;
    private String cpuSource = "";
    // Chip name (e.g. rk3399, mt6789); Build.HARDWARE is often a generic board name on older Android.
    private static String boardPlatform() {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(Runtime.getRuntime().exec(new String[]{"getprop", "ro.board.platform"}).getInputStream()))) {
            String p = r.readLine();
            if (p != null && !p.trim().isEmpty()) return p.trim();
        } catch (Exception ignored) {
        }
        return Build.HARDWARE;
    }

    private String tempSource = "";
    private static Double socTemp() {
        java.io.File[] zones = new java.io.File("/sys/class/thermal").listFiles((dir, n) -> n.startsWith("thermal_zone"));
        if (zones != null) for (java.io.File z : zones) {
            try (java.io.BufferedReader t = new java.io.BufferedReader(new java.io.FileReader(new java.io.File(z, "type")));
                 java.io.BufferedReader v = new java.io.BufferedReader(new java.io.FileReader(new java.io.File(z, "temp")))) {
                if (!t.readLine().toLowerCase(java.util.Locale.US).matches(".*(soc|cpu).*")) continue;
                double c = Long.parseLong(v.readLine().trim()) / 1000.0;
                if (c > 0 && c < 150) return Math.round(c * 10) / 10.0;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private Integer cpuLoad() {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/stat"))) {
            String[] f = r.readLine().trim().split("\\s+"); // cpu user nice system idle iowait irq softirq steal
            long idle = Long.parseLong(f[4]) + Long.parseLong(f[5]), total = 0;
            for (int i = 1; i < Math.min(f.length, 9); i++) total += Long.parseLong(f[i]);
            long busy = total - idle, dBusy = busy - prevBusy, dTotal = total - prevTotal;
            boolean first = prevBusy < 0;
            prevBusy = busy; prevTotal = total;
            cpuSource = "Measured";
            if (!first && dTotal > 0) return (int) Math.round(100.0 * dBusy / dTotal);
            return (int) Math.round(100.0 * busy / total); // first sample: average since boot
        } catch (Exception ignored) {
        }
        // Android 8+ hides /proc/stat from apps (Samsung). The per-core idle times are still readable and give the real
        // load; the clock-speed guess below read 90%+ on an idle tablet, since clocks jump to max at every small task.
        long idle = 0, now = android.os.SystemClock.elapsedRealtimeNanos() / 1000;
        int cores = 0;
        for (int i = 0; new java.io.File("/sys/devices/system/cpu/cpu" + i).exists(); i++) {
            boolean counted = false;
            for (int s = 0; ; s++) {
                try (java.io.BufferedReader t = new java.io.BufferedReader(new java.io.FileReader("/sys/devices/system/cpu/cpu" + i + "/cpuidle/state" + s + "/time"))) {
                    idle += Long.parseLong(t.readLine().trim()); // microseconds in this idle state
                    counted = true;
                } catch (Exception e) {
                    break;
                }
            }
            if (counted) cores++;
        }
        if (cores > 0) {
            Integer load = null;
            if (prevIdle >= 0 && now > prevIdleAt) load = (int) Math.round(100 - 100.0 * (idle - prevIdle) / ((double) (now - prevIdleAt) * cores));
            prevIdle = idle;
            prevIdleAt = now;
            cpuSource = "Measured (idle time)";
            return load == null ? null : Math.max(0, Math.min(100, load)); // first sample: nothing to compare yet
        }
        long cur = 0, max = 0;
        for (int i = 0; i < Runtime.getRuntime().availableProcessors(); i++) {
            String dir = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/";
            try (java.io.BufferedReader c = new java.io.BufferedReader(new java.io.FileReader(dir + "scaling_cur_freq"));
                 java.io.BufferedReader m = new java.io.BufferedReader(new java.io.FileReader(dir + "cpuinfo_max_freq"))) {
                cur += Long.parseLong(c.readLine().trim());
                max += Long.parseLong(m.readLine().trim());
            } catch (Exception ignored) { // core offline
            }
        }
        if (max == 0) return null;
        cpuSource = "Estimated from CPU clock speed";
        return (int) Math.round(100.0 * cur / max);
    }

    // RAM / storage used (%), CPU load, battery temperature and Wi-Fi signal.
    @SuppressWarnings("deprecation")
    private void putHealth(JSONObject d) throws Exception {
        Integer cpu = cpuLoad();
        if (cpu != null) d.put("cpu", Math.max(0, Math.min(100, cpu)));
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        getSystemService(ActivityManager.class).getMemoryInfo(mi);
        d.put("ram", Math.round(100.0 * (mi.totalMem - mi.availMem) / mi.totalMem));
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        d.put("disk", Math.round(100.0 * (fs.getTotalBytes() - fs.getAvailableBytes()) / fs.getTotalBytes()));
        Intent batt = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        // Temperature: the chip (SoC / CPU) sensor where the system lets apps read it (Telpo), else the battery sensor
        // (mains-powered terminals report a fake battery value there).
        Double soc = socTemp();
        if (soc != null) d.put("temp", soc);
        else if (batt != null && batt.hasExtra(android.os.BatteryManager.EXTRA_TEMPERATURE)) {
            d.put("temp", batt.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0);
        }
        tempSource = soc != null ? "CPU chip sensor" : "Battery sensor";
        WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
        if (wm != null && wm.getConnectionInfo() != null && wm.getConnectionInfo().getNetworkId() != -1) {
            d.put("signal", wm.getConnectionInfo().getRssi());
        }
        String lip = localIp();
        if (lip != null) d.put("localIp", lip);
        JSONObject info = new JSONObject();
        info.put("Memory", String.format(java.util.Locale.US, "%.1f of %.1f GB free", mi.availMem / 1e9, mi.totalMem / 1e9));
        info.put("Storage", String.format(java.util.Locale.US, "%.1f of %.1f GB free", fs.getAvailableBytes() / 1e9, fs.getTotalBytes() / 1e9));
        info.put("Mode", KioskActivity.monitorOnly(this) ? "Monitor only" : "Kiosk");
        // System details for the portal's device page (shown as-is in its Details list).
        info.put("Manufacturer", Build.MANUFACTURER + " · " + Build.DEVICE);
        info.put("Android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") · patch " + Build.VERSION.SECURITY_PATCH);
        info.put("Build", Build.DISPLAY);
        info.put("CPU", (Build.VERSION.SDK_INT >= 31 && !Build.UNKNOWN.equals(Build.SOC_MODEL) ? Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL : boardPlatform())
                + " · " + Runtime.getRuntime().availableProcessors() + " cores · " + Build.SUPPORTED_ABIS[0]);
        if (!cpuSource.isEmpty()) info.put("CPU load", cpuSource);
        if (!tempSource.isEmpty()) info.put("Temperature sensor", tempSource);
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/loadavg"))) {
            String[] l = r.readLine().split(" ");
            info.put("Load average", l[0] + " · " + l[1] + " · " + l[2] + " (1 / 5 / 15 min)");
        } catch (Exception ignored) { // blocked on newer Android
        }
        info.put("Kernel", System.getProperty("os.version"));
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        getSystemService(android.view.WindowManager.class).getDefaultDisplay().getRealMetrics(dm);
        info.put("Screen", dm.widthPixels + " × " + dm.heightPixels + " · " + dm.densityDpi + " dpi");
        android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
        android.net.NetworkCapabilities nc = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
        if (nc == null) info.put("Network", "None");
        else if (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) info.put("Network", "Ethernet");
        else if (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) && wm != null && wm.getConnectionInfo() != null) {
            android.net.wifi.WifiInfo wi = wm.getConnectionInfo();
            info.put("Network", "Wi-Fi · " + (wi.getFrequency() > 4000 ? "5 GHz" : "2.4 GHz") + " · " + wi.getLinkSpeed() + " Mbps · " + wi.getRssi() + " dBm");
        } else info.put("Network", nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ? "Mobile data" : "Other");
        if (batt != null && batt.getBooleanExtra(android.os.BatteryManager.EXTRA_PRESENT, false)) {
            int h = batt.getIntExtra(android.os.BatteryManager.EXTRA_HEALTH, 1);
            String health = h == 2 ? "Good" : h == 3 ? "Overheating" : h == 4 ? "Dead" : h == 5 ? "Over voltage" : h == 7 ? "Cold" : h == 6 ? "Failure" : "Unknown";
            int plug = batt.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0);
            int mv = batt.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, 0); // 0 on mains-powered terminals
            info.put("Battery", health + (mv > 1000 ? String.format(java.util.Locale.US, " · %.2f V", mv / 1000.0) : "")
                    + (plug == 1 ? " · on charger" : plug == 2 ? " · on USB" : plug == 4 ? " · wireless charging" : " · on battery"));
        }
        d.put("info", info);
    }

    // Admin typed a 4-digit Device ID created in the portal. Runs off the main thread; returns the server's reply.
    static JSONObject link(Context ctx, String code) throws Exception {
        SharedPreferences p = ctx.getSharedPreferences("kiosk", MODE_PRIVATE);
        JSONObject body = new JSONObject().put("id", p.getString(PREF_DEVICE_ID, "")).put("code", code);
        HttpURLConnection c = (HttpURLConnection) new URL(serverUrl(ctx) + "/api/link").openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("X-Device-Key", key(ctx));
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            for (int n; (n = in.read(chunk)) > 0; ) buf.write(chunk, 0, n);
            JSONObject reply = new JSONObject(buf.toString("UTF-8"));
            if (reply.optBoolean("ok")) p.edit().putString(PREF_CODE, reply.getString("code")).apply();
            return reply;
        } finally {
            c.disconnect();
        }
    }

    private JSONObject post(String url, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("X-Device-Key", key(this));
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                // InputStream.readAllBytes() is Android 13+.
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                for (int n; (n = in.read(chunk)) > 0; ) buf.write(chunk, 0, n);
                return new JSONObject(buf.toString("UTF-8"));
            }
        } finally {
            c.disconnect();
        }
    }

    private void runCommand(JSONObject cmd) {
        String type = cmd.optString("type");
        DevicePolicyManager dpm = getSystemService(DevicePolicyManager.class);
        try {
            if (type.equals("lockscreen")) dpm.lockNow();
            if (type.equals("reboot")) dpm.reboot(new ComponentName(this, AdminReceiver.class));
            if (type.equals("ring")) ring();
        } catch (SecurityException e) {
            Log.w(TAG, type + " needs device owner", e);
            event(this, type + " failed: app is not device owner");
        }
        // Slow jobs run on their own thread so check-ins keep going.
        if (type.equals("screenshot")) screenshot();
        if (type.equals("live")) startLive();
        if (type.equals("term")) startTerm();
        if (type.equals("run")) new Thread(() -> runShell(cmd), "run").start();
        if (type.equals("apps")) setAllowedApps(cmd.optJSONArray("packages"));
        if (type.equals("wifi")) addWifi(cmd.optString("ssid"), cmd.optString("password"));
        if (type.equals("browse")) new Thread(() -> browse(cmd.optString("path", "/sdcard")), "browse").start();
        if (type.equals("pull")) new Thread(() -> pullFile(cmd.optString("path")), "pull").start();
        if (type.equals("lost") || type.equals("found")) {
            String json = "";
            if (type.equals("lost")) {
                try {
                    json = new JSONObject().put("message", cmd.optString("message")).put("phone", cmd.optString("phone")).toString();
                } catch (JSONException e) {
                    json = "{}";
                }
            }
            getSharedPreferences("kiosk", MODE_PRIVATE).edit().putString(KioskActivity.PREF_LOST_KEY, json).commit();
            event(this, type.equals("lost") ? "Lost mode on" : "Lost mode off");
            if (type.equals("lost") && !KioskActivity.monitorOnly(this)) startActivity(new Intent(this, LostActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        if (type.equals("wipe")) {
            try {
                event(this, "Factory reset started from the portal");
                dpm.wipeData(0);
            } catch (SecurityException e) {
                event(this, "Wipe failed: app is not device owner");
            }
        }
        if (type.equals("relay")) setRelay(cmd.optBoolean("on"), cmd.optInt("seconds"));
        if (type.equals("install") || type.equals("file") || type.equals("logs")) {
            new Thread(() -> {
                try {
                    if (type.equals("install")) install(cmd);
                    if (type.equals("file")) receiveFile(cmd);
                    if (type.equals("logs")) uploadLogs();
                } catch (Exception e) {
                    Log.w(TAG, type + " failed", e);
                    event(this, type + " " + cmd.optString("name") + " failed: " + e.getMessage());
                }
            }).start();
        }
        Intent open = new Intent(this, KioskActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (type.equals("unlock")) open.putExtra(KioskActivity.EXTRA_REMOTE_UNLOCK, true);
        if (type.equals("singleapp")) {
            String pkg = cmd.optString("package");
            if (!pkg.isEmpty() && getPackageManager().getLaunchIntentForPackage(pkg) == null) {
                event(this, "Can't pin " + pkg + ": not installed");
                return;
            }
            KioskActivity.setSingleApp(this, pkg);
            sentApps = null;
            startActivity(open); // reopen the kiosk screen, which locks and starts the app
        }
        // Portal "Restart pinned app": hiding an app kills it, unhiding brings it straight back. One restart, then the
        // kiosk screen opens and pins it again as usual.
        if (type.equals("restartapp")) {
            String pkg = getSharedPreferences("kiosk", MODE_PRIVATE).getString(KioskActivity.PREF_SINGLE_APP, "");
            if (pkg.isEmpty()) {
                event(this, "Restart app: no app is pinned");
            } else {
                try {
                    ComponentName admin = new ComponentName(this, AdminReceiver.class);
                    dpm.setApplicationHidden(admin, pkg, true);
                    dpm.setApplicationHidden(admin, pkg, false);
                    event(this, "Pinned app restarted: " + pkg);
                } catch (SecurityException e) {
                    event(this, "Restart app failed: app is not device owner");
                }
                startActivity(open);
            }
        }
        // Device owner apps may start activities from the background. Opening the kiosk screen locks it.
        if (type.equals("unlock") || type.equals("lock")) startActivity(open);
        handler.postDelayed(() -> net.execute(this::report), 3000);
    }

    // ---- door relay: the file and values come from Relay (model profile, or the portal's verified setup) ----
    private final Runnable relayOff = () -> setRelay(false, 0);

    // null = this device has no relay.
    private Boolean relayState() {
        Relay r = Relay.current(this);
        return r == null ? null : r.state(this);
    }

    // seconds > 0: pulse (on now, off after N s). A new call cancels a pending pulse. Runs on the main thread.
    private void setRelay(boolean on, int seconds) {
        handler.removeCallbacks(relayOff);
        Relay r = Relay.current(this);
        if (r == null || !r.file.exists()) {
            event(this, r == null ? "Relay: this device model has no relay" : "Relay: " + r.file + " not found on this device");
            return;
        }
        boolean value = on || seconds > 0;
        try {
            r.set(this, value);
        } catch (Exception e) {
            Log.w(TAG, "relay write failed", e);
            event(this, "Relay failed: " + e.getMessage());
            net.execute(this::report);
            return;
        }
        if (seconds > 0) handler.postDelayed(relayOff, seconds * 1000L);
        net.execute(this::report);
    }

    // Portal "Allowed apps": same effect as the admin menu's app picker. KioskActivity redraws its tiles via its pref listener.
    private void setAllowedApps(JSONArray pkgs) {
        if (pkgs == null) return;
        java.util.Set<String> apps = new java.util.TreeSet<>();
        for (int i = 0; i < pkgs.length(); i++) if (!pkgs.optString(i).isEmpty()) apps.add(pkgs.optString(i));
        SharedPreferences p = getSharedPreferences("kiosk", MODE_PRIVATE);
        p.edit().putStringSet(KioskActivity.PREF_APPS, apps).commit();
        DevicePolicyManager dpm = getSystemService(DevicePolicyManager.class);
        if (dpm.isDeviceOwnerApp(getPackageName()) && !KioskActivity.monitorOnly(this)) {
            java.util.Set<String> lock = new java.util.TreeSet<>(apps);
            String single = p.getString(KioskActivity.PREF_SINGLE_APP, "");
            if (!single.isEmpty()) lock.add(single);
            lock.add(getPackageName());
            dpm.setLockTaskPackages(new ComponentName(this, AdminReceiver.class), lock.toArray(new String[0]));
        }
        event(this, "Allowed apps updated from portal");
    }

    // Downloads a portal file to `dest`, checking it against the sha256 the portal sent.
    private File download(JSONObject cmd, File dest) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(serverUrl(this) + cmd.getString("url")).openConnection();
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(60000);
            c.setRequestProperty("X-Device-Key", key(this));
            if (c.getResponseCode() != 200) throw new Exception("download HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[64 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) {
                    sha.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
            }
        } finally {
            c.disconnect();
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : sha.digest()) hex.append(String.format("%02x", b));
        if (!hex.toString().equals(cmd.optString("sha256"))) {
            dest.delete();
            throw new Exception("download corrupted (checksum mismatch)");
        }
        return dest;
    }

    // Silent install (device owner only). Updating this app itself restarts it; BootReceiver brings the service back.
    private void install(JSONObject cmd) throws Exception {
        File apk = download(cmd, new File(getCacheDir(), "install.apk"));
        PackageInstaller pi = getPackageManager().getPackageInstaller();
        int id = pi.createSession(new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL));
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("app.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                s.fsync(out);
            }
            Intent done = new Intent(this, InstallResult.class).putExtra("name", cmd.optString("name"));
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            s.commit(PendingIntent.getBroadcast(this, id, done, flags).getIntentSender());
        } finally {
            apk.delete();
        }
    }

    public static class InstallResult extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            String name = intent.getStringExtra("name");
            if (status == PackageInstaller.STATUS_SUCCESS) event(ctx, "Installed " + name);
            else if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) event(ctx, "Install of " + name + " needs a tap on the device (app is not device owner)");
            else event(ctx, "Install of " + name + " failed: " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
        }
    }

    // Saves a portal file into the device's Download/Kiosk folder.
    @SuppressWarnings("deprecation")
    private void receiveFile(JSONObject cmd) throws Exception {
        String name = cmd.getString("name").replaceAll("[\\\\/:*?\"<>|]", "_");
        File tmp = download(cmd, new File(getCacheDir(), "file.tmp"));
        try (InputStream in = new FileInputStream(tmp)) {
            OutputStream out;
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Kiosk");
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new Exception("could not create file");
                out = getContentResolver().openOutputStream(uri);
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Kiosk");
                dir.mkdirs();
                out = new FileOutputStream(new File(dir, name));
            }
            try (OutputStream o = out) {
                byte[] buf = new byte[64 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
            }
        } finally {
            tmp.delete();
        }
        event(this, "Received file " + name + " (Download/Kiosk)");
    }

    // Sends this app's recent log lines plus the last crash to the portal.
    private void uploadLogs() throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(", Android ").append(Build.VERSION.RELEASE)
                .append(", app ").append(getPackageManager().getPackageInfo(getPackageName(), 0).versionName).append("\n\n");
        String crash = getSharedPreferences("kiosk", MODE_PRIVATE).getString(PREF_CRASH, "");
        if (!crash.isEmpty()) sb.append("=== Last crash ===\n").append(crash).append("\n");
        sb.append("=== App log (newest last) ===\n");
        Process proc = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-t", "3000", "-v", "time"});
        try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            for (String line; (line = r.readLine()) != null; ) sb.append(line).append('\n');
        }
        HttpURLConnection c = (HttpURLConnection) new URL(serverUrl(this) + "/api/device-logs").openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(30000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            c.setRequestProperty("X-Device-Key", key(this));
            c.setRequestProperty("X-Device-Id", deviceId());
            byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = c.getOutputStream()) {
                out.write(body, Math.max(0, body.length - 1900 * 1024), Math.min(body.length, 1900 * 1024));
            }
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
        } finally {
            c.disconnect();
        }
    }

    // Device owner only. Automatic time zone has to be off or Android ignores the change.
    @SuppressWarnings("deprecation")
    private void setTimeZone(String tz) {
        DevicePolicyManager dpm = getSystemService(DevicePolicyManager.class);
        ComponentName admin = new ComponentName(this, AdminReceiver.class);
        if (!dpm.isDeviceOwnerApp(getPackageName()) || !java.util.Arrays.asList(java.util.TimeZone.getAvailableIDs()).contains(tz)) return;
        try {
            if (Build.VERSION.SDK_INT >= 30) dpm.setAutoTimeZoneEnabled(admin, false);
            else dpm.setGlobalSetting(admin, Settings.Global.AUTO_TIME_ZONE, "0");
            if (dpm.setTimeZone(admin, tz)) event(this, "Time zone set to " + tz);
        } catch (Exception e) {
            Log.w(TAG, "time zone", e);
        }
    }

    // ---- screenshots for the portal ----
    private void screenshot() {
        ScreenshotService a = ScreenshotService.instance;
        if (Build.VERSION.SDK_INT >= 30) {
            if (a == null) {
                screenshotFailed(this, "screenshot service is off on this device (run setup-tablet.bat once)");
                return;
            }
            a.capture(bmp -> net.execute(() -> uploadScreenshot(bmp)), err -> screenshotFailed(this, err));
            return;
        }
        if (projection != null) {
            captureProjection();
        } else {
            // Asks on the device; onProjectionReady() continues once the user taps Start now.
            screenshotWaiting = true;
            startActivity(new Intent(this, ScreenshotActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    static void onProjectionReady(Context ctx) {
        if (self != null && screenshotWaiting) {
            screenshotWaiting = false;
            self.handler.postDelayed(self::captureProjection, 600); // let the permission dialog disappear first
        }
    }

    private void captureProjection() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = dm.widthPixels, h = dm.heightPixels;
        android.view.WindowManager wm = getSystemService(android.view.WindowManager.class);
        android.graphics.Point real = new android.graphics.Point();
        wm.getDefaultDisplay().getRealSize(real);
        w = real.x;
        h = real.y;
        android.media.ImageReader reader = android.media.ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 2);
        android.hardware.display.VirtualDisplay vd;
        try {
            vd = projection.createVirtualDisplay("portal-screenshot", w, h, dm.densityDpi,
                    android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, handler);
        } catch (SecurityException e) {
            projection = null; // token expired: ask again next time
            screenshotFailed(this, "screen capture permission expired, take the screenshot again and allow it on the device");
            return;
        }
        int fw = w, fh = h;
        reader.setOnImageAvailableListener(r -> {
            android.media.Image img = r.acquireLatestImage();
            if (img == null) return;
            android.media.Image.Plane p = img.getPlanes()[0];
            int rowPx = p.getRowStride() / p.getPixelStride();
            android.graphics.Bitmap full = android.graphics.Bitmap.createBitmap(rowPx, fh, android.graphics.Bitmap.Config.ARGB_8888);
            full.copyPixelsFromBuffer(p.getBuffer());
            img.close();
            r.setOnImageAvailableListener(null, null);
            vd.release();
            r.close();
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(full, 0, 0, fw, fh);
            net.execute(() -> uploadScreenshot(bmp));
        }, handler);
    }

    static void screenshotFailed(Context ctx, String why) {
        event(ctx, "Screenshot failed: " + why);
        if (self != null) self.net.execute(() -> self.postScreenshot(null, why));
    }

    private void uploadScreenshot(android.graphics.Bitmap bmp) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, out);
        bmp.recycle();
        postScreenshot(out.toByteArray(), null);
    }

    // Runs on the `net` thread. Either a JPEG or an error message for the portal's screenshot window.
    private void postScreenshot(byte[] jpeg, String error) {
        try {
            String url = serverUrl(this) + "/api/screenshot" + (error == null ? "" : "?error=" + java.net.URLEncoder.encode(error, "UTF-8"));
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            try {
                c.setConnectTimeout(10000);
                c.setReadTimeout(30000);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "image/jpeg");
                c.setRequestProperty("X-Device-Key", key(this));
                c.setRequestProperty("X-Device-Id", deviceId());
                try (OutputStream o = c.getOutputStream()) {
                    if (jpeg != null) o.write(jpeg);
                }
                if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "screenshot upload failed", e);
        }
    }

    // ---- portal "Run command" (one device or many): one shell script, root on rooted tablets, output to the device page ----
    private void runShell(JSONObject cmd) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        boolean ok = false;
        try {
            boolean root = new File("/system/bin/su").exists() || new File("/system/xbin/su").exists();
            String script = cmd.optString("script");
            Process p = new ProcessBuilder(root ? new String[]{"su", "0", "sh", "-c", script} : new String[]{"sh", "-c", script})
                    .redirectErrorStream(true).directory(new File("/")).start();
            p.getOutputStream().close();
            Thread reader = new Thread(() -> {
                try (InputStream in = p.getInputStream()) {
                    byte[] b = new byte[4096];
                    for (int n; (n = in.read(b)) > 0; ) if (buf.size() < 100000) buf.write(b, 0, n);
                } catch (Exception ignored) {
                }
            });
            reader.start();
            reader.join(30000);
            if (reader.isAlive()) {
                p.destroy();
                buf.write("\n(stopped after 30 s)".getBytes(StandardCharsets.UTF_8));
            } else {
                int code = p.waitFor();
                ok = code == 0;
                if (code != 0) buf.write(("\n(exit code " + code + ")").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            byte[] m = e.toString().getBytes(StandardCharsets.UTF_8);
            buf.write(m, 0, m.length);
        }
        try {
            JSONObject r = new JSONObject().put("type", "run").put("name", cmd.optString("name")).put("ok", ok)
                    .put("output", buf.toString("UTF-8"));
            techCall(this, "POST", "/api/command-result", r);
        } catch (Exception e) {
            Log.w(TAG, "run result upload failed", e);
        }
    }

    // ---- remote shell: `sh -i` (as this app; `su` works on rooted tablets). Output goes up as it comes, typed lines
    // come back by long-poll. Ctrl+C (or exit) starts a fresh shell; the server says stop once the window is closed.
    private volatile boolean termOn;

    private void startTerm() {
        if (termOn) return;
        termOn = true;
        new Thread(this::termLoop, "term").start();
    }

    private void termLoop() {
        Process sh = null;
        int fails = 0;
        try {
            while (termOn) {
                if (sh == null || !sh.isAlive()) {
                    // Rooted tablets (Telpo): a root shell, full system control like `adb root`. Others: only this app's rights.
                    boolean root = new File("/system/bin/su").exists() || new File("/system/xbin/su").exists();
                    ProcessBuilder pb = new ProcessBuilder(root ? new String[]{"su", "0", "sh", "-i"} : new String[]{"sh", "-i"})
                            .redirectErrorStream(true).directory(new File("/"));
                    pb.environment().put("HOSTNAME", "tablet-" + appPrefs.getString(PREF_CODE, "")); // Android's mkshrc prompt: $HOSTNAME:$PWD $
                    pb.environment().put("HOME", getFilesDir().getPath());
                    Process p = sh = pb.start();
                    new Thread(() -> termOutput(p), "term-out").start();
                }
                try {
                    JSONObject r = liveCall("/api/term/input", null);
                    fails = 0;
                    if (r.optBoolean("stop")) break;
                    JSONArray in = r.optJSONArray("inputs");
                    for (int i = 0; in != null && i < in.length(); i++) {
                        String s = in.getString(i);
                        if (s.equals("\u0003")) {
                            sh.destroy();
                            sh.waitFor();
                            continue;
                        }
                        if (!sh.isAlive()) continue;
                        sh.getOutputStream().write(s.getBytes(StandardCharsets.UTF_8));
                        sh.getOutputStream().flush();
                    }
                } catch (Exception e) {
                    if (++fails >= 5) break;
                    Thread.sleep(1000);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "remote shell stopped", e);
        } finally {
            if (sh != null) sh.destroy();
            termOn = false;
        }
    }

    private void termOutput(Process sh) {
        byte[] b = new byte[8192];
        try (InputStream in = sh.getInputStream()) {
            for (int n; (n = in.read(b)) > 0; ) liveCall("/api/term", java.util.Arrays.copyOf(b, n));
        } catch (Exception ignored) {
        }
        try {
            if (termOn) liveCall("/api/term", "\n[shell ended, starting a new one]\n".getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    // ---- live remote control: frames up, the portal's taps come back in each reply, until nobody watches ----
    private volatile boolean live;

    private void startLive() {
        if (live) return;
        if (ScreenshotService.instance == null) {
            event(this, "Remote control needs the screenshot service (run setup-tablet.bat once)");
            return;
        }
        live = true;
        new Thread(this::liveLoop, "live").start();
    }

    private volatile int liveW, liveH;

    // Pictures: as often as Android allows screenshots (once a second on 11-13, three times on 14+). Rooted devices
    // (Telpo) use screencap instead: ~0.2 s a picture, so about 4 a second.
    private void liveLoop() {
        long until = System.currentTimeMillis() + 30 * 60 * 1000; // a forgotten window can't stream forever
        long gap = Build.VERSION.SDK_INT >= 34 ? 340 : 1010, last = 0;
        int fails = 0;
        Boolean root = null; // found out on the first picture
        new Thread(this::liveInputs, "live-in").start();
        try {
            while (live && System.currentTimeMillis() < until) {
                ScreenshotService a = ScreenshotService.instance;
                if (a == null) break;
                long wait = last + (root == Boolean.TRUE ? 120 : gap) - System.currentTimeMillis();
                if (wait > 0) Thread.sleep(wait);
                last = System.currentTimeMillis();
                android.graphics.Bitmap[] got = new android.graphics.Bitmap[1];
                if (root != Boolean.FALSE) {
                    got[0] = rootCapture();
                    if (root == null) root = got[0] != null;
                }
                if (root == Boolean.FALSE && Build.VERSION.SDK_INT < 30) {
                    event(this, "Remote control needs Android 11+ or a rooted device");
                    break;
                }
                if (root == Boolean.FALSE) {
                    java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
                    a.capture(b -> { got[0] = b; done.countDown(); }, err -> done.countDown());
                    done.await(3, java.util.concurrent.TimeUnit.SECONDS);
                }
                if (got[0] == null) continue;
                int w = got[0].getWidth(), h = got[0].getHeight();
                float k = Math.min(1f, 720f / Math.max(w, h)); // small pictures keep it quick on slow Wi-Fi
                android.graphics.Bitmap small = android.graphics.Bitmap.createScaledBitmap(got[0], Math.round(w * k), Math.round(h * k), true);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, out);
                if (small != got[0]) small.recycle();
                got[0].recycle();
                liveW = w;
                liveH = h;
                try {
                    if (liveCall("/api/live?w=" + w + "&h=" + h, out.toByteArray()).optBoolean("stop")) break;
                    fails = 0;
                } catch (Exception e) {
                    if (++fails >= 5) throw e; // a Wi-Fi blip shouldn't end the session
                    Thread.sleep(1000);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "live remote control stopped", e);
        } finally {
            live = false;
        }
    }

    // The screen through `su 0 screencap` (raw: a 16-byte header w, h, format, dataspace, then RGBA pixels).
    // null when the device isn't rooted or anything looks off.
    private static android.graphics.Bitmap rootCapture() {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "0", "screencap").start();
            java.io.DataInputStream in = new java.io.DataInputStream(new java.io.BufferedInputStream(p.getInputStream(), 1 << 16));
            byte[] head = new byte[16];
            in.readFully(head);
            java.nio.ByteBuffer hb = java.nio.ByteBuffer.wrap(head).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            int w = hb.getInt(), h = hb.getInt(), format = hb.getInt();
            if (format != 1 || w <= 0 || h <= 0 || (long) w * h > 16_000_000) return null; // 1 = RGBA_8888
            byte[] px = new byte[w * h * 4];
            in.readFully(px);
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
            b.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(px));
            return b;
        } catch (Exception e) {
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }

    // Taps and keys: a long-poll the server answers the moment one is sent from the portal.
    private void liveInputs() {
        int fails = 0;
        while (live) {
            try {
                JSONObject r = liveCall("/api/live/input", null);
                fails = 0;
                if (r.optBoolean("stop")) break;
                JSONArray in = r.optJSONArray("inputs");
                ScreenshotService a = ScreenshotService.instance;
                for (int i = 0; in != null && a != null && liveW > 0 && i < in.length(); i++) {
                    JSONObject o = in.getJSONObject(i);
                    a.input(o.optString("a"), (float) o.optDouble("x") * liveW, (float) o.optDouble("y") * liveH,
                            (float) o.optDouble("x2") * liveW, (float) o.optDouble("y2") * liveH);
                    if (i + 1 < in.length()) Thread.sleep(100);
                }
            } catch (Exception e) {
                if (++fails >= 5) break;
                try { Thread.sleep(1000); } catch (InterruptedException x) { break; }
            }
        }
        live = false;
    }

    private JSONObject liveCall(String path, byte[] jpeg) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(serverUrl(this) + path).openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(25000);
            c.setRequestProperty("X-Device-Key", key(this));
            c.setRequestProperty("X-Device-Id", deviceId());
            if (jpeg != null) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "image/jpeg");
                try (OutputStream o = c.getOutputStream()) {
                    o.write(jpeg);
                }
            }
            return readReply(c);
        } finally {
            c.disconnect();
        }
    }

    private volatile boolean fetchingMaintImage;

    private void fetchMaintImage(long version) {
        try {
            java.io.File f = new java.io.File(getFilesDir(), "maintenance.jpg");
            if (version == 0) {
                f.delete();
            } else {
                java.io.File tmp = new java.io.File(getFilesDir(), "maintenance.tmp");
                HttpURLConnection c = (HttpURLConnection) new URL(serverUrl(this) + "/api/maintenance.jpg").openConnection();
                try {
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(30000);
                    c.setRequestProperty("X-Device-Key", key(this));
                    c.setRequestProperty("X-Device-Id", deviceId());
                    if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
                    try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                        byte[] buf = new byte[16384];
                        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                    }
                } finally {
                    c.disconnect();
                }
                f.delete();
                if (!tmp.renameTo(f)) throw new Exception("could not save the image");
            }
            getSharedPreferences("kiosk", MODE_PRIVATE).edit().putLong(PREF_MAINT_IMG, version).commit();
        } catch (Exception e) {
            Log.w(TAG, "maintenance image download failed", e);
        } finally {
            fetchingMaintImage = false;
        }
    }

    // "Find my device": alarm sound at full volume for 20 seconds.
    // Push a Wi-Fi network (device owner): add it and connect. WPA2 if a password is given, else open.
    private void addWifi(String ssid, String password) {
        if (ssid == null || ssid.isEmpty()) return;
        try {
            WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
            if (!wm.isWifiEnabled()) wm.setWifiEnabled(true);
            @SuppressWarnings("deprecation") android.net.wifi.WifiConfiguration c = new android.net.wifi.WifiConfiguration();
            c.SSID = "\"" + ssid + "\"";
            if (password == null || password.isEmpty()) {
                c.allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE);
            } else {
                c.preSharedKey = "\"" + password + "\"";
                c.allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.WPA_PSK);
            }
            int id = wm.addNetwork(c);
            if (id < 0) { event(this, "Wi-Fi \"" + ssid + "\" could not be added"); return; }
            wm.enableNetwork(id, true);
            wm.reconnect();
            event(this, "Wi-Fi network \"" + ssid + "\" added");
        } catch (Exception e) {
            event(this, "Wi-Fi setup failed: " + e.getMessage());
        }
    }

    // App usage (top apps by foreground time, last 24 h) and data use (Wi-Fi + mobile today). Both need the
    // GET_USAGE_STATS appop (set at setup on non-rooted tablets); without it they are simply left out.
    private void putUsage(JSONObject d) {
        try {
            android.app.AppOpsManager ops = getSystemService(android.app.AppOpsManager.class);
            int mode = ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), getPackageName());
            if (mode != android.app.AppOpsManager.MODE_ALLOWED) return;
            long end = System.currentTimeMillis(), start = end - 24L * 3600 * 1000;
            android.app.usage.UsageStatsManager usm = getSystemService(android.app.usage.UsageStatsManager.class);
            java.util.List<android.app.usage.UsageStats> list = usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, start, end);
            if (list != null && !list.isEmpty()) {
                java.util.Map<String, Long> total = new java.util.HashMap<>();
                for (android.app.usage.UsageStats u : list) if (u.getTotalTimeInForeground() > 0)
                    total.merge(u.getPackageName(), u.getTotalTimeInForeground(), Long::sum);
                java.util.List<java.util.Map.Entry<String, Long>> top = new java.util.ArrayList<>(total.entrySet());
                top.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
                JSONArray usage = new JSONArray();
                for (int i = 0; i < Math.min(8, top.size()); i++) {
                    usage.put(new JSONArray().put(appLabel(top.get(i).getKey())).put(Math.round(top.get(i).getValue() / 60000.0))); // minutes
                }
                if (usage.length() > 0) d.put("usage", usage);
            }
            // Data today (local midnight -> now), total across Wi-Fi + mobile, in MB.
            android.app.usage.NetworkStatsManager nsm = getSystemService(android.app.usage.NetworkStatsManager.class);
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0); c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0);
            long midnight = c.getTimeInMillis();
            long wifi = bucketBytes(nsm, android.net.NetworkCapabilities.TRANSPORT_WIFI, midnight, end);
            long mobile = bucketBytes(nsm, android.net.NetworkCapabilities.TRANSPORT_CELLULAR, midnight, end);
            if (wifi >= 0 || mobile >= 0) d.put("data", new JSONObject()
                    .put("wifi", Math.round(Math.max(0, wifi) / 1e6)).put("mobile", Math.round(Math.max(0, mobile) / 1e6)));
        } catch (Exception e) {
            Log.w(TAG, "usage stats unavailable", e);
        }
    }

    private String appLabel(String pkg) {
        try {
            return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private static long bucketBytes(android.app.usage.NetworkStatsManager nsm, int transport, long start, long end) {
        try {
            android.app.usage.NetworkStats.Bucket b = nsm.querySummaryForDevice(
                    transport == android.net.NetworkCapabilities.TRANSPORT_WIFI ? android.net.ConnectivityManager.TYPE_WIFI : android.net.ConnectivityManager.TYPE_MOBILE,
                    null, start, end);
            return b == null ? -1 : b.getRxBytes() + b.getTxBytes();
        } catch (Exception e) {
            return -1;
        }
    }

    // File browser (admins, via the portal). Listing uses `ls` (root on rooted tablets, so any path); a download
    // streams the file straight to the server with no size cap.
    private boolean rooted() {
        return new File("/system/bin/su").exists() || new File("/system/xbin/su").exists();
    }

    private String[] sh(String cmd) {
        return rooted() ? new String[]{"su", "0", "sh", "-c", cmd} : new String[]{"sh", "-c", cmd};
    }

    private void browse(String path) {
        JSONArray entries = new JSONArray();
        String error = null;
        try {
            // -1 one per line, -A almost-all, -p mark directories with a trailing slash.
            Process p = new ProcessBuilder(sh("ls -1Ap \"" + path.replace("\"", "") + "\"")).redirectErrorStream(true).start();
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null && entries.length() < 2000; ) {
                if (line.isEmpty()) continue;
                boolean dir = line.endsWith("/");
                String nm = dir ? line.substring(0, line.length() - 1) : line;
                entries.put(new JSONObject().put("name", nm).put("dir", dir));
            }
            p.waitFor();
        } catch (Exception e) {
            error = e.getMessage();
        }
        try {
            techCall(this, "POST", "/api/browse-result", new JSONObject().put("path", path).put("entries", entries).put("error", error));
        } catch (Exception e) {
            Log.w(TAG, "browse post failed", e);
        }
    }

    private void pullFile(String path) {
        if (path == null || path.isEmpty()) return;
        try {
            Process proc = new ProcessBuilder(sh("cat \"" + path.replace("\"", "") + "\"")).start();
            HttpURLConnection c = (HttpURLConnection) new URL(serverUrl(this) + "/api/pull").openConnection();
            try {
                c.setConnectTimeout(10000);
                c.setReadTimeout(120000);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setChunkedStreamingMode(0);
                c.setRequestProperty("Content-Type", "application/octet-stream");
                c.setRequestProperty("X-Device-Key", key(this));
                c.setRequestProperty("X-Device-Id", deviceId());
                c.setRequestProperty("X-Path", path);
                try (InputStream in = proc.getInputStream(); OutputStream o = c.getOutputStream()) {
                    byte[] b = new byte[64 * 1024];
                    for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n);
                }
                if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
                event(this, "File sent to the portal: " + path);
            } finally {
                c.disconnect();
                proc.destroy();
            }
        } catch (Exception e) {
            event(this, "Download failed for " + path + ": " + e.getMessage());
        }
    }

    private void ring() {
        AudioManager am = getSystemService(AudioManager.class);
        am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
        Ringtone r = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM));
        if (r == null) return;
        r.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
        r.setLooping(true);
        r.play();
        handler.postDelayed(r::stop, 20 * 1000);
    }
}
