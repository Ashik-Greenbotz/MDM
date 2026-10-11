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
    private static final String PREF_EVENTS = "events", PREF_CRASH = "crash";
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
        catchCrashes();
        grantLocation();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        else startForeground(1, n);
        startLocation();
        handler.post(new Runnable() {
            @Override
            public void run() {
                net.execute(DeviceService.this::report);
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
            boolean monitor = KioskActivity.monitorOnly(this);
            d.put("type", !monitor ? "android-kiosk" : isTelpo() ? "telpo" : "android");
            d.put("mode", monitor ? "monitor" : "kiosk");
            d.put("os", "Android " + Build.VERSION.RELEASE);
            d.put("deviceName", Settings.Global.getString(getContentResolver(), Settings.Global.DEVICE_NAME));
            d.put("model", Build.MODEL);
            try {
                d.put("serial", Build.getSerial());
            } catch (SecurityException ignored) {
            }
            int battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            if (battery >= 0) d.put("battery", battery); // mains-powered terminals report Integer.MIN_VALUE
            d.put("charging", bm.isCharging());
            d.put("kioskLocked", !monitor && System.currentTimeMillis() >= unlockUntil);
            d.put("uptime", android.os.SystemClock.elapsedRealtime() / 1000);
            d.put("appVersion", getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
            Location loc = lastLocation;
            if (loc != null) {
                d.put("lat", loc.getLatitude());
                d.put("lng", loc.getLongitude());
                d.put("accuracy", loc.getAccuracy());
                d.put("locationTime", loc.getTime());
            }
            JSONObject reply = post(serverUrl(this) + "/api/report", d);
            clearSent(events, crash);
            JSONObject cmd = reply.optJSONObject("command");
            if (cmd != null) handler.post(() -> runCommand(cmd));
        } catch (Exception e) {
            Log.w(TAG, "report failed: " + e);
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

    // RAM / storage used (%), battery temperature and Wi-Fi signal. Android doesn't let apps read CPU load.
    @SuppressWarnings("deprecation")
    private void putHealth(JSONObject d) throws Exception {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        getSystemService(ActivityManager.class).getMemoryInfo(mi);
        d.put("ram", Math.round(100.0 * (mi.totalMem - mi.availMem) / mi.totalMem));
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        d.put("disk", Math.round(100.0 * (fs.getTotalBytes() - fs.getAvailableBytes()) / fs.getTotalBytes()));
        Intent batt = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batt != null && batt.hasExtra(android.os.BatteryManager.EXTRA_TEMPERATURE)) {
            d.put("temp", batt.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0);
        }
        WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
        if (wm != null && wm.getConnectionInfo() != null && wm.getConnectionInfo().getNetworkId() != -1) {
            d.put("signal", wm.getConnectionInfo().getRssi());
            int ip = wm.getConnectionInfo().getIpAddress();
            d.put("localIp", (ip & 255) + "." + (ip >> 8 & 255) + "." + (ip >> 16 & 255) + "." + (ip >> 24 & 255));
        }
        JSONObject info = new JSONObject();
        info.put("Memory", String.format(java.util.Locale.US, "%.1f of %.1f GB free", mi.availMem / 1e9, mi.totalMem / 1e9));
        info.put("Storage", String.format(java.util.Locale.US, "%.1f of %.1f GB free", fs.getAvailableBytes() / 1e9, fs.getTotalBytes() / 1e9));
        info.put("Mode", KioskActivity.monitorOnly(this) ? "Monitor only" : "Kiosk");
        d.put("info", info);
    }

    private JSONObject post(String url, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("X-Device-Key", getString(R.string.server_key));
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
        // Device owner apps may start activities from the background. Opening the kiosk screen locks it.
        if (type.equals("unlock") || type.equals("lock")) startActivity(open);
        handler.postDelayed(() -> net.execute(this::report), 3000);
    }

    // Downloads a portal file to `dest`, checking it against the sha256 the portal sent.
    private File download(JSONObject cmd, File dest) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(serverUrl(this) + cmd.getString("url")).openConnection();
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(60000);
            c.setRequestProperty("X-Device-Key", getString(R.string.server_key));
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
            c.setRequestProperty("X-Device-Key", getString(R.string.server_key));
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

    // "Find my device": alarm sound at full volume for 20 seconds.
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
