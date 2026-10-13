package com.greenbotz.kiosk;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

// App pinning, manual override: an invisible square in the top-right corner on top of the pinned app.
// 6 taps within 3 s open the kiosk screen's override PIN prompt (works offline).
// Needs "draw over other apps", granted once over ADB (setup-tablet.bat); without it, Home -> kiosk screen still works.
final class PinOverlay {
    private static View view;
    private static int taps;
    private static long firstTapAt;

    private PinOverlay() {
    }

    // Called from DeviceService every check-in: shows the overlay only while an app is pinned and the kiosk is locked.
    static void sync(Context ctx) {
        Context app = ctx.getApplicationContext();
        boolean want = KioskActivity.isPinned(app) && Settings.canDrawOverlays(app);
        if (want && view == null) show(app);
        if (!want && view != null) hide(app);
    }

    private static void show(Context app) {
        int size = Math.round(70 * app.getResources().getDisplayMetrics().density);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        view = new View(app);
        view.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN && tap()) {
                app.startActivity(new Intent(app, KioskActivity.class)
                        .putExtra(KioskActivity.EXTRA_OVERRIDE, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            }
            return true;
        });
        try {
            app.getSystemService(WindowManager.class).addView(view, lp);
        } catch (RuntimeException e) {
            view = null;
        }
    }

    private static void hide(Context app) {
        try {
            app.getSystemService(WindowManager.class).removeView(view);
        } catch (RuntimeException ignored) {
        }
        view = null;
    }

    // True on the 6th tap within 3 seconds.
    static boolean tap() {
        long now = SystemClock.elapsedRealtime();
        if (now - firstTapAt > 3000) {
            firstTapAt = now;
            taps = 0;
        }
        if (++taps >= 6) {
            taps = 0;
            return true;
        }
        return false;
    }
}
