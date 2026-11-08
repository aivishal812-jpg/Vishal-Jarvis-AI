package com.myraa.mobile;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;
import java.util.Iterator;

/** Native settings screen for the Jarvis Pro features (wake word, lock, permissions, backup, crash log). */
public final class ProSettingsActivity extends Activity {
  private static final int MIC_REQUEST = 201, CAL_REQUEST = 202, EXPORT_REQUEST = 203, IMPORT_REQUEST = 204;
  private static final String[] BACKUP_KEYS = {"memory", "language", "model", "liveModel", "quality", "spokenReplies", "textScale",
    "contactAliasesV1", JarvisPrefs.WAKE_WORD, JarvisPrefs.SECURE_ACTIONS, JarvisPrefs.FALLBACK_MODEL, JarvisPrefs.HOME_CITY,
    NoteStore.KEY, ReminderStore.KEY};
  private static final int BG = Color.rgb(7, 12, 22), CARD = Color.rgb(16, 26, 44), ACCENT = Color.rgb(56, 189, 248), TEXT = Color.rgb(226, 236, 248), MUTED = Color.rgb(140, 160, 186);
  private LinearLayout root;

  @Override protected void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().setStatusBarColor(BG);
    getWindow().setNavigationBarColor(BG);
  }

  @Override protected void onResume() { super.onResume(); render(); }

  private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

  private void render() {
    ScrollView scroll = new ScrollView(this);
    scroll.setBackgroundColor(BG);
    root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(18), dp(26), dp(18), dp(40));
    scroll.addView(root);
    setContentView(scroll);

    TextView title = text("Jarvis Pro settings", 24, TEXT); title.setTypeface(Typeface.DEFAULT_BOLD); root.addView(title);
    root.addView(text("Version " + BuildInfo.VERSION + ". Everything here stays on this phone.", 13, MUTED));

    SharedPreferences prefs = JarvisPrefs.get(this);

    LinearLayout wake = card("Wake word: \"Hey Jarvis\"", "Listens in the background, even with the screen locked. Say \"Hey Jarvis\" (or \"জার্ভিস\" / \"Hey service\") with your request, e.g. \"Hey Jarvis, torch jalao\". A small card answers in one short sentence and closes without opening the app; when locked, tasks like opening apps ask for your fingerprint first. It pauses by itself whenever the keyboard's voice typing (or any other app) uses the microphone. The notification shows what it heard. Uses more battery. The repeating microphone beep is silenced (sounds are muted for about a second each time the mic restarts). Status: " + WakeWordService.lastStatus);
    wake.addView(toggle("Listen for \"Hey Jarvis\"", JarvisPrefs.wakeWord(this), on -> {
      prefs.edit().putBoolean(JarvisPrefs.WAKE_WORD, on).apply();
      if (on) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED || (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)) {
          requestPermissions(Build.VERSION.SDK_INT >= 33 ? new String[]{Manifest.permission.RECORD_AUDIO, "android.permission.POST_NOTIFICATIONS"} : new String[]{Manifest.permission.RECORD_AUDIO}, MIC_REQUEST);
        } else WakeWordService.start(this);
      } else WakeWordService.stop(this);
      root.postDelayed(this::render, 600);
    }));
    wake.addView(toggle("Silence the microphone beep", BeepMuter.enabled(this), on -> { prefs.edit().putBoolean(JarvisPrefs.SILENT_MIC, on).apply(); if (!on) BeepMuter.unmuteNow(); }));
    wake.addView(toggle("Answer on the lock screen (phone stays locked)", JarvisPrefs.lockScreen(this), on -> prefs.edit().putBoolean(JarvisPrefs.LOCK_SCREEN, on).apply()));
    if (Build.VERSION.SDK_INT >= 34) {
      android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
      boolean full = nm != null && nm.canUseFullScreenIntent();
      wake.addView(button((full ? "On: " : "Off: ") + "Full-screen alerts (wake the locked screen)",
        () -> open(new Intent("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT", Uri.parse("package:" + getPackageName())))));
    }
    wake.addView(button((Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(this) ? "On: " : "Off: ") + "Open Jarvis instantly when called (display over other apps)",
      () -> open(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())))));

    LinearLayout lock = card("Security lock", SecureGate.available(this) ? "Ask for fingerprint, face or screen lock before Jarvis places a call or sends a message." : "Set a screen lock on this phone (Android 9+) to use this.");
    lock.addView(toggle("Lock calls and messages", JarvisPrefs.secureActions(this), on -> prefs.edit().putBoolean(JarvisPrefs.SECURE_ACTIONS, on).apply()));

    LinearLayout perms = card("Permissions", "Turn these on for the new features to work fully.");
    perms.addView(button((JarvisNotificationListener.enabled(this) ? "On: " : "Off: ") + "Notification reading", () -> open(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))));
    boolean cal = checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    perms.addView(button((cal ? "On: " : "Off: ") + "Calendar reading", () -> { if (!cal) requestPermissions(new String[]{Manifest.permission.READ_CALENDAR}, CAL_REQUEST); else open(appDetails()); }));
    if (Build.VERSION.SDK_INT >= 31) perms.addView(button((ReminderScheduler.exactAllowed(this) ? "On: " : "Off: ") + "Exact reminder time", () -> open(new Intent("android.settings.REQUEST_SCHEDULE_EXACT_ALARM", Uri.parse("package:" + getPackageName())))));
    PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
    boolean unrestricted = power != null && power.isIgnoringBatteryOptimizations(getPackageName());
    perms.addView(button((unrestricted ? "On: " : "Off: ") + "Run without battery limits", () -> open(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())))));
    perms.addView(button("Make Jarvis the default assistant", () -> open(new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))));
    perms.addView(button("All app permissions", () -> open(appDetails())));

    LinearLayout prefsCard = card("Preferences", "Home city is used for weather and the daily briefing. The backup model is used if the main Gemini model is busy.");
    prefsCard.addView(field("Home city (e.g. Kolkata)", JarvisPrefs.homeCity(this), v -> prefs.edit().putString(JarvisPrefs.HOME_CITY, v.length() > 60 ? v.substring(0, 60) : v).apply()));
    prefsCard.addView(field("Backup model", JarvisPrefs.fallbackModel(this), v -> {
      if (v.matches("[A-Za-z0-9._-]{0,100}")) prefs.edit().putString(JarvisPrefs.FALLBACK_MODEL, v).apply();
      else toast("Model names can only use letters, numbers, dots and dashes.");
    }));

    JSONArray reminders = ReminderStore.all(this);
    LinearLayout rem = card("Reminders (" + reminders.length() + ")", reminders.length() == 0 ? "Say \"remind me in 10 minutes to drink water\"." : "Tap a reminder to delete it.");
    DateFormat format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
    for (int i = 0; i < reminders.length() && i < 50; i++) {
      JSONObject item = reminders.optJSONObject(i); if (item == null) continue;
      String id = item.optString("id");
      String repeat = item.optString("repeat", "none");
      rem.addView(button(format.format(new Date(item.optLong("at"))) + ("none".equals(repeat) ? "" : " (" + repeat + ")") + "\n" + item.optString("text"), () ->
        new AlertDialog.Builder(this).setTitle("Delete this reminder?").setMessage(item.optString("text"))
          .setPositiveButton("Delete", (d, w) -> { ReminderScheduler.cancel(this, id); ReminderStore.remove(this, id); render(); })
          .setNegativeButton("Keep", null).show()));
    }

    LinearLayout notes = card("Notes (" + NoteStore.all(this).length() + ")", "Say \"note this down…\" to add one, or ask Jarvis to list your notes.");
    if (NoteStore.all(this).length() > 0) notes.addView(button("Delete all notes", () -> new AlertDialog.Builder(this).setTitle("Delete all notes?")
      .setPositiveButton("Delete", (d, w) -> { prefs.edit().putString(NoteStore.KEY, "[]").apply(); render(); }).setNegativeButton("Cancel", null).show()));

    LinearLayout backup = card("Backup", "Save memory, notes, reminders, contact nicknames and settings to a file, or restore them. Your Gemini API key is never included.");
    backup.addView(button("Export backup", () -> {
      Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE, "vishal-jarvis-backup.json");
      try { startActivityForResult(intent, EXPORT_REQUEST); } catch (RuntimeException e) { toast("No file app is available."); }
    }));
    backup.addView(button("Restore from backup", () -> {
      Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
      try { startActivityForResult(intent, IMPORT_REQUEST); } catch (RuntimeException e) { toast("No file app is available."); }
    }));

    String crash = CrashLog.read(this);
    LinearLayout crashCard = card("Crash log", crash.isEmpty() ? "No crashes recorded." : "Jarvis recorded a crash. Share it with the developer to help fix it.");
    if (!crash.isEmpty()) {
      crashCard.addView(button("View crash log", () -> new AlertDialog.Builder(this).setTitle("Crash log").setMessage(crash.length() > 6000 ? crash.substring(crash.length() - 6000) : crash).setPositiveButton("Close", null).show()));
      crashCard.addView(button("Share crash log", () -> open(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Vishal Jarvis " + BuildInfo.VERSION + " crash log").putExtra(Intent.EXTRA_TEXT, crash), "Share crash log"))));
      crashCard.addView(button("Clear crash log", () -> { CrashLog.clear(this); render(); }));
    }

    root.addView(button("Back to Jarvis", this::finish));
  }

  private Intent appDetails() { return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())); }
  private void open(Intent intent) { try { startActivity(intent); } catch (RuntimeException e) { try { startActivity(appDetails()); } catch (RuntimeException ignored) { toast("This phone does not offer that setting."); } } }
  private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }

  private TextView text(String value, int sp, int color) {
    TextView view = new TextView(this); view.setText(value); view.setTextSize(sp); view.setTextColor(color);
    view.setPadding(0, dp(4), 0, dp(4)); return view;
  }
  private LinearLayout card(String title, String body) {
    LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
    card.setPadding(dp(16), dp(14), dp(16), dp(14));
    GradientDrawable bg = new GradientDrawable(); bg.setColor(CARD); bg.setCornerRadius(dp(16)); bg.setStroke(dp(1), Color.argb(70, 56, 189, 248));
    card.setBackground(bg);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    params.topMargin = dp(14); card.setLayoutParams(params);
    TextView head = text(title, 17, TEXT); head.setTypeface(Typeface.DEFAULT_BOLD); card.addView(head);
    card.addView(text(body, 13, MUTED));
    root.addView(card); return card;
  }
  interface Flag { void set(boolean on); }
  interface Value { void set(String value); }
  private View toggle(String label, boolean value, Flag flag) {
    Switch view = new Switch(this); view.setText(label); view.setTextColor(TEXT); view.setTextSize(15); view.setChecked(value);
    view.setPadding(0, dp(8), 0, dp(8)); view.setOnCheckedChangeListener((b, on) -> flag.set(on)); return view;
  }
  private View button(String label, Runnable action) {
    Button view = new Button(this); view.setText(label); view.setAllCaps(false); view.setTextColor(TEXT); view.setTextSize(14);
    view.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.argb(40, 56, 189, 248)); bg.setCornerRadius(dp(12)); view.setBackground(bg);
    view.setPadding(dp(14), dp(10), dp(14), dp(10));
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    params.topMargin = dp(8); view.setLayoutParams(params);
    view.setOnClickListener(v -> action.run()); return view;
  }
  private View field(String hint, String value, Value onSave) {
    LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
    EditText edit = new EditText(this); edit.setHint(hint); edit.setText(value); edit.setTextColor(TEXT); edit.setHintTextColor(MUTED);
    edit.setSingleLine(true); edit.setInputType(InputType.TYPE_CLASS_TEXT);
    row.addView(edit, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    Button save = new Button(this); save.setText("Save"); save.setAllCaps(false); save.setTextColor(BG);
    GradientDrawable bg = new GradientDrawable(); bg.setColor(ACCENT); bg.setCornerRadius(dp(12)); save.setBackground(bg);
    save.setOnClickListener(v -> { onSave.set(edit.getText().toString().trim()); toast("Saved"); });
    row.addView(save, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    return row;
  }

  @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(request, permissions, results);
    if (request == MIC_REQUEST) {
      if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) WakeWordService.start(this);
      else { JarvisPrefs.get(this).edit().putBoolean(JarvisPrefs.WAKE_WORD, false).apply(); toast("The wake word needs microphone permission."); }
    }
    render();
  }

  @Override protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (result != RESULT_OK || data == null || data.getData() == null) return;
    Uri uri = data.getData();
    if (request == EXPORT_REQUEST) {
      try (OutputStream out = getContentResolver().openOutputStream(uri)) {
        if (out == null) throw new IllegalStateException();
        out.write(exportBackup().toString(2).getBytes(StandardCharsets.UTF_8));
        toast("Backup saved.");
      } catch (Exception e) { toast("The backup could not be saved."); }
    } else if (request == IMPORT_REQUEST) {
      try (InputStream in = getContentResolver().openInputStream(uri); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
        if (in == null) throw new IllegalStateException();
        byte[] chunk = new byte[8192]; int n;
        while ((n = in.read(chunk)) > 0) { buffer.write(chunk, 0, n); if (buffer.size() > 2_000_000) throw new IllegalArgumentException(); }
        int restored = importBackup(new JSONObject(new String(buffer.toByteArray(), StandardCharsets.UTF_8)));
        ReminderScheduler.rescheduleAll(this);
        toast("Restored " + restored + " items. Restart Jarvis to see everything.");
        render();
      } catch (Exception e) { toast("That file is not a Vishal Jarvis backup."); }
    }
  }

  private JSONObject exportBackup() throws Exception {
    SharedPreferences prefs = JarvisPrefs.get(this);
    JSONObject values = new JSONObject();
    for (String key : BACKUP_KEYS) {
      Object value = prefs.getAll().get(key);
      if (value instanceof String || value instanceof Boolean || value instanceof Integer || value instanceof Long) values.put(key, value);
    }
    return new JSONObject().put("app", "vishal-jarvis").put("format", 1).put("version", BuildInfo.VERSION).put("created", System.currentTimeMillis()).put("values", values);
  }

  private int importBackup(JSONObject backup) {
    if (!"vishal-jarvis".equals(backup.optString("app"))) throw new IllegalArgumentException();
    JSONObject values = backup.optJSONObject("values");
    if (values == null) throw new IllegalArgumentException();
    SharedPreferences.Editor editor = JarvisPrefs.get(this).edit();
    int count = 0;
    for (Iterator<String> keys = values.keys(); keys.hasNext();) {
      String key = keys.next(); boolean allowed = false;
      for (String k : BACKUP_KEYS) if (k.equals(key)) allowed = true;
      if (!allowed) continue;
      Object value = values.opt(key);
      if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
      else if (value instanceof Integer) editor.putInt(key, (Integer) value);
      else if (value instanceof Long) editor.putLong(key, (Long) value);
      else if (value instanceof String) {
        String s = (String) value; if (s.length() > 500_000) continue;
        editor.putString(key, s);
      } else continue;
      count++;
    }
    editor.commit();
    return count;
  }
}
