package com.myraa.mobile;

import android.graphics.Bitmap;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

/**
 * On-device text recognition for a screenshot taken by {@link UiAutomationService}.
 *
 * The picture is recognised in memory and never stored or sent anywhere; only the resulting
 * text is handed back. If the recogniser fails, the callback receives an empty string and the
 * caller falls back to the accessibility tree.
 */
final class ScreenOcr {
  interface Callback { void done(String text); }

  static void recognise(Bitmap bitmap, Callback callback) {
    if (bitmap == null) { callback.done(""); return; }
    TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    recognizer.process(InputImage.fromBitmap(bitmap, 0))
      .addOnSuccessListener(text -> {
        recognizer.close();
        callback.done(text == null ? "" : text.getText());
      })
      .addOnFailureListener(failure -> {
        recognizer.close();
        callback.done("");
      });
  }

  private ScreenOcr() { }
}
