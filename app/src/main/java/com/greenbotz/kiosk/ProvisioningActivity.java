package com.greenbotz.kiosk;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.PersistableBundle;

// QR enrollment, Android 10+: setup asks which mode we want (fully managed), then hands over for final setup.
public class ProvisioningActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PersistableBundle extras = getIntent().getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE);
        AdminReceiver.saveEnrollment(this, extras);
        if (DevicePolicyManager.ACTION_GET_PROVISIONING_MODE.equals(getIntent().getAction())) {
            setResult(RESULT_OK, new Intent().putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE,
                    DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE));
        } else {
            setResult(RESULT_OK);
        }
        finish();
    }
}
