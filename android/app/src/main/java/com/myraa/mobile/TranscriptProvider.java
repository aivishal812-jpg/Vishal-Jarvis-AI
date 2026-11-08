package com.myraa.mobile;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** Narrow, read-only grant for the one user-created transcript in the private cache. */
public final class TranscriptProvider extends ContentProvider {
  static final String AUTHORITY = "com.myraa.mobile.transcripts";
  static File directory(android.content.Context context) { return new File(context.getCacheDir(), "exports"); }
  static File file(android.content.Context context) { return new File(directory(context), "transcript.txt"); }
  static Uri uriFor(File file) { return Uri.parse("content://" + AUTHORITY + "/" + file.getName()); }
  private boolean valid(Uri uri) {
    return uri != null && AUTHORITY.equals(uri.getAuthority()) && uri.getPath() != null
      && uri.getPath().matches("/transcript-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.txt");
  }
  private File sharedFile(Uri uri) { return new File(directory(getContext()), uri.getLastPathSegment()); }
  @Override public boolean onCreate() { return true; }
  @Override public String getType(Uri uri) { return valid(uri) ? "text/plain" : null; }
  @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
    if (!valid(uri)) return null;
    String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
    MatrixCursor cursor = new MatrixCursor(columns); Object[] row = new Object[columns.length];
    for (int i = 0; i < columns.length; i++) {
      if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = "Vishal-Jarvis-conversation.txt";
      else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = sharedFile(uri).length();
    }
    cursor.addRow(row); return cursor;
  }
  @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    if (!valid(uri) || !"r".equals(mode)) throw new FileNotFoundException("Read-only transcript");
    return ParcelFileDescriptor.open(sharedFile(uri), ParcelFileDescriptor.MODE_READ_ONLY);
  }
  @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
  @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
  @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
