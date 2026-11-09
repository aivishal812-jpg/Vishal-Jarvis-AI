package com.myraa.mobile;

import android.app.Activity;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Events;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Upcoming calendar events (needs READ_CALENDAR) and event deletion (needs WRITE_CALENDAR). */
final class CalendarReader {

  static JSONArray upcoming(Context context, int days, int limit) {
    return upcoming(context, days, limit, false);
  }

  /** @param withId include the event id so the user can name one event to delete later. */
  static JSONArray upcoming(Context context, int days, int limit, boolean withId) {
    Calendar start = Calendar.getInstance();
    start.set(Calendar.HOUR_OF_DAY, 0); start.set(Calendar.MINUTE, 0); start.set(Calendar.SECOND, 0); start.set(Calendar.MILLISECOND, 0);
    long begin = Math.min(start.getTimeInMillis(), System.currentTimeMillis());
    long end = start.getTimeInMillis() + Math.max(1, Math.min(31, days)) * 86_400_000L;
    Uri.Builder uri = CalendarContract.Instances.CONTENT_URI.buildUpon();
    ContentUris.appendId(uri, begin); ContentUris.appendId(uri, end);
    String[] columns = { CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
      CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_ID };
    JSONArray out = new JSONArray();
    SimpleDateFormat format = new SimpleDateFormat("EEE d MMM, HH:mm", Locale.US);
    try (Cursor c = context.getContentResolver().query(uri.build(), columns, null, null, CalendarContract.Instances.BEGIN + " ASC")) {
      while (c != null && c.moveToNext() && out.length() < limit) {
        long b = c.getLong(1), e = c.getLong(2);
        if (e < System.currentTimeMillis() && c.getInt(4) == 0) continue;
        JSONObject item = new JSONObject();
        item.put("title", c.getString(0) == null ? "(no title)" : c.getString(0)).put("begin", b).put("end", e)
          .put("when", c.getInt(4) == 1 ? new SimpleDateFormat("EEE d MMM", Locale.US).format(new Date(b)) + " (all day)" : format.format(new Date(b)))
          .put("location", c.getString(3) == null ? "" : c.getString(3));
        if (withId) item.put("id", c.getLong(5));
        out.put(item);
      }
    } catch (Exception ignored) { }
    return out;
  }

  /** One distinct event (a repeating event appears once) with the instances that were found. */
  static final class Target {
    final long id; final String title; long firstBegin, lastEnd; int instances;
    Target(long id, String title, long begin, long end) { this.id = id; this.title = title; this.firstBegin = begin; this.lastEnd = end; this.instances = 1; }
    String when() {
      SimpleDateFormat format = new SimpleDateFormat("EEE d MMM, HH:mm", Locale.US);
      return format.format(new Date(firstBegin));
    }
  }

  /** Unique events in the window, newest-first is not needed: the caller ranks them. */
  static List<Target> upcomingTargets(Context context, int days, int limit) {
    Map<Long, Target> found = new LinkedHashMap<>();
    Calendar start = Calendar.getInstance();
    start.set(Calendar.HOUR_OF_DAY, 0); start.set(Calendar.MINUTE, 0); start.set(Calendar.SECOND, 0); start.set(Calendar.MILLISECOND, 0);
    long begin = Math.min(start.getTimeInMillis(), System.currentTimeMillis());
    long end = start.getTimeInMillis() + Math.max(1, Math.min(31, days)) * 86_400_000L;
    Uri.Builder uri = CalendarContract.Instances.CONTENT_URI.buildUpon();
    ContentUris.appendId(uri, begin); ContentUris.appendId(uri, end);
    String[] columns = { CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE,
      CalendarContract.Instances.BEGIN, CalendarContract.Instances.END };
    try (Cursor c = context.getContentResolver().query(uri.build(), columns, null, null, CalendarContract.Instances.BEGIN + " ASC")) {
      while (c != null && c.moveToNext() && found.size() < Math.max(1, limit)) {
        long id = c.getLong(0);
        Target existing = found.get(id);
        if (existing == null) found.put(id, new Target(id, c.getString(1) == null ? "(no title)" : c.getString(1), c.getLong(2), c.getLong(3)));
        else { existing.instances++; existing.lastEnd = Math.max(existing.lastEnd, c.getLong(3)); }
      }
    } catch (Exception ignored) { }
    return new ArrayList<>(found.values());
  }

  /**
   * Deletes event rows with the user's own calendar permission.
   * Returns how many events were removed; throws SecurityException when WRITE_CALENDAR is missing,
   * and IllegalStateException when Android refused (for example because the row is read-only).
   */
  static int deleteEvents(Context context, List<Long> ids) {
    if (ids == null || ids.isEmpty()) return 0;
    int removed = 0;
    for (Long id : ids) {
      if (id == null || id <= 0) continue;
      Uri row = ContentUris.withAppendedId(Events.CONTENT_URI, id);
      try {
        if (context.getContentResolver().delete(row, null, null) > 0) removed++;
      } catch (SecurityException denied) {
        throw denied;
      } catch (RuntimeException broken) {
        // Keep going: one read-only (holiday / birthday) event must not block the others.
      }
    }
    return removed;
  }

  /** True when WRITE_CALENDAR is missing; READ_CALENDAR is implied by the delete path. */
  static boolean needsWrite(Activity activity) {
    return activity.checkSelfPermission(android.Manifest.permission.WRITE_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED;
  }

  private CalendarReader() { }
}
