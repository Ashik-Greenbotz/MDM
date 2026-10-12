package com.greenbotz.kiosk;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

// Full-screen announcement from the portal. Stays up until Acknowledge; Back does nothing.
// The server re-sends it on every check-in until the ack arrives, so closing it early (maintenance) just brings it back later.
public class AnnouncementActivity extends Activity {
    // Id on screen now, so DeviceService doesn't reopen it every check-in.
    static volatile String showing;

    private final SharedPreferences.OnSharedPreferenceChangeListener listener = (p, key) -> {
        if (DeviceService.PREF_MAINTENANCE.equals(key) && MaintenanceActivity.on(this)) close();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String id = getIntent().getStringExtra("id"), image = getIntent().getStringExtra("image");
        showing = id;
        float dp = getResources().getDisplayMetrics().density;
        int pad = Math.round(24 * dp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Announcement");
        title.setTextColor(0xFF1D2129);
        title.setTextSize(28);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        col.addView(title);

        TextView text = new TextView(this);
        text.setText(getIntent().getStringExtra("text"));
        text.setTextColor(0xFF1D2129);
        text.setTextSize(20);
        text.setPadding(0, pad / 2, 0, pad / 2);
        col.addView(text);

        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        img.setAdjustViewBounds(true);
        img.setVisibility(View.GONE);
        col.addView(img, new LinearLayout.LayoutParams(-1, -2));
        if (image != null && !image.isEmpty()) {
            new Thread(() -> {
                try {
                    Bitmap b = DeviceService.fetchImage(this, image);
                    if (b != null) runOnUiThread(() -> {
                        img.setImageBitmap(b);
                        img.setVisibility(View.VISIBLE);
                    });
                } catch (Exception e) {
                    Log.w("Announcement", "image failed", e);
                }
            }).start();
        }

        Button ack = new Button(this);
        ack.setText("Acknowledge");
        ack.setTextSize(22);
        ack.setAllCaps(false);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, Math.round(72 * dp));
        bp.topMargin = pad;
        ack.setOnClickListener(v -> {
            DeviceService.ack(this, id);
            close();
        });
        col.addView(ack, bp);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFFFFFFF);
        scroll.setFillViewport(true);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(col);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
        getSharedPreferences("kiosk", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(listener);
        if (MaintenanceActivity.on(this)) close();
    }

    @Override
    protected void onPause() {
        super.onPause();
        getSharedPreferences("kiosk", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(listener);
    }

    private void close() {
        showing = null;
        finish();
    }

    @Override
    public void onBackPressed() {
        // Must be acknowledged.
    }
}
