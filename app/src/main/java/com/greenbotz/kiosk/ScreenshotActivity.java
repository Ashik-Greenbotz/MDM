package com.greenbotz.kiosk;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

// Screenshots on Android 9/10 (no accessibility screenshot API there): asks once for screen capture permission.
// ponytail: Android 10 also needs a mediaProjection-type foreground service; add it when an Android 10 device shows up.
// Tick "Don't show again" on the device and later portal screenshots are silent until the app restarts.
public class ScreenshotActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        startActivityForResult(mpm.createScreenCaptureIntent(), 1);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == RESULT_OK && data != null) {
            DeviceService.projection = getSystemService(MediaProjectionManager.class).getMediaProjection(resultCode, data);
            DeviceService.onProjectionReady(this);
        } else {
            DeviceService.screenshotFailed(this, "screen capture permission was refused on the device");
        }
        finish();
    }
}
