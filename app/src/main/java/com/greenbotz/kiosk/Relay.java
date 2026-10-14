package com.greenbotz.kiosk;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.concurrent.TimeUnit;

// Door relay, one profile per device model so the model-specific file and values live here only.
// Same models and values as RELAY_PROFILES in functions/server.js. The portal may send a verified per-device setup
// (relayCfg in the check-in reply), which wins over the model's profile.
final class Relay {
    static final String GPIO = "/sys/class/gpio-ctrl/relay1/enable";   // Telpo F8, F10B: app can read and write
    static final String TELPOIO = "/sys/class/telpoio/relay_en";        // Telpo F10 (Android 9): root only, can't be read
    // The only files the app will ever write, whatever the portal says (it writes them as root on the F10).
    private static final String[] FILES = {GPIO, TELPOIO};
    static final String[] MODELS = {"Samsung Tablet", "Telpo F10", "Telpo F8", "Telpo F10B", "Telpo VCP", "Akuvox", "Others"};
    static final String PREF_MODEL = "device_model", PREF_USAGE = "usage", PREF_CFG = "relay_cfg";
    private static final String PREF_LAST = "relay", PREF_RESULT = "relay_result", PREF_RESULT_AT = "relay_result_at";

    final File file;
    final String on, off;
    final boolean root, fromPortal;

    private Relay(String path, String on, String off, boolean root, boolean fromPortal) {
        this.file = new File(path);
        this.on = on;
        this.off = off;
        this.root = root;
        this.fromPortal = fromPortal;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("kiosk", Context.MODE_PRIVATE);
    }

    // The relay to use, or null when this device has none (or its model has none).
    static Relay current(Context ctx) {
        SharedPreferences p = prefs(ctx);
        try {
            String cfg = p.getString(PREF_CFG, "");
            if (!cfg.isEmpty()) {
                JSONObject j = new JSONObject(cfg);
                if (j.optBoolean("none")) return null;
                String f = j.optString("iface"), on = j.optString("on", "0"), off = j.optString("off", "1");
                if (known(f) && valid(on) && valid(off) && !on.equals(off)) return new Relay(f, on, off, j.optBoolean("root"), true);
            }
        } catch (Exception ignored) {
        }
        switch (p.getString(PREF_MODEL, "")) {
            case "Samsung Tablet": return null;
            case "Telpo F8": case "Telpo F10B": return new Relay(GPIO, "0", "1", false, false);
            case "Telpo F10": return new Relay(TELPOIO, "0", "1", true, false);
            default:
                // No verified profile (no model yet, VCP, Akuvox, Others): use a known relay file if one exists,
                // with the mapping every tested Telpo uses (as before app 10.14). Never written during detection.
                if (new File(GPIO).exists()) return new Relay(GPIO, "0", "1", false, false);
                if (new File(TELPOIO).exists()) return new Relay(TELPOIO, "0", "1", true, false);
                return null;
        }
    }

    private static boolean known(String f) {
        for (String k : FILES) if (k.equals(f)) return true;
        return false;
    }

    private static boolean valid(String v) {
        return v.equals("0") || v.equals("1");
    }

    // Best guess for the setup screen; the person setting up confirms or changes it. Only looks, never writes.
    static String suggestModel(Context ctx) {
        String maker = Build.MANUFACTURER.toLowerCase(), model = Build.MODEL.toUpperCase(), board = (Build.BOARD + " " + Build.HARDWARE).toLowerCase();
        if (maker.contains("samsung")) return "Samsung Tablet";
        if (maker.contains("akuvox")) return "Akuvox";
        if (new File(TELPOIO).exists()) return "Telpo F10";
        if (new File(GPIO).exists()) return model.contains("F10B") || board.contains("rk3568") ? "Telpo F10B" : "Telpo F8";
        if (model.contains("VCP")) return "Telpo VCP";
        return "Others";
    }

    // null = no relay file. Read back when the file is readable; otherwise the last value written.
    Boolean state(Context ctx) {
        if (!file.exists()) return null;
        if (!file.canRead()) return prefs(ctx).getBoolean(PREF_LAST, false);
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            return on.equals(r.readLine().trim());
        } catch (Exception e) {
            return null;
        }
    }

    boolean readable() {
        return file.canRead();
    }

    // Writes on/off and records the result for the portal. Throws on failure (no permission, su missing or refused, timeout).
    void set(Context ctx, boolean value) throws Exception {
        String v = value ? on : off;
        try {
            if (!root && file.canWrite()) {
                try (FileWriter w = new FileWriter(file)) {
                    w.write(v);
                }
            } else {
                Process proc = new ProcessBuilder("su", "0", "sh", "-c", "echo " + v + " > " + file.getPath()).redirectErrorStream(true).start();
                if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                    proc.destroy();
                    throw new Exception("su timed out (no root?)");
                }
                if (proc.exitValue() != 0) throw new Exception("su exit " + proc.exitValue() + " (no root?)");
            }
        } catch (Exception e) {
            result(ctx, (value ? "ON" : "OFF") + " failed: " + e.getMessage());
            throw e;
        }
        prefs(ctx).edit().putBoolean(PREF_LAST, value).apply();
        result(ctx, (value ? "ON" : "OFF") + " ok (wrote " + v + ")");
    }

    private static void result(Context ctx, String text) {
        prefs(ctx).edit().putString(PREF_RESULT, text).putLong(PREF_RESULT_AT, System.currentTimeMillis()).apply();
    }

    // For the portal's Relay card.
    JSONObject info(Context ctx) throws Exception {
        SharedPreferences p = prefs(ctx);
        JSONObject j = new JSONObject().put("iface", file.getPath()).put("exists", file.exists()).put("readable", readable())
                .put("root", root).put("on", on).put("off", off).put("source", fromPortal ? "portal" : "model");
        if (p.contains(PREF_RESULT)) j.put("lastResult", p.getString(PREF_RESULT, "")).put("lastAt", p.getLong(PREF_RESULT_AT, 0));
        return j;
    }
}
