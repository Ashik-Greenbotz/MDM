package com.greenbotz.kiosk;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.WallpaperManager;
import android.app.admin.DevicePolicyManager;
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
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
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
    private static final String PREF_APPS = "apps";
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
        // Mode is picked once, on the first launch by setup-tablet.bat; after that only the admin menu changes it.
        if (!prefs().contains(PREF_MODE)) {
            prefs().edit().putString(PREF_MODE, "monitor".equals(getIntent().getStringExtra("mode")) ? "monitor" : "kiosk").commit(); // commit: must survive an early crash
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
        WindowInsetsController c = getWindow().getInsetsController();
        c.hide(WindowInsets.Type.systemBars());
        c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
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
        showOnline(status, false);
        status.setOnClickListener(v -> {
            if (!online) showWifiPicker();
        });
        getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(
                new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) {
                        // VALIDATED = Android actually reached the internet, not just joined Wi-Fi.
                        boolean ok = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                        runOnUiThread(() -> showOnline(status, ok));
                    }

                    @Override
                    public void onLost(Network n) {
                        runOnUiThread(() -> showOnline(status, false));
                    }
                });
    }

    private static GradientDrawable rounded(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private void showOnline(TextView status, boolean online) {
        this.online = online;
        status.setText(online ? "●  Online" : "●  Offline · tap to connect Wi-Fi");
        status.setTextColor(online ? 0xFF1E8E3E : 0xFFD93025);
        status.setBackground(rounded(online ? 0xFFE6F4EA : 0xFFFCE8E6, 999));
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
        dpm.setLocationEnabled(admin, true);
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
        if (password == null) {
            c.setSecurityParams(WifiConfiguration.SECURITY_TYPE_OPEN);
        } else {
            boolean wpa3Only = caps.contains("SAE") && !caps.contains("PSK");
            c.setSecurityParams(wpa3Only ? WifiConfiguration.SECURITY_TYPE_SAE : WifiConfiguration.SECURITY_TYPE_PSK);
            c.preSharedKey = "\"" + password + "\"";
        }
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
        return new TreeSet<>(prefs().getStringSet(PREF_APPS, Collections.emptySet()));
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
            dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_HOME
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
        startLockTask();
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
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAdminMenu() {
        String[] items = {"Exit kiosk", "Change password", "Uninstall kiosk app", "Choose allowed apps", "Server address",
                monitorOnly(this) ? "Switch to kiosk mode" : "Switch to monitor-only (no lock)"};
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
                .setNegativeButton("Cancel", null)
                .show();
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
