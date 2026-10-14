package com.greenbotz.kiosk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

// Technician screen on a technical team tablet (usage "tech"), opened from the "My tickets" tile on the normal home screen.
// They can accept, start, hold, add notes and photos and submit a job as resolved; closing is only done in the portal.
// The last ticket list is kept (PREF_CACHE) so the screen still shows it without network; updates made offline
// wait in DeviceService's tech outbox. 6 taps top-right open the admin password, as on the kiosk screen.
public class TechHomeActivity extends Activity {
    private static final String PREF_CACHE = "tech_cache";
    private static final long POLL_MS = 15 * 1000;
    private static final int PICK_CAMERA = 1, PICK_GALLERY = 2;
    private static final String[] NOTE_KINDS = {"note", "work", "pending", "finding", "next", "parts"};
    private static final String[] NOTE_NAMES = {"Work summary / progress", "Work completed", "Pending items", "Issue encountered", "Next steps / follow-up", "Parts / replacement"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private JSONObject home;          // last /api/tech/home reply
    private String openTicket;        // ticket id shown in detail, null = list
    private String error;             // why the last refresh failed (shown as a banner)
    private Uri cameraUri;
    private ScrollView scroll;
    private String shownScreen = "", draft = ""; // screen on display (list or a ticket id), unsent note text
    private final int[] draftKind = {0}; // note type picked (kept across refreshes)
    private float dp;
    private int taps;
    private long firstTapAt;
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            load();
            handler.postDelayed(this, POLL_MS);
        }
    };

    // Open tickets in the last saved list (for the home screen tile).
    static int openCount(Context ctx) {
        try {
            JSONArray a = new JSONObject(ctx.getSharedPreferences("kiosk", MODE_PRIVATE).getString(PREF_CACHE, "{}")).optJSONArray("tickets");
            int n = 0;
            for (int i = 0; a != null && i < a.length(); i++) {
                String s = a.optJSONObject(i).optString("status");
                if (!s.equals("resolved") && !s.equals("closed") && !s.equals("cancelled")) n++;
            }
            return n;
        } catch (Exception e) {
            return 0;
        }
    }

    static boolean wanted(Context ctx) {
        return "tech".equals(ctx.getSharedPreferences("kiosk", MODE_PRIVATE).getString(Relay.PREF_USAGE, "onsite"));
    }

    // Apps the technician screen hands off to; they must be allowed in lock task mode or they won't open.
    static Set<String> helperApps(Context ctx) {
        Set<String> out = new TreeSet<>();
        Intent[] intents = {new Intent(MediaStore.ACTION_IMAGE_CAPTURE), new Intent(Intent.ACTION_GET_CONTENT).setType("image/*"),
                new Intent(Intent.ACTION_VIEW, Uri.parse("geo:1.3,103.8"))};
        for (Intent i : intents) for (ResolveInfo ri : ctx.getPackageManager().queryIntentActivities(i, 0)) out.add(ri.activityInfo.packageName);
        for (String p : new String[]{"com.android.documentsui", "com.google.android.documentsui", "com.android.providers.media.module",
                "com.google.android.providers.media.module", "com.google.android.apps.photos"}) {
            try {
                ctx.getPackageManager().getPackageInfo(p, 0);
                out.add(p);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(KioskActivity.themed(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dp = getResources().getDisplayMetrics().density;
        try {
            home = new JSONObject(prefs().getString(PREF_CACHE, "{}"));
        } catch (Exception e) {
            home = new JSONObject();
        }
        if (savedInstanceState != null) {
            openTicket = savedInstanceState.getString("open");
            String u = savedInstanceState.getString("camera");
            if (u != null) cameraUri = Uri.parse(u);
        }
        render();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("open", openTicket);
        if (cameraUri != null) out.putString("camera", cameraUri.toString());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!wanted(this)) { // usage changed back to onsite in the portal or the admin menu
            finish();
            return;
        }
        handler.post(poll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(poll);
    }

    @Override
    public void onBackPressed() {
        if (openTicket != null) {
            openTicket = null;
            render();
        } else {
            finish(); // back to the normal home screen
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("kiosk", MODE_PRIVATE);
    }

    // ---- data ----
    private void load() {
        new Thread(() -> {
            try {
                JSONObject r = DeviceService.techCall(this, "GET", "/api/tech/home", null);
                prefs().edit().putString(PREF_CACHE, r.toString()).apply();
                // only redraw when something changed (redrawing loses the keyboard focus)
                boolean same = error == null && String.valueOf(r.opt("tickets")).equals(String.valueOf(home.opt("tickets")))
                        && String.valueOf(r.opt("tech")).equals(String.valueOf(home.opt("tech")));
                runOnUiThread(() -> { home = r; error = null; if (!same) render(); });
            } catch (DeviceService.HttpError e) {
                runOnUiThread(() -> { error = e.getMessage(); render(); });
            } catch (Exception e) {
                runOnUiThread(() -> { error = "No connection to the portal: showing the last saved tickets."; render(); });
            }
        }).start();
    }

    private List<JSONObject> tickets() {
        List<JSONObject> out = new ArrayList<>();
        JSONArray a = home.optJSONArray("tickets");
        for (int i = 0; a != null && i < a.length(); i++) out.add(a.optJSONObject(i));
        return out;
    }

    private JSONObject ticket(String id) {
        for (JSONObject t : tickets()) if (t.optString("id").equals(id)) return t;
        return null;
    }

    // Sends a ticket update now, or queues it when there is no network. done runs after a successful send.
    private void send(String id, String what, JSONObject body, Runnable done) {
        try {
            body.put("op", UUID.randomUUID().toString());
        } catch (Exception ignored) {
        }
        String path = "/api/tech/tickets/" + id + "/" + what;
        new Thread(() -> {
            try {
                DeviceService.techCall(this, "POST", path, body);
                runOnUiThread(() -> { if (done != null) done.run(); load(); });
            } catch (DeviceService.HttpError e) {
                runOnUiThread(() -> alert("Not saved", e.getMessage()));
            } catch (Exception e) {
                DeviceService.techQueue(this, path, body);
                runOnUiThread(() -> {
                    toast("No connection: saved on the tablet, it is sent when the portal is reachable");
                    if (done != null) done.run();
                });
            }
        }).start();
    }

    // ---- screens ----
    private void render() {
        if (openTicket != null && ticket(openTicket) == null) openTicket = null;
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(20), px(20), px(20), px(28));
        header(col);
        if (error != null) {
            TextView b = text(error, 15, R.color.err, false);
            b.setBackground(card());
            b.setPadding(px(14), px(10), px(14), px(10));
            col.addView(b, margins(-1, 0, 0, 12));
        }
        if (openTicket == null) list(col); else detail(col, ticket(openTicket));
        // same screen as before (a refresh): keep the scroll position
        String screen = String.valueOf(openTicket);
        int y = scroll != null && screen.equals(shownScreen) ? scroll.getScrollY() : 0;
        shownScreen = screen;
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(col);
        scroll.post(() -> scroll.scrollTo(0, y));
        FrameLayout root = new FrameLayout(this);
        root.setBackground(getDrawable(R.drawable.bg_tech));
        root.addView(scroll);
        View corner = new View(this); // invisible admin corner, same as the kiosk screen
        corner.setOnClickListener(v -> cornerTap());
        root.addView(corner, new FrameLayout.LayoutParams(px(70), px(70), Gravity.TOP | Gravity.END));
        setContentView(root);
    }

    private void header(LinearLayout col) {
        JSONObject tech = home.optJSONObject("tech");
        String name = tech != null ? tech.optString("name") : prefs().getString(DeviceService.PREF_TECH_NAME, "");
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView logo = new android.widget.ImageView(this); // technician icon, same as the home screen "My tickets" tile
        logo.setImageResource(R.drawable.tech_icon);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(64), px(64));
        lp.setMarginEnd(px(14));
        row.addView(logo, lp);
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.addView(text(name.isEmpty() ? "Technician tablet" : "Hi, " + name, 26, R.color.text_primary, true));
        names.addView(text("Device ID " + prefs().getString(DeviceService.PREF_CODE, "–") + " · technical team", 14, R.color.text_secondary, false));
        row.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        boolean ok = prefs().getBoolean(DeviceService.PREF_SERVER_OK, false) && error == null;
        TextView pill = text(ok ? "● Online" : "● Offline", 15, ok ? R.color.ok : R.color.err, true);
        pill.setBackground(card());
        pill.setPadding(px(14), px(8), px(14), px(8));
        row.addView(pill);
        col.addView(row, margins(-1, 0, 0, 14));
        if (tech == null) {
            if (error == null) col.addView(text("Loading your tickets…", 16, R.color.text_secondary, false));
            return;
        }
    }

    private void list(LinearLayout col) {
        Button back = button("← Home", false);
        back.setOnClickListener(v -> finish());
        col.addView(back, margins(-2, 0, 0, 10));
        if (home.optJSONObject("tech") == null) return;
        List<JSONObject> all = tickets(), active = new ArrayList<>();
        int newAcc = 0, prog = 0, hold = 0, waiting = 0;
        for (JSONObject t : all) {
            String s = t.optString("status");
            if (s.equals("assigned") || s.equals("accepted")) newAcc++;
            if (s.equals("in_progress")) prog++;
            if (s.equals("on_hold")) hold++;
            if (s.equals("resolved")) waiting++;
            if (!s.equals("resolved") && !s.equals("closed") && !s.equals("cancelled")) active.add(t);
        }
        GridLayout g = new GridLayout(this);
        g.setColumnCount(getResources().getConfiguration().screenWidthDp >= 600 ? 5 : 3);
        String[][] tiles = {{String.valueOf(active.size() + waiting), "Assigned"}, {String.valueOf(newAcc), "New / accepted"},
                {String.valueOf(prog), "In progress"}, {String.valueOf(hold), "On hold"}, {String.valueOf(active.size()), "Overall open tickets"}};
        for (String[] t : tiles) {
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setBackground(getDrawable(R.drawable.glass_hi));
            tile.setPadding(px(14), px(12), px(14), px(12));
            tile.addView(text(t[0], 28, R.color.text_primary, true));
            tile.addView(text(t[1], 13, R.color.text_secondary, false));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            lp.width = 0;
            lp.setMargins(px(4), px(4), px(4), px(4));
            g.addView(tile, lp);
        }
        col.addView(g, margins(-1, 0, 0, 12));
        // Most urgent first: priority, then due time
        active.sort((a, b) -> {
            int pa = prio(a.optString("priority")), pb = prio(b.optString("priority"));
            return pa != pb ? pb - pa : Long.compare(a.optLong("due", Long.MAX_VALUE), b.optLong("due", Long.MAX_VALUE));
        });
        col.addView(text("My Tasks", 20, R.color.text_primary, true), margins(-1, 8, 0, 6));
        if (active.isEmpty()) col.addView(text("No open tickets right now.", 16, R.color.text_secondary, false), margins(-1, 0, 0, 8));
        for (JSONObject t : active) col.addView(ticketCard(t), margins(-1, 0, 0, 10));
    }

    private View ticketCard(JSONObject t) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(getDrawable(R.drawable.glass_hi));
        c.setPadding(px(16), px(14), px(16), px(14));
        LinearLayout top = new LinearLayout(this);
        top.addView(text(t.optString("no") + "  " + t.optString("priority").toUpperCase(), 13, prioColor(t.optString("priority")), true), new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(text(t.optString("statusLabel") + (t.optBoolean("overdue") ? " · OVERDUE" : ""), 13, statusColor(t), true));
        c.addView(top);
        c.addView(text(t.optString("title"), 19, R.color.text_primary, true), margins(-1, 4, 0, 2));
        c.addView(text(place(t), 14, R.color.text_secondary, false));
        JSONObject d = t.optJSONObject("device");
        if (d != null) c.addView(text("Device " + d.optString("code") + " · " + d.optString("name") + (d.optBoolean("removed") ? "" : d.optBoolean("online") ? " · online" : " · offline"), 14, R.color.text_secondary, false));
        String desc = t.optString("desc");
        if (!desc.isEmpty()) c.addView(text(desc.length() > 140 ? desc.substring(0, 140) + "…" : desc, 14, R.color.text_primary, false), margins(-1, 4, 0, 0));
        String when = "Assigned " + time(t.optLong("assignedAt", t.optLong("createdAt"))) + (t.optLong("due") > 0 ? " · due " + time(t.optLong("due")) : "")
                + (t.isNull("distanceKm") ? "" : " · " + t.optDouble("distanceKm") + " km");
        c.addView(text(when, 13, R.color.text_secondary, false), margins(-1, 4, 0, 0));
        String id = t.optString("id");
        c.setOnClickListener(v -> { openTicket = id; render(); });
        return c;
    }

    private void detail(LinearLayout col, JSONObject t) {
        Button back = button("← All tickets", false);
        back.setOnClickListener(v -> { openTicket = null; render(); });
        col.addView(back, margins(-2, 0, 0, 10));
        col.addView(ticketCard(t), margins(-1, 0, 0, 10));
        String id = t.optString("id"), s = t.optString("status");
        // Status buttons the technician may use (closing is portal-only), right under the ticket
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        if (s.equals("assigned")) row.addView(action("Accept ticket", () -> move(id, "accepted", null)), margins(-1, 0, 0, 8));
        if (s.equals("accepted") || s.equals("on_hold")) row.addView(action(s.equals("on_hold") ? "Resume work" : "Start work", () -> move(id, "in_progress", null)), margins(-1, 0, 0, 8));
        if (s.equals("accepted") || s.equals("in_progress")) row.addView(action("Put on hold…", () -> ask("Put on hold", "Why is the work on hold?", 1, txt -> move(id, "on_hold", txt))), margins(-1, 0, 0, 8));
        if (s.equals("in_progress")) row.addView(action("Submit as resolved…", () -> ask("Submit as resolved",
                "Completion summary: what was wrong and what you did. An admin closes the ticket in the portal after checking it.", 10, txt -> move(id, "resolved", txt))), margins(-1, 0, 0, 8));
        if (s.equals("resolved")) row.addView(text("Submitted. Waiting for the portal to close it.", 15, R.color.ok, true));
        if (s.equals("closed") || s.equals("cancelled")) row.addView(text("This ticket is " + t.optString("statusLabel").toLowerCase() + ".", 15, R.color.text_secondary, true));
        col.addView(row, margins(-1, 0, 0, 10));
        LinearLayout box = section(col, "Details");
        String full = t.optString("desc");
        box.addView(text((full.isEmpty() ? "No description." : full) + "\n\nCategory: " + t.optString("category"), 15, R.color.text_primary, false));
        JSONObject placeAt = t.optJSONObject("place");
        if (placeAt != null) {
            Button nav = button("Navigate to site", false);
            nav.setOnClickListener(v -> openOrSay(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + placeAt.optDouble("lat") + "," + placeAt.optDouble("lng")
                    + "(" + Uri.encode(t.optString("site", "Site")) + ")")), "No maps app on this tablet"));
            box.addView(nav, margins(-2, 10, 0, 0));
        }
        if (!s.equals("closed") && !s.equals("cancelled")) {
            // Notes
            LinearLayout notes = section(col, "Add Work Summary & Updates");
            Button kind = SetupActivity.picker(this, "Type of update", NOTE_NAMES, draftKind);
            notes.addView(kind, margins(-1, 0, 0, 4));
            EditText note = new EditText(this);
            note.setHint("Write here…");
            note.setMinLines(2);
            note.setTextColor(getColor(R.color.text_primary));
            note.setHintTextColor(getColor(R.color.text_secondary));
            note.setText(draft);
            note.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
                @Override public void onTextChanged(CharSequence c, int a, int b, int d) { draft = c.toString(); }
                @Override public void afterTextChanged(android.text.Editable e) { }
            });
            notes.addView(note);
            Button add = button("Add update", true);
            add.setOnClickListener(v -> {
                String txt = note.getText().toString().trim();
                if (txt.isEmpty()) { toast("Write the update first"); return; }
                try {
                    send(id, "note", new JSONObject().put("kind", NOTE_KINDS[draftKind[0]]).put("text", txt), () -> { draft = ""; note.setText(""); });
                } catch (Exception ignored) {
                }
            });
            notes.addView(add, margins(-1, 8, 0, 0));
            // Photos
            LinearLayout photos = section(col, "Photos");
            LinearLayout pr = new LinearLayout(this);
            Button cam = button("Take photo", false), gal = button("From gallery", false);
            cam.setOnClickListener(v -> takePhoto());
            gal.setOnClickListener(v -> pickPhoto());
            pr.addView(cam, weight(8));
            pr.addView(gal, weight(0));
            photos.addView(pr);
            ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setId(View.generateViewId());
            bar.setVisibility(View.GONE);
            bar.setTag("upload");
            photos.addView(bar, margins(-1, 8, 0, 0));
        }
        JSONArray files = t.optJSONArray("files");
        if (files != null && files.length() > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < files.length(); i++) sb.append("📎 ").append(files.optJSONObject(i).optString("name")).append(" · ").append(files.optJSONObject(i).optString("by")).append("\n");
            section(col, "Files (" + files.length() + ")").addView(text(sb.toString().trim(), 14, R.color.text_primary, false));
        }
        // History, newest first, with the portal's comments
        LinearLayout hist = section(col, "History");
        JSONArray tl = t.optJSONArray("timeline");
        for (int i = tl == null ? -1 : tl.length() - 1; i >= 0; i--) {
            JSONObject e = tl.optJSONObject(i);
            hist.addView(text(time(e.optLong("at")) + " · " + e.optString("by"), 12, R.color.text_secondary, false), margins(-1, 6, 0, 0));
            hist.addView(text(describe(e), 15, R.color.text_primary, false));
        }
    }

    private void move(String id, String to, String text) {
        try {
            send(id, "status", new JSONObject().put("to", to).put("text", text == null ? "" : text), null);
        } catch (Exception ignored) {
        }
    }

    // ---- photos: camera into a MediaStore entry (no FileProvider needed), or picked from the gallery ----
    private void takePhoto() {
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, "ticket-" + System.currentTimeMillis() + ".jpg");
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            cameraUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, cameraUri)
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, PICK_CAMERA);
        } catch (Exception e) {
            alert("Camera", "Couldn't open the camera: " + e.getMessage());
        }
    }

    private void pickPhoto() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "application/pdf"});
        try {
            startActivityForResult(i, PICK_GALLERY);
        } catch (Exception e) {
            alert("Gallery", "No gallery app is allowed on this tablet: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        Uri uri = req == PICK_CAMERA ? cameraUri : data == null ? null : data.getData();
        if (res != RESULT_OK || uri == null || openTicket == null) {
            if (req == PICK_CAMERA && cameraUri != null) getContentResolver().delete(cameraUri, null, null);
            return;
        }
        String id = openTicket;
        boolean fromCamera = req == PICK_CAMERA;
        ProgressBar bar = getWindow().getDecorView().findViewWithTag("upload");
        if (bar != null) { bar.setVisibility(View.VISIBLE); bar.setProgress(0); }
        new Thread(() -> {
            try {
                String type = getContentResolver().getType(uri);
                boolean pdf = "application/pdf".equals(type);
                byte[] bytes = pdf ? readAll(uri) : jpeg(uri);
                if (bytes.length > 10 * 1024 * 1024) throw new Exception("file is over 10 MB");
                String name = (pdf ? "document-" : "photo-") + System.currentTimeMillis() + (pdf ? ".pdf" : ".jpg");
                DeviceService.techUpload(this, "/api/tech/tickets/" + id + "/files?name=" + Uri.encode(name) + "&op=" + UUID.randomUUID(), bytes,
                        p -> runOnUiThread(() -> { if (bar != null) bar.setProgress(p); }));
                runOnUiThread(() -> { toast("Uploaded"); load(); });
            } catch (Exception e) {
                runOnUiThread(() -> { if (bar != null) bar.setVisibility(View.GONE); alert("Upload failed", e.getMessage() + "\n\nCheck the connection and try again."); });
            } finally {
                if (fromCamera) getContentResolver().delete(uri, null, null); // don't leave ticket photos in the tablet's gallery
            }
        }).start();
    }

    private byte[] readAll(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[16384];
            for (int n; (n = in.read(chunk)) > 0; ) {
                buf.write(chunk, 0, n);
                if (buf.size() > 10 * 1024 * 1024) throw new Exception("file is over 10 MB");
            }
            return buf.toByteArray();
        }
    }

    // Photo as a JPEG of at most ~2000 px, turned upright (cameras store the rotation in EXIF).
    private byte[] jpeg(Uri uri) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, o); }
        if (o.outWidth <= 0) throw new Exception("not a picture");
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (Math.max(o.outWidth, o.outHeight) / o.inSampleSize > 2600) o.inSampleSize *= 2;
        Bitmap b;
        try (InputStream in = getContentResolver().openInputStream(uri)) { b = BitmapFactory.decodeStream(in, null, o); }
        int rot = 0;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            int ori = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            rot = ori == ExifInterface.ORIENTATION_ROTATE_90 ? 90 : ori == ExifInterface.ORIENTATION_ROTATE_180 ? 180 : ori == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
        } catch (Exception ignored) {
        }
        float scale = Math.min(1f, 2000f / Math.max(b.getWidth(), b.getHeight()));
        Matrix m = new Matrix();
        m.postScale(scale, scale);
        m.postRotate(rot);
        Bitmap out = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        out.compress(Bitmap.CompressFormat.JPEG, 85, buf);
        return buf.toByteArray();
    }

    // ---- admin corner ----
    private void cornerTap() {
        long now = SystemClock.elapsedRealtime();
        if (now - firstTapAt > 3000) { firstTapAt = now; taps = 0; }
        if (++taps >= 6) {
            taps = 0;
            startActivity(new Intent(this, KioskActivity.class).putExtra(KioskActivity.EXTRA_ADMIN, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    // ---- small UI helpers (glass look, same colours as the kiosk screen) ----
    private interface Text { void got(String s); }

    private void ask(String title, String hint, int minLen, Text done) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setMinLines(3);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        AlertDialog d = new AlertDialog.Builder(this).setTitle(title).setView(e).setPositiveButton("Send", null).setNegativeButton("Cancel", null).create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String s = e.getText().toString().trim();
            if (s.length() < minLen) { e.setError(minLen > 1 ? "Write at least " + minLen + " characters" : "Required"); return; }
            d.dismiss();
            done.got(s);
        }));
        d.show();
    }

    private void alert(String title, String msg) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private void openOrSay(Intent i, String fail) {
        try {
            startActivity(i);
        } catch (Exception e) {
            toast(fail);
        }
    }

    private LinearLayout section(LinearLayout col, String title) {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        s.setBackground(getDrawable(R.drawable.glass_hi));
        s.setPadding(px(16), px(14), px(16), px(16));
        s.addView(text(title, 17, R.color.text_primary, true), margins(-1, 0, 0, 8));
        col.addView(s, margins(-1, 0, 0, 10));
        return s;
    }

    private Button action(String label, Runnable r) {
        Button b = button(label, true);
        b.setOnClickListener(v -> r.run());
        return b;
    }

    private Button button(String label, boolean solid) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(17);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(16));
        g.setColor(solid ? getColor(R.color.accent) : getColor(R.color.glass_fill));
        if (!solid) g.setStroke(px(1), getColor(R.color.glass_edge));
        b.setBackground(g);
        b.setTextColor(solid ? 0xFFFFFFFF : getColor(R.color.text_primary));
        b.setPadding(px(16), 0, px(16), 0);
        b.setMinHeight(px(52));
        return b;
    }

    private TextView text(String s, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(getColor(color));
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private android.graphics.drawable.Drawable card() {
        return getDrawable(R.drawable.glass_hi);
    }

    private LinearLayout.LayoutParams margins(int width, int top, int end, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, -2);
        p.setMargins(0, px(top), px(end), px(bottom));
        return p;
    }

    private LinearLayout.LayoutParams weight(int endMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, px(52), 1);
        p.setMarginEnd(px(endMargin));
        return p;
    }

    private int px(int v) {
        return Math.round(v * dp);
    }

    private static int prio(String p) {
        return p.equals("urgent") ? 3 : p.equals("high") ? 2 : p.equals("normal") ? 1 : 0;
    }

    private int prioColor(String p) {
        return p.equals("urgent") ? R.color.err : p.equals("high") ? R.color.warn : R.color.text_secondary;
    }

    private int statusColor(JSONObject t) {
        String s = t.optString("status");
        return t.optBoolean("overdue") ? R.color.err : s.equals("in_progress") || s.equals("resolved") ? R.color.ok : s.equals("on_hold") ? R.color.warn : R.color.accent;
    }

    private String place(JSONObject t) {
        String site = t.optString("site"), addr = t.optString("address");
        return (site.isEmpty() ? "No site" : site) + (addr.isEmpty() ? "" : " · " + addr);
    }

    private String describe(JSONObject e) {
        JSONObject st = home.optJSONObject("statuses");
        String kind = e.optString("kind"), text = e.optString("text");
        if (kind.equals("status") && st != null) return "Status: " + st.optString(e.optString("to")) + (text.isEmpty() ? "" : "\n" + text);
        String head = kind.equals("finding") ? "Issue: " : kind.equals("work") ? "Work completed: " : kind.equals("pending") ? "Pending: " : kind.equals("next") ? "Next steps: " : kind.equals("parts") ? "Parts: " : kind.equals("admin") ? "Portal: " : "";
        return head + text;
    }

    private static String time(long t) {
        return t <= 0 ? "–" : DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(t));
    }
}
