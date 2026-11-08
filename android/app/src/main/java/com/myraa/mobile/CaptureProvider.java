package com.myraa.mobile;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;

/** One private, write-once file the camera app can save a photo into for Jarvis to look at. */
public final class CaptureProvider extends ContentProvider {
  static final String AUTHORITY = "com.myraa.mobile.capture";
  static File file(Context context) { File dir = new File(context.getCacheDir(), "capture"); //noinspection ResultOfMethodCallIgnored
    dir.mkdirs(); return new File(dir, "photo.jpg"); }
  static Uri uri() { return Uri.parse("content://" + AUTHORITY + "/photo.jpg"); }
  private boolean valid(Uri uri) { return uri != null && AUTHORITY.equals(uri.getAuthority()) && "/photo.jpg".equals(uri.getPath()); }
  @Override public boolean onCreate() { return true; }
  @Override public String getType(Uri uri) { return valid(uri) ? "image/jpeg" : null; }
  @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    if (!valid(uri)) throw new FileNotFoundException();
    int flags = mode != null && mode.contains("w")
      ? ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE
      : ParcelFileDescriptor.MODE_READ_ONLY;
    return ParcelFileDescriptor.open(file(getContext()), flags);
  }
  @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) { return null; }
  @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
  @Override public int delete(Uri uri, String s, String[] a) { return 0; }
  @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
