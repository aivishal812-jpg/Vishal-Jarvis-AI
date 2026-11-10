package com.myraa.mobile;

import java.util.Locale;

/**
 * The rules for interrupting Jarvis while it is still speaking.
 *
 * A wrong interruption is worse than a late one: if the phone's own voice leaks back into the
 * microphone, an eager threshold would make Jarvis cut itself off in the middle of every answer.
 * So a sentence only interrupts when it is not filler, has real words, and is not simply a repeat
 * of what Jarvis is already saying.
 */
final class BargeIn {

  /** How long to wait after speech starts before opening the ears (avoids the first syllables). */
  static final long SETTLE_MS = 700;

  private static final String[] FILLERS = {
    "ok", "okay", "hmm", "um", "uh", "হুম", "আচ্ছা", "ঠিক", "ঠিক আছে", "হ্যাঁ", "হা", "thanks",
    "thank you", "ধন্যবাদ", "yes", "no", "না"
  };

  private BargeIn() {}

  /**
   * True when {@code heard} is a real instruction that should stop the current answer.
   *
   * @param answerSoFar what Jarvis is saying right now, used to ignore its own echo
   */
  static boolean interrupts(String heard, String answerSoFar) {
    String text = normalise(heard);
    if (text.isEmpty()) return false;
    String bare = text.replaceAll("[^\\p{L}\\p{N} ]", " ").replaceAll("\\s+", " ").trim();
    if (bare.isEmpty()) return false;
    int letters = bare.replace(" ", "").length();
    String[] words = bare.split(" ");
    if (words.length < 2 && letters < 4) return false;
    if (letters < 3) return false;
    for (String filler : FILLERS) if (bare.equals(filler)) return false;
    // Jarvis's own words coming back through the speaker are not an interruption.
    String own = normalise(answerSoFar);
    if (own.length() >= 4) {
      String ownBare = own.replaceAll("[^\\p{L}\\p{N} ]", " ").replaceAll("\\s+", " ").trim();
      if (ownBare.contains(bare)) return false;
    }
    return true;
  }

  static boolean interrupts(String heard) { return interrupts(heard, ""); }

  private static String normalise(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
  }
}
