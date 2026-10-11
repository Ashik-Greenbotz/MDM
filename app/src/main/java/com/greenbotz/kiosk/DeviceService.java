package com.greenbotz.kiosk;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Checks in with the kiosk server every few seconds: sends status + location, receives unlock/lock commands.
public class DeviceService extends Service {
    private static final String TAG = "DeviceService";
    // ponytail: polling; 10s keeps commands snappy for a few hundred tablets, use a push channel beyond that
    private static final long REPORT_MS = 10 * 1000;
    static final String PREF_SERVER = "server_url";
    private static final String PREF_DEVICE_ID = "device_id";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private Location lastLocation;
    private final LocationListener locationListener = loc -> lastLocation = loc;

    static void start(Context ctx) {
        try {
            ctx.startForegroundService(new Intent(ctx, DeviceService.class));
        } catch (RuntimeException e) {
            Log.w(TAG, "could not start", e);
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
        grantLocation();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
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

    // Device owner can grant its own permissions, so no prompt appears on the tablet.
    private void grantLocation() {
        DevicePolicyManager dpm = getSystemService(DevicePolicyManager.class);
        if (!dpm.isDeviceOwnerApp(getPackageName())) return;
        ComponentName admin = new ComponentName(this, AdminReceiver.class);
        for (String p : new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                Manifest.permission.READ_PHONE_STATE /* Build.getSerial() */}) {
            dpm.setPermissionGrantState(admin, getPackageName(), p, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
        }
        dpm.setLocationEnabled(admin, true);
    }

    private void startLocation() {
        LocationManager lm = getSystemService(LocationManager.class);
        try {
            for (String p : new String[]{LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                if (!lm.hasProvider(p)) continue;
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (lastLocation == null || l.getTime() > lastLocation.getTime())) lastLocation = l;
                lm.requestLocationUpdates(p, 60 * 1000, 0, locationListener, Looper.getMainLooper());
            }
        } catch (SecurityException e) {
            Log.w(TAG, "no location permission (not device owner?)", e);
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
            long unlockUntil = getSharedPreferences("kiosk", MODE_PRIVATE).getLong(KioskActivity.PREF_UNLOCK_UNTIL, 0);
            JSONObject d = new JSONObject();
            d.put("id", deviceId());
            d.put("deviceName", Settings.Global.getString(getContentResolver(), Settings.Global.DEVICE_NAME));
            d.put("model", Build.MODEL);
            try {
                d.put("serial", Build.getSerial());
            } catch (SecurityException ignored) {
            }
            d.put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
            d.put("charging", bm.isCharging());
            d.put("kioskLocked", System.currentTimeMillis() >= unlockUntil);
            d.put("appVersion", getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
            Location loc = lastLocation;
            if (loc != null) {
                d.put("lat", loc.getLatitude());
                d.put("lng", loc.getLongitude());
                d.put("accuracy", loc.getAccuracy());
                d.put("locationTime", loc.getTime());
            }
            JSONObject reply = post(serverUrl(this) + "/api/report", d);
            JSONObject cmd = reply.optJSONObject("command");
            if (cmd != null) handler.post(() -> runCommand(cmd.optString("type")));
        } catch (Exception e) {
            Log.w(TAG, "report failed: " + e);
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
            c.setRequestProperty("X-Device-Key", getString(R.string.server_key));
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } finally {
            c.disconnect();
        }
    }

    private void runCommand(String type) {
        Intent open = new Intent(this, KioskActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (type.equals("unlock")) open.putExtra(KioskActivity.EXTRA_REMOTE_UNLOCK, true);
        // Device owner apps may start activities from the background. Opening the kiosk screen locks it.
        if (type.equals("unlock") || type.equals("lock")) startActivity(open);
        handler.postDelayed(() -> net.execute(this::report), 3000);
    }
}
