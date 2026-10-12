package com.greenbotz.kiosk;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

// First run on a new tablet, before the home screen: 1) server + 4-digit Device ID, 2) allowed apps.
// KioskActivity opens it while needed(); Back does nothing. Saving the apps finishes it and the home screen locks with them.
public class SetupActivity extends Activity {
    static final String PREF_SETUP_DONE = "setup_done";

    // KioskActivity writes false on the very first launch. No value = tablet set up before this screen existed: skip it.
    // (Not keyed on the Device ID: the server may hand a new device one on its first check-in.)
    static boolean needed(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("kiosk", MODE_PRIVATE);
        if (p.contains(PREF_SETUP_DONE)) return !p.getBoolean(PREF_SETUP_DONE, false);
        p.edit().putBoolean(PREF_SETUP_DONE, true).apply();
        return false;
    }

    private float dp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dp = getResources().getDisplayMetrics().density;
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        showLink();
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("kiosk", MODE_PRIVATE);
    }

    // Screen 1: server address (pre-filled) + Device ID created in the portal.
    private void showLink() {
        LinearLayout col = column("Set up this device", "Step 1 of 2 · Create the Device ID in the portal first (Devices → Add device).");
        EditText server = field("Server address", DeviceService.serverUrl(this), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText code = field("4-digit Device ID", prefs().getString(DeviceService.PREF_CODE, ""), InputType.TYPE_CLASS_NUMBER);
        code.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4)});
        col.addView(label("Server address"));
        col.addView(server);
        col.addView(label("Device ID"));
        col.addView(code);
        TextView msg = new TextView(this);
        msg.setTextSize(16);
        msg.setPadding(0, px(12), 0, 0);
        col.addView(msg);
        Button connect = button("Connect");
        col.addView(connect, buttonParams());
        connect.setOnClickListener(v -> {
            String u = server.getText().toString().trim().replaceAll("/+$", "");
            String c = code.getText().toString().trim();
            if (!u.matches("https?://.+")) {
                error(msg, "Server address must start with http:// or https://");
                return;
            }
            if (!c.matches("\\d{4}")) {
                error(msg, "The Device ID is 4 digits");
                return;
            }
            prefs().edit().putString(DeviceService.PREF_SERVER, u).commit();
            connect.setEnabled(false);
            msg.setTextColor(0xFF6B7280);
            msg.setText("Connecting…");
            new Thread(() -> {
                String err = link(c);
                runOnUiThread(() -> {
                    connect.setEnabled(true);
                    if (err == null) showApps(); else error(msg, err);
                });
            }).start();
        });
        setContentView(scroll(col));
    }

    // null = linked. A brand-new device isn't known to the server until its first check-in, so retry that case a few times.
    private String link(String code) {
        String err = "Failed";
        for (int i = 0; i < 6; i++) {
            try {
                JSONObject r = DeviceService.link(this, code);
                if (r.optBoolean("ok")) return null;
                err = r.optString("error", "Failed");
                if (!err.contains("checked in")) return err;
            } catch (Exception e) {
                return "Can't reach the server: " + e.getMessage();
            }
            DeviceService.reportNow();
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                return err;
            }
        }
        return err;
    }

    // Screen 2: allowed apps, same list as the admin menu's "Allowed apps".
    private void showApps() {
        LinearLayout col = column("Choose allowed apps", "Step 2 of 2 · Linked as Device ID " + prefs().getString(DeviceService.PREF_CODE, "")
                + ". Tick the apps this tablet may open.");
        PackageManager pm = getPackageManager();
        TreeMap<String, String> byLabel = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            if (!ri.activityInfo.packageName.equals(getPackageName())) byLabel.put(ri.loadLabel(pm).toString(), ri.activityInfo.packageName);
        }
        TreeSet<String> chosen = new TreeSet<>(prefs().getStringSet(KioskActivity.PREF_APPS, new TreeSet<>()));
        for (Map.Entry<String, String> e : byLabel.entrySet()) {
            CheckBox cb = new CheckBox(this);
            cb.setText(e.getKey());
            cb.setTextSize(18);
            cb.setPadding(px(8), px(10), 0, px(10));
            cb.setChecked(chosen.contains(e.getValue()));
            cb.setOnCheckedChangeListener((b, on) -> {
                if (on) chosen.add(e.getValue()); else chosen.remove(e.getValue());
            });
            col.addView(cb);
        }
        Button save = button("Save and finish");
        save.setOnClickListener(v -> {
            prefs().edit().putStringSet(KioskActivity.PREF_APPS, chosen).putBoolean(PREF_SETUP_DONE, true).commit();
            DeviceService.event(this, "Setup finished on the device");
            startActivity(new Intent(this, KioskActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish(); // the home screen's onResume locks with the new apps
        });
        col.addView(save, buttonParams());
        setContentView(scroll(col));
    }

    private LinearLayout column(String title, String sub) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(32), px(32), px(32), px(32));
        // Company logo, same image as the home screen.
        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageResource(R.drawable.wallpaper);
        logo.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(140));
        lp.bottomMargin = px(24);
        col.addView(logo, lp);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(0xFF1D2129);
        t.setTextSize(28);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        col.addView(t);
        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextColor(0xFF6B7280);
        s.setTextSize(16);
        s.setPadding(0, px(8), 0, px(16));
        col.addView(s);
        return col;
    }

    private TextView label(String text) {
        TextView l = new TextView(this);
        l.setText(text);
        l.setTextColor(0xFF1D2129);
        l.setTextSize(14);
        l.setPadding(0, px(12), 0, 0);
        return l;
    }

    private EditText field(String hint, String value, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setInputType(type);
        e.setTextSize(20);
        e.setSingleLine(true);
        return e;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(20);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, px(64));
        p.topMargin = px(20);
        return p;
    }

    private ScrollView scroll(View content) {
        ScrollView s = new ScrollView(this);
        s.setBackgroundColor(0xFFFFFFFF);
        s.addView(content);
        return s;
    }

    private void error(TextView msg, String text) {
        msg.setTextColor(0xFFD93025);
        msg.setText(text);
    }

    private int px(int v) {
        return Math.round(v * dp);
    }

    @Override
    public void onBackPressed() {
        // Setup must be finished.
    }
}
