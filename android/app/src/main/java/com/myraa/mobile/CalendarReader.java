package com.myraa.mobile;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Read-only view of upcoming calendar events (needs READ_CALENDAR). */
final class CalendarReader {
  static JSONArray upcoming(Context context, int days, int limit) {
    Calendar start = Calendar.getInstance();
    start.set(Calendar.HOUR_OF_DAY, 0); start.set(Calendar.MINUTE, 0); start.set(Calendar.SECOND, 0); start.set(Calendar.MILLISECOND, 0);
    long begin = Math.min(start.getTimeInMillis(), System.currentTimeMillis());
    long end = start.getTimeInMillis() + Math.max(1, Math.min(31, days)) * 86_400_000L;
    Uri.Builder uri = CalendarContract.Instances.CONTENT_URI.buildUpon();
    ContentUris.appendId(uri, begin); ContentUris.appendId(uri, end);
    String[] columns = { CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
      CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY };
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
        out.put(item);
      }
    } catch (Exception ignored) { }
    return out;
  }
  private CalendarReader() {}
}
