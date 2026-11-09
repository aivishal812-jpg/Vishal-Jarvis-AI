package com.myraa.mobile;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONObject;

/**
 * Text recognition from a picture, on the device (ML Kit).
 *
 * Chosen as one of the three extra capabilities because the model already receives the photo,
 * yet for small print, bills, forms and screenshots the recognised text is what the user
 * actually asked for, and it works with no network. The recogniser is the bundled Latin
 * script model; Bengali or Hindi script is reported honestly as unavailable rather than
 * returning nonsense.
 */
final class OcrTool {

  interface Callback { void done(JSONObject result); }

  static void read(android.content.Context context, Uri uri, String language, Callback callback) {
    if (uri == null) {
      callback.done(AiAgent.json("status", "invalid",
        "message", "Attach a photo or screenshot first (the photo button next to the chat box), then ask again."));
      return;
    }
    String script = language == null ? "" : language.toLowerCase(java.util.Locale.ROOT);
    if (script.startsWith("bn") || script.startsWith("hi")) {
      callback.done(AiAgent.json("status", "unavailable",
        "message", "The built-in recogniser only reads Latin script on this phone, so Bengali or Hindi text in a picture cannot be extracted reliably here. I can still look at the image itself and describe it."));
      return;
    }
    BitmapFactory.Options bounds = new BitmapFactory.Options();
    bounds.inJustDecodeBounds = true;
    try (java.io.InputStream in = context.getContentResolver().openInputStream(uri)) {
      BitmapFactory.decodeStream(in, null, bounds);
    } catch (Exception failure) {
      callback.done(AiAgent.json("status", "failed", "message", "That picture could not be opened."));
      return;
    }
    if (bounds.outWidth <= 0) {
      callback.done(AiAgent.json("status", "failed", "message", "That file is not a picture Android can read."));
      return;
    }
    int sample = 1;
    while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2000) sample *= 2;
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inSampleSize = sample;
    Bitmap bitmap;
    try (java.io.InputStream in = context.getContentResolver().openInputStream(uri)) {
      bitmap = BitmapFactory.decodeStream(in, null, options);
    } catch (Exception failure) {
      callback.done(AiAgent.json("status", "failed", "message", "That picture could not be decoded."));
      return;
    }
    if (bitmap == null) {
      callback.done(AiAgent.json("status", "failed", "message", "That picture could not be decoded."));
      return;
    }
    TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    recognizer.process(InputImage.fromBitmap(bitmap, 0))
      .addOnSuccessListener(text -> {
        String value = text == null ? "" : text.getText();
        recognizer.close();
        if (value.trim().isEmpty()) {
          callback.done(AiAgent.json("status", "not_found", "message", "No readable text was found in that picture."));
          return;
        }
        String capped = value.length() > 8000 ? value.substring(0, 8000) + "…" : value;
        callback.done(AiAgent.json("status", "info", "message", capped, "text", capped,
          "blocks", text.getTextBlocks().size()));
      })
      .addOnFailureListener(failure -> {
        recognizer.close();
        callback.done(AiAgent.json("status", "failed", "message", "Text recognition failed on that picture."));
      });
  }

  private OcrTool() { }
}
