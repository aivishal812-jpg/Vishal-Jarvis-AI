package com.myraa.mobile;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;

/** Only invoked by an explicit export/share tap. Uses no storage permission. */
final class TranscriptExport {
  static File create(Context context) throws Exception { return write(context, TranscriptProvider.file(context)); }
  static File createShared(Context context) throws Exception {
    File directory = TranscriptProvider.directory(context);
    File[] old = directory.listFiles();
    if (old != null) for (File file : old) {
      if (file.getName().startsWith("transcript-") && System.currentTimeMillis() - file.lastModified() > 24L * 60 * 60 * 1000) file.delete();
    }
    return write(context, new File(directory, "transcript-" + java.util.UUID.randomUUID() + ".txt"));
  }
  private static File write(Context context, File file) throws Exception {
    JSONArray history = AiTaskStore.get(context).history();
    if (history.length() == 0) throw new IllegalStateException("There is no conversation to export yet.");
    StringBuilder text = new StringBuilder("Vishal Jarvis — conversation\n\n");
    DateFormat dates = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
    for (int i = 0; i < history.length(); i++) {
      JSONObject turn = history.optJSONObject(i); if (turn == null) continue;
      String role = "user".equals(turn.optString("role")) ? "You" : "Vishal Jarvis";
      text.append(role);
      long at = turn.optLong("at", 0); if (at > 0) text.append(" (").append(dates.format(new Date(at))).append(")");
      text.append(":\n").append(turn.optString("text", "")).append("\n\n");
    }
    if (!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs()) throw new java.io.IOException("Could not create export");
    try (FileOutputStream out = new FileOutputStream(file)) { out.write(text.toString().getBytes(StandardCharsets.UTF_8)); }
    return file;
  }
  private TranscriptExport() {}
}
