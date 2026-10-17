package com.greenbotz.kiosk;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;

import org.json.JSONArray;
import org.json.JSONObject;

// Idle slideshow ("digital signage"): shows the site's images full-screen when the kiosk home has been idle for a
// while. Any touch closes it and returns to the home screen. Pushed per site from the portal.
public class SignageActivity extends Activity {
    private ImageView img;
    private final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
    private String[] urls = new String[0];
    private int seconds = 10, pos;
    private final java.util.Map<String, Bitmap> cache = new java.util.HashMap<>();

    static boolean enabled(android.content.Context ctx) {
        try {
            String s = ctx.getSharedPreferences("kiosk", Activity.MODE_PRIVATE).getString(DeviceService.PREF_SIGNAGE, "");
            if (s.isEmpty()) return false;
            JSONObject o = new JSONObject(s);
            return o.optBoolean("enabled") && o.optJSONArray("urls") != null && o.optJSONArray("urls").length() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    static int idleMs(android.content.Context ctx) {
        try {
            return Math.max(1, new JSONObject(ctx.getSharedPreferences("kiosk", Activity.MODE_PRIVATE)
                    .getString(DeviceService.PREF_SIGNAGE, "{}")).optInt("idleMin", 2)) * 60 * 1000;
        } catch (Exception e) {
            return 120000;
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        img.setBackgroundColor(Color.BLACK);
        setContentView(img);
        try {
            JSONObject o = new JSONObject(getSharedPreferences("kiosk", MODE_PRIVATE).getString(DeviceService.PREF_SIGNAGE, "{}"));
            seconds = Math.max(2, o.optInt("seconds", 10));
            JSONArray a = o.optJSONArray("urls");
            urls = new String[a == null ? 0 : a.length()];
            for (int i = 0; i < urls.length; i++) urls[i] = a.getString(i);
        } catch (Exception e) {
            finish();
            return;
        }
        if (urls.length == 0) finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
        if (!enabled(this)) { finish(); return; }
        pos = 0;
        next();
    }

    private void next() {
        if (urls.length == 0) return;
        String url = urls[pos % urls.length];
        pos++;
        Bitmap cached = cache.get(url);
        if (cached != null) show(cached);
        else new Thread(() -> {
            try {
                Bitmap bm = DeviceService.fetchImage(this, url);
                if (bm != null) { cache.put(url, bm); runOnUiThread(() -> show(bm)); }
            } catch (Exception e) {
                Log.w("Signage", "image failed: " + url, e);
            }
        }).start();
        h.postDelayed(this::next, seconds * 1000L);
    }

    private void show(Bitmap bm) {
        img.setImageBitmap(bm);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        finish(); // any touch wakes the kiosk home
        return true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacksAndMessages(null);
    }

    @Override
    public void onBackPressed() {
        finish();
    }
}
