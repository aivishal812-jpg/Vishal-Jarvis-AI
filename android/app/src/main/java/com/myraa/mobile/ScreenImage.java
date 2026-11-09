package com.myraa.mobile;

import android.graphics.Bitmap;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A screenshot of what the user is looking at, handed to the model as an image.
 *
 * The accessibility tree cannot describe canvases, PDFs, games, videos or many custom-drawn
 * screens. For those, the phone takes one screenshot (through the user's accessibility
 * permission, in memory only), shrinks it, and attaches it to the next model turn, so the model
 * reads the screen the way it reads an attached photo. Nothing is written to storage, nothing is
 * uploaded anywhere except to the model the user already sends their messages to, and the image
 * is dropped the moment it has been used.
 */
final class ScreenImage {

  private static final Map<String, String> BY_TASK = new ConcurrentHashMap<>();
  static final int MAX_WIDTH = 1080;

  /** Encodes and stores the screenshot for one task; returns false when it cannot be encoded. */
  static boolean stage(String taskId, Bitmap bitmap) {
    if (taskId == null || bitmap == null) return false;
    try {
      int width = Math.min(bitmap.getWidth(), MAX_WIDTH);
      float scale = width / (float) bitmap.getWidth();
      int height = Math.max(1, Math.round(bitmap.getHeight() * scale));
      Bitmap fitted = scale < 1f
        ? Bitmap.createScaledBitmap(bitmap, width, height, true)
        : bitmap;
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      fitted.compress(Bitmap.CompressFormat.JPEG, 72, out);
      if (fitted != bitmap) fitted.recycle();
      BY_TASK.put(taskId, Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP));
      return true;
    } catch (Throwable failure) {
      return false;
    }
  }

  static boolean has(String taskId) { return taskId != null && BY_TASK.containsKey(taskId); }

  /** Removes and returns the image, so it is sent at most once. */
  static String take(String taskId) {
    return taskId == null ? null : BY_TASK.remove(taskId);
  }

  static void clear() { BY_TASK.clear(); }

  private ScreenImage() { }
}
