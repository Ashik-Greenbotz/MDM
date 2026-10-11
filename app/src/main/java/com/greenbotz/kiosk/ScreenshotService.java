package com.greenbotz.kiosk;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

import java.util.concurrent.Executors;
import java.util.function.Consumer;

// Silent screenshots for the portal on Android 11+. Turned on once over ADB (setup-tablet.bat):
//   settings put secure enabled_accessibility_services com.greenbotz.kiosk/.ScreenshotService
// It reads no screen content and handles no events; it only exists so takeScreenshot() is allowed.
public class ScreenshotService extends AccessibilityService {
    static volatile ScreenshotService instance;

    @Override
    protected void onServiceConnected() {
        instance = this;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    // Calls done with a software bitmap, or with null plus an error message on failure.
    void capture(Consumer<Bitmap> done, Consumer<String> failed) {
        if (android.os.Build.VERSION.SDK_INT < 30) {
            failed.accept("needs Android 11+");
            return;
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, Executors.newSingleThreadExecutor(), new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult r) {
                Bitmap hw = Bitmap.wrapHardwareBuffer(r.getHardwareBuffer(), r.getColorSpace());
                r.getHardwareBuffer().close();
                if (hw == null) {
                    failed.accept("empty screenshot");
                    return;
                }
                done.accept(hw.copy(Bitmap.Config.ARGB_8888, false));
                hw.recycle();
            }

            @Override
            public void onFailure(int code) {
                failed.accept(code == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ? "too soon, try again in a second"
                        : code == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ? "the app on screen blocks screenshots"
                        : "error " + code);
            }
        });
    }
}
