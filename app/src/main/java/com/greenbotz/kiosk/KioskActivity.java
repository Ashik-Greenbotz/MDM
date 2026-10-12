package com.greenbotz.kiosk;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.WallpaperManager;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public class KioskActivity extends Activity {
    // Used until changed from the admin menu.
    private static final String DEFAULT_PASSWORD = "1234";
    private static final String PREF_HASH = "pw_hash";
    static final String PREF_APPS = "apps";
    static final String PREF_UNLOCK_UNTIL = "unlock_until";
    private static final String PREF_WALLPAPER_SET = "wallpaper_set";
    private static final long UNLOCK_MS = 2 * 60 * 1000;
    private static final int TAPS_NEEDED = 6;
    private static final long TAP_WINDOW_MS = 3000;

    private DevicePolicyManager dpm;
    private ComponentName admin;
    private int taps;
    private long firstTapAt;
    private boolean online;
    private boolean remoteUnlockPending;
    static final String EXTRA_REMOTE_UNLOCK = "remote_unlock";
    // "kiosk" locks the device to this app; "monitor" only reports to the dashboard (phones, Telpo terminals).
    static final String PREF_MODE = "mode";
    // App pinning: package that runs full screen; the kiosk screen only shows briefly to reopen it.
    static final String PREF_SINGLE_APP = "single_app";
    // Manual override while an app is pinned: 6 taps bottom-left + this PIN. Checked on the device, so it works offline.
    // ponytail: one fixed PIN for every device; make it per-device / settable from the portal if it leaks.
    private static final String OVERRIDE_PIN = "9499";
    static final String EXTRA_OVERRIDE = "override";
    private boolean overridePending;

    static boolean isPinned(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("kiosk", MODE_PRIVATE);
        return !monitorOnly(ctx) && !p.getString(PREF_SINGLE_APP, "").isEmpty()
                && System.currentTimeMillis() >= p.getLong(PREF_UNLOCK_UNTIL, 0);
    }
    private static final long SINGLE_APP_DELAY_MS = 5000;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable openSingleApp = this::openSingleApp;
    // Device ID arrives from the server in the background; show it as soon as it's saved.
    private final SharedPreferences.OnSharedPreferenceChangeListener codeListener = (p, key) -> {
        if (DeviceService.PREF_CODE.equals(key)) runOnUiThread(this::showDeviceCode);
        if (DeviceService.PREF_SERVER_OK.equals(key)) runOnUiThread(this::showStatus);
        if (PREF_APPS.equals(key)) runOnUiThread(this::renderApps);
    };

    // Battery % above the Device ID; ACTION_BATTERY_CHANGED is sticky, so registering also delivers the current level.
    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            int level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
            boolean charging = i.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0;
            if (level < 0) return;
            int pct = level * 100 / scale;
            int color = pct < 20 ? 0xFFD93025 : pct < 40 ? 0xFFF29900 : 0xFF1E8E3E;
            TextView t = findViewById(R.id.battery);
            t.setText(pct + "%" + (charging ? "  charging" : ""));
            t.setTextColor(color);
            t.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(color));
        }
    };

    static boolean monitorOnly(Context ctx) {
        return "monitor".equals(ctx.getSharedPreferences("kiosk", MODE_PRIVATE).getString(PREF_MODE, "kiosk"));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        dpm = getSystemService(DevicePolicyManager.class);
        admin = new ComponentName(this, AdminReceiver.class);
        findViewById(R.id.corner).setOnClickListener(v -> onCornerTap());
        findViewById(R.id.overrideCorner).setOnClickListener(v -> {
            handler.removeCallbacks(openSingleApp);
            if (isPinned(this) && PinOverlay.tap()) askOverridePin();
        });
        overridePending = getIntent().getBooleanExtra(EXTRA_OVERRIDE, false);
        // Mode is picked once, on the first launch by setup-tablet.bat; after that only the admin menu changes it.
        if (!prefs().contains(PREF_MODE)) {
            prefs().edit().putString(PREF_MODE, "monitor".equals(getIntent().getStringExtra("mode")) ? "monitor" : "kiosk")
                    .putBoolean(SetupActivity.PREF_SETUP_DONE, false).commit(); // commit: must survive an early crash
        }
        setSystemWallpaperOnce();
        watchInternet();
        DeviceService.start(this);
        remoteUnlockPending = getIntent().getBooleanExtra(EXTRA_REMOTE_UNLOCK, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderApps();
        showDeviceCode();
        prefs().registerOnSharedPreferenceChangeListener(codeListener);
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        showStatus();
        if (monitorOnly(this)) {
            unlock();
            return;
        }
        if (remoteUnlockPending) {
            remoteUnlockPending = false;
            exitKiosk();
            return;
        }
        // Opening this screen always locks, ending any "Exit kiosk" time early.
        prefs().edit().remove(PREF_UNLOCK_UNTIL).apply();
        lock();
        hideSystemBars();
        if (MaintenanceActivity.on(this)) {
            startActivity(new Intent(this, MaintenanceActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        if (SetupActivity.needed(this)) {
            startActivity(new Intent(this, SetupActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        if (overridePending) {
            overridePending = false;
            handler.removeCallbacks(openSingleApp);
            askOverridePin();
        } else {
            scheduleSingleApp();
        }
        PinOverlay.sync(this);
    }

    private void askOverridePin() {
        EditText pin = new EditText(this);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin.setHint("Override PIN");
        new AlertDialog.Builder(this)
                .setTitle("Manual override")
                .setMessage("Unpins " + appLabel(prefs().getString(PREF_SINGLE_APP, "")) + " and opens the admin menu.")
                .setView(pin)
                .setPositiveButton("OK", (d, w) -> {
                    if (OVERRIDE_PIN.equals(pin.getText().toString())) {
                        setSingleApp(this, "");
                        DeviceService.event(this, "Manual override used on the device");
                        // Put the allowed apps (and Home button) back; setSingleApp cleared them from lock task.
                        lock();
                        renderApps();
                        PinOverlay.sync(this);
                        scheduleSingleApp();
                        showAdminMenu();
                    } else {
                        Toast.makeText(this, "Wrong PIN", Toast.LENGTH_SHORT).show();
                        scheduleSingleApp();
                    }
                })
                .setNegativeButton("Cancel", (d, w) -> scheduleSingleApp())
                .show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(openSingleApp);
        prefs().unregisterOnSharedPreferenceChangeListener(codeListener);
        unregisterReceiver(batteryReceiver);
    }

    private void showDeviceCode() {
        String code = prefs().getString(DeviceService.PREF_CODE, "");
        ((TextView) findViewById(R.id.deviceCode)).setText(code.isEmpty() ? "" : "Device ID  " + code);
    }

    private Intent singleAppIntent() {
        String pkg = prefs().getString(PREF_SINGLE_APP, "");
        return pkg.isEmpty() ? null : getPackageManager().getLaunchIntentForPackage(pkg);
    }

    // Shows "Opening X in 5 s" and then opens it. The delay is the admin's way in: 6 corner taps cancel it.
    private void scheduleSingleApp() {
        TextView banner = findViewById(R.id.single);
        Intent launch = singleAppIntent();
        handler.removeCallbacks(openSingleApp);
        if (launch == null) {
            banner.setVisibility(View.GONE);
            return;
        }
        banner.setText("\uD83D\uDCCC Opening " + appLabel(prefs().getString(PREF_SINGLE_APP, "")) + "…");
        banner.setVisibility(View.VISIBLE);
        handler.postDelayed(openSingleApp, SINGLE_APP_DELAY_MS);
    }

    // The pinned app becomes the lock task itself (not a task on top of this screen), so Back on it does nothing.
    private void openSingleApp() {
        Intent launch = singleAppIntent();
        if (launch == null) return;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!isOwner() || !isPinned(this)) {
            startActivity(launch);
            return;
        }
        try {
            stopLockTask();
        } catch (RuntimeException ignored) {
        }
        startActivity(launch, ActivityOptions.makeBasic().setLockTaskEnabled(true).toBundle());
    }

    private String appLabel(String pkg) {
        try {
            return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }

    @Override
    public void onBackPressed() {
        // swallow back
    }

    // Same logo as the normal Samsung home/lock screen wallpaper, used while unlocked.
    private void setSystemWallpaperOnce() {
        if (prefs().getBoolean(PREF_WALLPAPER_SET, false)) return;
        try (InputStream in = getResources().openRawResource(R.drawable.wallpaper)) {
            WallpaperManager.getInstance(this).setStream(in);
            prefs().edit().putBoolean(PREF_WALLPAPER_SET, true).apply();
        } catch (IOException ignored) {
        }
    }

    private void watchInternet() {
        TextView status = findViewById(R.id.status);
        showStatus();
        status.setOnClickListener(v -> {
            if (!online) showWifiPicker();
            else {
                showServerAddress = !showServerAddress;
                showStatus();
            }
        });
        getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(
                new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) {
                        // VALIDATED = Android actually reached the internet, not just joined Wi-Fi.
                        boolean ok = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                        int icon = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? R.drawable.ic_wifi
                                : caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? R.drawable.ic_ethernet : 0;
                        runOnUiThread(() -> {
                            online = ok;
                            netIcon = icon;
                            showStatus();
                        });
                    }

                    @Override
                    public void onLost(Network n) {
                        runOnUiThread(() -> {
                            online = false;
                            showStatus();
                        });
                    }
                });
    }

    private static GradientDrawable rounded(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    // One pill. Green "Online": internet + portal server reached. Yellow: internet but the server isn't answering.
    // Red: no internet, tap to pick Wi-Fi. Online, a tap shows/hides the server address.
    private int netIcon;
    private boolean showServerAddress;

    private void showStatus() {
        TextView status = findViewById(R.id.status);
        boolean server = prefs().getBoolean(DeviceService.PREF_SERVER_OK, false);
        int icon = online ? netIcon : 0;
        int color = !online ? 0xFFD93025 : server ? 0xFF1E8E3E : 0xFFB06000;
        String text = !online ? "Offline · tap to connect Wi-Fi" : server ? "Online" : "Online · server offline";
        if (online && showServerAddress) {
            Uri u = Uri.parse(DeviceService.serverUrl(this));
            text += " · " + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
        }
        status.setText(icon != 0 ? text : "●  " + text);
        status.setTextColor(color);
        status.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
        status.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(color));
        status.setBackground(rounded(!online ? 0xFFFCE8E6 : server ? 0xFFE6F4EA : 0xFFFEF3C7, 999));
    }

    // Own Wi-Fi list instead of the Settings panel, which links on to the rest of Settings.
    // addNetwork/setWifiEnabled are only allowed for device owner apps on Android 10+.
    private void showWifiPicker() {
        if (!isOwner()) {
            Toast.makeText(this, "Wi-Fi setup needs device owner", Toast.LENGTH_LONG).show();
            return;
        }
        // Scan results need location permission and location turned on.
        for (String p : new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES}) {
            dpm.setPermissionGrantState(admin, getPackageName(), p, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
        }
        DeviceService.enableLocation(dpm, admin);
        WifiManager wm = getSystemService(WifiManager.class);
        if (!wm.isWifiEnabled()) wm.setWifiEnabled(true);
        wm.startScan();
        Toast.makeText(this, "Searching for Wi-Fi...", Toast.LENGTH_SHORT).show();
        // ponytail: fixed 3s wait for scan results; listen for SCAN_RESULTS_AVAILABLE_ACTION if lists come up empty
        new Handler(Looper.getMainLooper()).postDelayed(() -> listWifi(wm), 3000);
    }

    private void listWifi(WifiManager wm) {
        if (isFinishing()) return;
        TreeMap<String, ScanResult> bySsid = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (ScanResult r : wm.getScanResults()) {
            String ssid = r.SSID == null ? "" : r.SSID.replace("\"", ""); // getWifiSsid() is Android 13+
            if (ssid.isEmpty() || ssid.equals("<unknown ssid>")) continue;
            ScanResult seen = bySsid.get(ssid);
            if (seen == null || r.level > seen.level) bySsid.put(ssid, r);
        }
        String[] names = bySsid.keySet().toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle(names.length == 0 ? "No Wi-Fi found" : "Choose Wi-Fi")
                .setItems(names, (d, i) -> askWifiPassword(wm, names[i], bySsid.get(names[i]).capabilities))
                .setNeutralButton("Refresh", (d, w) -> showWifiPicker())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void askWifiPassword(WifiManager wm, String ssid, String caps) {
        if (caps.contains("EAP")) {
            Toast.makeText(this, "Enterprise Wi-Fi is not supported", Toast.LENGTH_LONG).show();
            return;
        }
        boolean open = !caps.contains("WPA") && !caps.contains("WEP") && !caps.contains("SAE") && !caps.contains("PSK");
        if (open) {
            connectWifi(wm, ssid, caps, null);
            return;
        }
        EditText pw = passwordField();
        pw.setHint("Wi-Fi password");
        new AlertDialog.Builder(this)
                .setTitle(ssid)
                .setView(pw)
                .setPositiveButton("Connect", (d, w) -> connectWifi(wm, ssid, caps, pw.getText().toString()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    @SuppressWarnings("deprecation")
    private void connectWifi(WifiManager wm, String ssid, String caps, String password) {
        WifiConfiguration c = new WifiConfiguration();
        c.SSID = "\"" + ssid + "\"";
        boolean wpa3Only = caps.contains("SAE") && !caps.contains("PSK");
        if (Build.VERSION.SDK_INT >= 30) {
            c.setSecurityParams(password == null ? WifiConfiguration.SECURITY_TYPE_OPEN
                    : wpa3Only ? WifiConfiguration.SECURITY_TYPE_SAE : WifiConfiguration.SECURITY_TYPE_PSK);
        } else {
            // Android 9/10: WPA3-only networks are not supported here.
            c.allowedKeyManagement.set(password == null ? WifiConfiguration.KeyMgmt.NONE : WifiConfiguration.KeyMgmt.WPA_PSK);
        }
        if (password != null) c.preSharedKey = "\"" + password + "\"";
        int id = wm.addNetwork(c);
        if (id == -1 || !wm.enableNetwork(id, true)) {
            Toast.makeText(this, "Could not connect to " + ssid, Toast.LENGTH_LONG).show();
            return;
        }
        // Status flips to Online by itself once the connection works; a wrong password just stays Offline.
        Toast.makeText(this, "Connecting to " + ssid + "...", Toast.LENGTH_LONG).show();
    }

    private boolean isOwner() {
        return dpm.isDeviceOwnerApp(getPackageName());
    }

    private Set<String> allowedApps() {
        Set<String> apps = new TreeSet<>(prefs().getStringSet(PREF_APPS, Collections.emptySet()));
        String single = prefs().getString(PREF_SINGLE_APP, "");
        if (!single.isEmpty()) apps.add(single);
        return apps;
    }

    private void renderApps() {
        GridLayout grid = findViewById(R.id.apps);
        grid.removeAllViews();
        PackageManager pm = getPackageManager();
        float dp = getResources().getDisplayMetrics().density;
        for (String pkg : allowedApps()) {
            Intent launch = pm.getLaunchIntentForPackage(pkg);
            if (launch == null) continue; // uninstalled since it was picked
            try {
                ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                Drawable icon = pm.getApplicationIcon(info);
                icon.setBounds(0, 0, (int) (64 * dp), (int) (64 * dp));
                TextView tile = new TextView(this);
                tile.setText(pm.getApplicationLabel(info));
                tile.setCompoundDrawables(null, icon, null, null);
                tile.setCompoundDrawablePadding((int) (10 * dp));
                tile.setGravity(Gravity.CENTER);
                tile.setTextColor(0xFF1D2129);
                tile.setTextSize(14);
                tile.setMaxLines(2);
                tile.setEllipsize(TextUtils.TruncateAt.END);
                tile.setPadding((int) (8 * dp), (int) (18 * dp), (int) (8 * dp), (int) (14 * dp));
                tile.setBackground(rounded(0xFFFFFFFF, 20 * dp));
                tile.setElevation(2 * dp);
                tile.setOnClickListener(v -> startActivity(launch));
                // Equal-width columns: width 0 + weight 1.
                GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                        GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
                lp.width = 0;
                int m = (int) (6 * dp);
                lp.setMargins(m, m, m, m);
                grid.addView(tile, lp);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        if (grid.getChildCount() == 0) {
            TextView empty = new TextView(this);
            empty.setText("No apps available");
            empty.setTextColor(0xFF6B7280);
            empty.setPadding(0, (int) (24 * dp), 0, (int) (24 * dp));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(0, 4, GridLayout.CENTER));
            grid.addView(empty, lp);
        }
    }

    private void chooseApps() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        TreeMap<String, String> byLabel = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (!pkg.equals(getPackageName())) byLabel.put(ri.loadLabel(pm).toString(), pkg);
        }
        String[] labels = byLabel.keySet().toArray(new String[0]);
        String[] pkgs = byLabel.values().toArray(new String[0]);
        Set<String> chosen = allowedApps();
        boolean[] checked = new boolean[pkgs.length];
        for (int i = 0; i < pkgs.length; i++) checked[i] = chosen.contains(pkgs[i]);
        new AlertDialog.Builder(this)
                .setTitle("Allowed apps")
                .setMultiChoiceItems(labels, checked, (d, i, on) -> {
                    if (on) chosen.add(pkgs[i]); else chosen.remove(pkgs[i]);
                })
                .setPositiveButton("Save", (d, w) -> {
                    prefs().edit().putStringSet(PREF_APPS, chosen).apply();
                    lock();
                    renderApps();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void lock() {
        if (isOwner()) {
            Set<String> pkgs = allowedApps();
            pkgs.add(getPackageName());
            dpm.setLockTaskPackages(admin, pkgs.toArray(new String[0]));
            // Home button returns to this screen; recents and notifications stay blocked.
            // App pinned: no Home button at all (way out is the bottom-left override or Unpin from the portal).
            dpm.setLockTaskFeatures(admin, (isPinned(this) ? 0 : DevicePolicyManager.LOCK_TASK_FEATURE_HOME)
                    | DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS);
            setHomeAlias(true);
            IntentFilter home = new IntentFilter(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addCategory(Intent.CATEGORY_DEFAULT);
            dpm.addPersistentPreferredActivity(admin, home, homeAlias());
            dpm.setKeyguardDisabled(admin, true);
            dpm.setStatusBarDisabled(admin, true);
        }
        // Without device owner this falls back to screen pinning (user confirms, can escape).
        try {
            startLockTask();
        } catch (IllegalArgumentException e) {
            // "Invalid task, not in foreground": another app came up first; onResume locks next time.
            Log.w("Kiosk", "startLockTask", e);
        }
    }

    private void unlock() {
        if (isOwner()) {
            dpm.clearPackagePersistentPreferredActivities(admin, getPackageName());
            // Otherwise lockTaskMode="if_whitelisted" re-locks the next time the app opens.
            dpm.setLockTaskPackages(admin, new String[0]);
            dpm.setKeyguardDisabled(admin, false);
            dpm.setStatusBarDisabled(admin, false);
        }
        stopLockTask();
        // Stop being the home screen so the tablet goes back to its normal launcher.
        setHomeAlias(false);
    }

    private ComponentName homeAlias() {
        return new ComponentName(this, getPackageName() + ".HomeAlias");
    }

    private void setHomeAlias(boolean on) {
        getPackageManager().setComponentEnabledSetting(homeAlias(),
                on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }

    private void onCornerTap() {
        handler.removeCallbacks(openSingleApp); // admin is trying to get in: stay on this screen
        long now = SystemClock.elapsedRealtime();
        if (now - firstTapAt > TAP_WINDOW_MS) {
            firstTapAt = now;
            taps = 0;
        }
        if (++taps >= TAPS_NEEDED) {
            taps = 0;
            askPassword();
        }
    }

    private EditText passwordField() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return input;
    }

    private void askPassword() {
        EditText input = passwordField();
        new AlertDialog.Builder(this)
                .setTitle("Admin password")
                .setView(input)
                .setPositiveButton("OK", (d, w) -> {
                    if (hash(input.getText().toString()).equals(storedHash())) {
                        showAdminMenu();
                    } else {
                        Toast.makeText(this, "Wrong password", Toast.LENGTH_SHORT).show();
                        scheduleSingleApp();
                    }
                })
                .setNegativeButton("Cancel", (d, w) -> scheduleSingleApp())
                .show();
    }

    private void showAdminMenu() {
        String[] items = {"Exit kiosk", "Change password", "Uninstall kiosk app", "Choose allowed apps", "Server address",
                monitorOnly(this) ? "Switch to kiosk mode" : "Switch to monitor-only (no lock)",
                prefs().getString(PREF_SINGLE_APP, "").isEmpty() ? "App pinning…"
                        : "App pinning… (" + appLabel(prefs().getString(PREF_SINGLE_APP, "")) + ")",
                "Link device ID"};
        new AlertDialog.Builder(this)
                .setTitle("Admin")
                .setItems(items, (d, which) -> {
                    if (which == 1) {
                        changePassword();
                        return;
                    }
                    if (which == 3) {
                        chooseApps();
                        return;
                    }
                    if (which == 4) {
                        changeServer();
                        return;
                    }
                    if (which == 7) {
                        linkDeviceId();
                        return;
                    }
                    if (which == 6) {
                        chooseSingleApp();
                        return;
                    }
                    if (which == 5) {
                        prefs().edit().putString(PREF_MODE, monitorOnly(this) ? "kiosk" : "monitor").apply();
                        recreate();
                        return;
                    }
                    if (which == 2) {
                        unlock();
                        // Give the tablet back as it was: drop device owner, then ask Android to uninstall us.
                        if (isOwner()) dpm.clearDeviceOwnerApp(getPackageName());
                        startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + getPackageName())));
                        finish();
                        return;
                    }
                    exitKiosk();
                })
                .setNegativeButton("Cancel", (d, w) -> scheduleSingleApp())
                .show();
    }

    private void chooseSingleApp() {
        PackageManager pm = getPackageManager();
        TreeMap<String, String> byLabel = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            String pkg = ri.activityInfo.packageName;
            if (!pkg.equals(getPackageName())) byLabel.put(ri.loadLabel(pm).toString(), pkg);
        }
        java.util.List<String> labels = new java.util.ArrayList<>(byLabel.keySet()), pkgs = new java.util.ArrayList<>(byLabel.values());
        int current = pkgs.indexOf(prefs().getString(PREF_SINGLE_APP, ""));
        int[] chosen = {current};
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("App pinning")
                .setSingleChoiceItems(labels.toArray(new String[0]), current, (d, i) -> chosen[0] = i)
                .setPositiveButton("Pin app", (d, w) -> {
                    if (chosen[0] < 0) {
                        Toast.makeText(this, "Pick an app first", Toast.LENGTH_SHORT).show();
                        scheduleSingleApp();
                        return;
                    }
                    setSingleApp(this, pkgs.get(chosen[0]));
                    if (monitorOnly(this)) prefs().edit().putString(PREF_MODE, "kiosk").apply();
                    recreate();
                })
                .setNegativeButton("Cancel", (d, w) -> scheduleSingleApp());
        if (current >= 0) {
            b.setNeutralButton("Unpin app", (d, w) -> {
                setSingleApp(this, "");
                Toast.makeText(this, "App unpinned", Toast.LENGTH_SHORT).show();
                recreate();
            });
        }
        b.show();
    }

    private void linkDeviceId() {
        EditText code = new EditText(this);
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        code.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4)});
        code.setHint("4-digit ID from the portal");
        code.setText(prefs().getString(DeviceService.PREF_CODE, ""));
        new AlertDialog.Builder(this)
                .setTitle("Link device ID")
                .setMessage("Create the ID in the portal (Devices → Add device), then type it here.")
                .setView(code)
                .setPositiveButton("Link", (d, w) -> {
                    String c = code.getText().toString().trim();
                    new Thread(() -> {
                        String msg;
                        try {
                            org.json.JSONObject r = DeviceService.link(this, c);
                            msg = r.optBoolean("ok") ? "Linked as " + r.optString("name") + " (ID " + r.optString("code") + ")" : r.optString("error", "Failed");
                        } catch (Exception e) {
                            msg = "Can't reach the server: " + e.getMessage();
                        }
                        String m = msg;
                        runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("Link device ID").setMessage(m)
                                .setPositiveButton("OK", (x, y) -> scheduleSingleApp()).show());
                    }).start();
                })
                .setNegativeButton("Cancel", (d, w) -> scheduleSingleApp())
                .show();
    }

    // Also used by the portal's "Pin app" / "Unpin app" commands.
    static void setSingleApp(Context ctx, String pkg) {
        String old = ctx.getSharedPreferences("kiosk", MODE_PRIVATE).getString(PREF_SINGLE_APP, "");
        ctx.getSharedPreferences("kiosk", MODE_PRIVATE).edit().putString(PREF_SINGLE_APP, pkg == null ? "" : pkg).commit();
        // Unpinning: drop the old app out of lock task now; lock() puts the allowed-apps list back when this screen resumes.
        DevicePolicyManager dpm = ctx.getSystemService(DevicePolicyManager.class);
        if (!old.isEmpty() && !old.equals(pkg) && dpm.isDeviceOwnerApp(ctx.getPackageName())) {
            dpm.setLockTaskPackages(new ComponentName(ctx, AdminReceiver.class), new String[]{ctx.getPackageName()});
        }
        DeviceService.event(ctx, pkg == null || pkg.isEmpty() ? "App unpinned" : "App pinned: " + pkg);
    }

    // Shared by the admin menu and the dashboard's remote "Unlock" command.
    private void exitKiosk() {
        unlock();
        long until = System.currentTimeMillis() + UNLOCK_MS;
        prefs().edit().putLong(PREF_UNLOCK_UNTIL, until).apply();
        scheduleRelock(this, until);
        Toast.makeText(this, "Unlocked for 2 minutes", Toast.LENGTH_LONG).show();
        startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // onResume runs after this and would lock again, so skip it once.
        if (intent.getBooleanExtra(EXTRA_REMOTE_UNLOCK, false)) {
            remoteUnlockPending = true;
        }
        if (intent.getBooleanExtra(EXTRA_OVERRIDE, false)) overridePending = true;
    }

    private void changeServer() {
        EditText url = new EditText(this);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(DeviceService.serverUrl(this));
        new AlertDialog.Builder(this)
                .setTitle("Server address")
                .setView(url)
                .setPositiveButton("Save", (d, w) -> {
                    String u = url.getText().toString().trim().replaceAll("/+$", "");
                    if (!u.startsWith("http://") && !u.startsWith("https://")) {
                        Toast.makeText(this, "Must start with http:// or https://", Toast.LENGTH_LONG).show();
                        return;
                    }
                    prefs().edit().putString(DeviceService.PREF_SERVER, u).apply();
                    Toast.makeText(this, "Server saved", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void changePassword() {
        EditText old = passwordField();
        old.setHint("Old password");
        EditText first = passwordField();
        first.setHint("New password");
        EditText second = passwordField();
        second.setHint("Confirm password");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(old);
        box.addView(first);
        box.addView(second);
        new AlertDialog.Builder(this)
                .setTitle("Change password")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    if (!hash(old.getText().toString()).equals(storedHash())) {
                        Toast.makeText(this, "Old password is wrong", Toast.LENGTH_LONG).show();
                        return;
                    }
                    String pw = first.getText().toString();
                    if (pw.length() < 4 || !pw.equals(second.getText().toString())) {
                        Toast.makeText(this, "Passwords must match and be at least 4 characters", Toast.LENGTH_LONG).show();
                        return;
                    }
                    prefs().edit().putString(PREF_HASH, hash(pw)).apply();
                    Toast.makeText(this, "Password changed", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("kiosk", MODE_PRIVATE);
    }

    // Reopens this screen at `until`; onResume then sees the time is up and locks.
    // Device owner apps are allowed to start activities from the background.
    static void scheduleRelock(Context ctx, long until) {
        Intent open = new Intent(ctx, KioskActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // Android 14+: the creator must opt in for the alarm to start an activity from the background.
        Bundle opts = Build.VERSION.SDK_INT < 34 ? null : ActivityOptions.makeBasic().setPendingIntentCreatorBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle();
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_IMMUTABLE, opts);
        ctx.getSystemService(AlarmManager.class).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, until, pi);
    }

    private String storedHash() {
        return prefs().getString(PREF_HASH, hash(DEFAULT_PASSWORD));
    }

    // ponytail: unsalted SHA-256, fine for a local kiosk PIN; use PBKDF2 if the device can be rooted/extracted
    static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
