package com.myraa.mobile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Holds the one photo the user attached so the next Gemini request can "see" it. */
final class ImageAttachment {
  private static volatile String staged;
  private static final Map<String, String> BY_TASK = new ConcurrentHashMap<>();
  static final int MAX_SIDE = 1280;

  /** Decodes, rotates and shrinks the picture; returns a small data URL thumbnail for the UI. */
  static String stage(Context context, Uri uri) throws Exception {
    BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
    try (InputStream in = context.getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, bounds); }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IllegalArgumentException("That file is not a picture Android can read.");
    int sample = 1; while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2;
    BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = sample;
    Bitmap bitmap;
    try (InputStream in = context.getContentResolver().openInputStream(uri)) { bitmap = BitmapFactory.decodeStream(in, null, options); }
    if (bitmap == null) throw new IllegalArgumentException("Android could not decode that picture.");
    int rotation = 0;
    try (InputStream in = context.getContentResolver().openInputStream(uri)) {
      int o = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
      rotation = o == ExifInterface.ORIENTATION_ROTATE_90 ? 90 : o == ExifInterface.ORIENTATION_ROTATE_180 ? 180 : o == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
    } catch (Exception ignored) { /* nothing to do on release of a temporary object */ }
    float scale = Math.min(1f, MAX_SIDE / (float) Math.max(bitmap.getWidth(), bitmap.getHeight()));
    Matrix matrix = new Matrix(); matrix.postScale(scale, scale); if (rotation != 0) matrix.postRotate(rotation);
    Bitmap fitted = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    staged = encode(fitted, 82);
    float t = 160f / Math.max(fitted.getWidth(), fitted.getHeight());
    Bitmap thumb = Bitmap.createScaledBitmap(fitted, Math.max(1, Math.round(fitted.getWidth() * t)), Math.max(1, Math.round(fitted.getHeight() * t)), true);
    return "data:image/jpeg;base64," + encode(thumb, 70);
  }
  private static String encode(Bitmap bitmap, int quality) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
    return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
  }
  static boolean hasStaged() { return staged != null; }
  static void clear() { staged = null; }
  /** Moves the staged photo onto a chat task. */
  static boolean claim(String taskId) { String image = staged; staged = null; if (image == null) return false; BY_TASK.put(taskId, image); return true; }
  static String take(String taskId) { return taskId == null ? null : BY_TASK.remove(taskId); }
  private ImageAttachment() {}
}
