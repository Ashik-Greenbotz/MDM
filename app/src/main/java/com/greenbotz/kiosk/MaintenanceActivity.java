package com.greenbotz.kiosk;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;

// Full-screen "System Maintenance" image shown on every device while the portal has maintenance mode on.
// The image is the one uploaded on the portal's Configuration page, or the built-in one.
// It sits on top of the home screen or a pinned app; Back does nothing. It closes itself when the portal turns it off.
public class MaintenanceActivity extends Activity {
    static boolean on(Context ctx) {
        return ctx.getSharedPreferences("kiosk", MODE_PRIVATE).getBoolean(DeviceService.PREF_MAINTENANCE, false);
    }

    private final SharedPreferences.OnSharedPreferenceChangeListener listener = (p, key) -> {
        if (DeviceService.PREF_MAINTENANCE.equals(key) && !on(this)) leave();
        if (DeviceService.PREF_MAINT_IMG.equals(key)) runOnUiThread(this::showImage);
    };
    private android.widget.FrameLayout root;
    private ImageView img;

    private void showImage() {
        java.io.File f = new java.io.File(getFilesDir(), "maintenance.jpg");
        android.graphics.Bitmap b = getSharedPreferences("kiosk", MODE_PRIVATE).getLong(DeviceService.PREF_MAINT_IMG, 0) != 0 && f.exists()
                ? android.graphics.BitmapFactory.decodeFile(f.getPath()) : null;
        if (b != null) {
            img.setImageBitmap(b);
            root.setBackgroundColor(0xFF000000);
        } else {
            img.setImageResource(R.drawable.maintenance);
            root.setBackgroundColor(0xFF53BFE0); // the built-in image's blue, so the letterbox bars blend in
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root = new android.widget.FrameLayout(this);
        img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        showImage();
        root.addView(img, new android.widget.FrameLayout.LayoutParams(-1, -1));
        android.widget.TextView footer = new android.widget.TextView(this);
        footer.setText(R.string.footer);
        footer.setTextColor(0xCCFFFFFF);
        footer.setTextSize(12);
        int pad = Math.round(12 * getResources().getDisplayMetrics().density);
        footer.setPadding(pad, pad, pad, pad);
        android.widget.FrameLayout.LayoutParams fp = new android.widget.FrameLayout.LayoutParams(-2, -2);
        fp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
        root.addView(footer, fp);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        DeviceService.maintenanceShowing = true;
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
        getSharedPreferences("kiosk", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(listener);
        if (!on(this)) leave();
    }

    @Override
    protected void onPause() {
        super.onPause();
        DeviceService.maintenanceShowing = false;
        getSharedPreferences("kiosk", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(listener);
    }

    // Maintenance is over: hand back to the kiosk screen, which re-locks and reopens the pinned app if any.
    private void leave() {
        startActivity(new Intent(this, KioskActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }

    @Override
    public void onBackPressed() {
        // Trapped on purpose.
    }
}
