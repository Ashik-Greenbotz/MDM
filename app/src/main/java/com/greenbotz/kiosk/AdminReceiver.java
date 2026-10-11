package com.greenbotz.kiosk;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.PersistableBundle;

public class AdminReceiver extends DeviceAdminReceiver {
    // QR enrollment, Android 9: the QR code's server / mode / token arrive here once setup finishes.
    @Override
    public void onProfileProvisioningComplete(Context ctx, Intent intent) {
        saveEnrollment(ctx, intent.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE));
        ctx.startActivity(new Intent(ctx, KioskActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    static void saveEnrollment(Context ctx, PersistableBundle extras) {
        if (extras == null) return;
        SharedPreferences.Editor e = ctx.getSharedPreferences("kiosk", Context.MODE_PRIVATE).edit();
        String server = extras.getString("server_url"), mode = extras.getString("mode"), token = extras.getString("token");
        if (server != null && server.matches("https?://.+")) e.putString(DeviceService.PREF_SERVER, server);
        if ("monitor".equals(mode) || "kiosk".equals(mode)) e.putString(KioskActivity.PREF_MODE, mode);
        if (token != null) e.putString(DeviceService.PREF_TOKEN, token);
        e.commit();
    }
}
