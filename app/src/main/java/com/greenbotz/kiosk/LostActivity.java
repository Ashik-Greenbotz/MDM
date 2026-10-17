package com.greenbotz.kiosk;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

// Full-screen "lost device" notice, pushed from the portal. Shows a message and a phone number so whoever finds
// the tablet can return it. It sits on top of everything and Back does nothing; it closes when the portal clears it.
public class LostActivity extends Activity {
    static boolean on(Context ctx) {
        return !ctx.getSharedPreferences("kiosk", MODE_PRIVATE).getString(DeviceService.PREF_LOST, "").isEmpty();
    }

    private final SharedPreferences.OnSharedPreferenceChangeListener listener = (p, key) -> {
        if (DeviceService.PREF_LOST.equals(key)) runOnUiThread(() -> { if (on(this)) show(); else leave(); });
    };
    private TextView msg, phone;

    private void show() {
        String json = getSharedPreferences("kiosk", MODE_PRIVATE).getString(DeviceService.PREF_LOST, "");
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            msg.setText(o.optString("message", getString(R.string.lost_default)));
            String ph = o.optString("phone", "");
            phone.setText(ph.isEmpty() ? "" : "📞  " + ph);
            phone.setVisibility(ph.isEmpty() ? View.GONE : View.VISIBLE);
        } catch (org.json.JSONException e) {
            msg.setText(getString(R.string.lost_default));
        }
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(KioskActivity.themed(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFFB3261E); // red
        int pad = Math.round(28 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(getString(R.string.lost_title));
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(34);
        title.setGravity(Gravity.CENTER);

        msg = new TextView(this);
        msg.setTextColor(0xFFFFFFFF);
        msg.setTextSize(20);
        msg.setGravity(Gravity.CENTER);
        msg.setPadding(0, pad, 0, pad);

        phone = new TextView(this);
        phone.setTextColor(0xFFFFFFFF);
        phone.setTextSize(26);
        phone.setGravity(Gravity.CENTER);

        root.addView(title);
        root.addView(msg);
        root.addView(phone);
        setContentView(root);
        show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
        getSharedPreferences("kiosk", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(listener);
        if (!on(this)) leave();
    }

    @Override
    protected void onPause() {
        super.onPause();
        getSharedPreferences("kiosk", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(listener);
    }

    private void leave() {
        startActivity(new Intent(this, KioskActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }

    @Override
    public void onBackPressed() {
        // Trapped on purpose.
    }
}
