package com.greenbotz.kiosk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

// Alarms are lost on reboot; restore the relock if the tablet was unlocked.
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        long until = ctx.getSharedPreferences("kiosk", Context.MODE_PRIVATE)
                .getLong(KioskActivity.PREF_UNLOCK_UNTIL, 0);
        DeviceService.start(ctx);
        if (until > 0) KioskActivity.scheduleRelock(ctx, Math.max(until, System.currentTimeMillis()));
    }
}
