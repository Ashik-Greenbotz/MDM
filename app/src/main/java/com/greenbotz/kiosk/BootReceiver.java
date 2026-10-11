package com.greenbotz.kiosk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

// Alarms are lost on reboot; restore the relock if the tablet was unlocked. Also runs after the app is updated.
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        long until = ctx.getSharedPreferences("kiosk", Context.MODE_PRIVATE)
                .getLong(KioskActivity.PREF_UNLOCK_UNTIL, 0);
        DeviceService.start(ctx);
        if (until > 0) KioskActivity.scheduleRelock(ctx, Math.max(until, System.currentTimeMillis()));
        // After a portal update the old kiosk screen is gone; bring it back (locked) right away.
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction()) && !KioskActivity.monitorOnly(ctx) && until <= System.currentTimeMillis()) {
            ctx.startActivity(new Intent(ctx, KioskActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }
}
