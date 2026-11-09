package com.myraa.mobile;

import org.json.JSONObject;

/**
 * What Jarvis should say while a call is happening, and how far it is allowed to listen.
 *
 * The rules live here, apart from Android, so they can be tested:
 *  - it only ever answers the other person's words; its own words are removed from the transcript
 *  - it never answers the same sentence twice
 *  - it stays silent until it has a whole sentence, so it does not talk over someone mid-word
 *  - it refuses obviously private requests (OTP, PIN, password, card number), because a live
 *    answer would leak them out loud to whoever is on the line
 */
final class CallCoach {

  /** How Jarvis is allowed to take part in the call. */
  enum Mode {
    /** Nothing but the floating card; the user reads the suggestion. */
    OFF("off"),
    /** Never speaks; writes the suggested answer on the card. */
    SUGGEST("suggest"),
    /** Speaks into the call, out loud through the speaker. */
    SPEAK("speak");

    final String key;
    Mode(String key) { this.key = key; }

    static Mode of(String value) {
      if (value == null) return SUGGEST;
      for (Mode mode : values()) if (mode.key.equals(value)) return mode;
      return SUGGEST;
    }
  }

  static final String INSTRUCTION =
    "You are Vishal Jarvis, sitting next to Vishal during a live phone call. "
    + "The transcript may contain both sides mixed together: Vishal is 'Vishal:', the other person is "
    + "'Caller:'. The other person usually cannot hear you; only Vishal can (or, in speak mode, your "
    + "voice goes into the call). "
    + "Reply with the single next thing Vishal should say, spoken aloud, as Vishal would say it. "
    + "Use natural West Bengal Bengali (or the language the caller is using), at most two short "
    + "sentences, no markdown, no lists, no emojis. Do not repeat what Vishal already said. "
    + "If the caller asked a question and the answer is not in the transcript, say what to ask them "
    + "back or say that you do not have that information; never invent facts, prices, addresses or "
    + "numbers. Never read out or confirm any OTP, PIN, password, card or account number.";

  /** Words that must never be spoken back into a call. */
  private static final String[] SECRETS = {
    "otp", "one time password", "pin", "password", "cvv", "card number", "upi pin",
    "ওটিপি", "পিন", "পাসওয়ার্ড", "পাসওয়ার্ড", "কার্ড নম্বর"
  };

  private CallCoach() {}

  static boolean needsHelp(String sentence) {
    if (sentence == null) return false;
    String text = sentence.trim();
    if (text.length() < 4) return false;
    String lower = text.toLowerCase(java.util.Locale.ROOT);
    return lower.endsWith("?") || lower.endsWith("؟") || text.endsWith("?")
      || lower.contains("কি") || lower.contains("কী") || lower.contains("কেন")
      || lower.contains("কোথায়") || lower.contains("কোথায়") || lower.contains("কত")
      || lower.contains("কবে") || lower.contains("কোন") || lower.contains("কোনো")
      || lower.contains("what") || lower.contains("why") || lower.contains("where")
      || lower.contains("when") || lower.contains("how") || lower.contains("which")
      || lower.contains("can you") || lower.contains("could you") || lower.contains("do you");
  }

  /** True when the sentence is a request for a secret and must not be answered out loud. */
  static boolean forbidden(String sentence) {
    if (sentence == null) return false;
    String lower = sentence.toLowerCase(java.util.Locale.ROOT);
    for (String word : SECRETS) if (lower.contains(word)) return true;
    return false;
  }

  /**
   * Removes Jarvis's own words from the transcript, so it never answers itself and never repeats
   * itself. Each line is "Speaker: text".
   */
  static String withoutOwnWords(String transcript, String ownName) {
    if (transcript == null) return "";
    StringBuilder kept = new StringBuilder();
    for (String line : transcript.split("\n")) {
      String trimmed = line.trim();
      if (trimmed.isEmpty()) continue;
      if (ownName != null && !ownName.isEmpty() && trimmed.startsWith(ownName + ":")) continue;
      kept.append(trimmed).append('\n');
    }
    return kept.toString().trim();
  }

  /** The last complete sentence, or empty when the line is still unfinished. */
  static String lastSentence(String text) {
    if (text == null) return "";
    String trimmed = text.trim();
    if (trimmed.isEmpty()) return "";
    int end = -1;
    for (int i = trimmed.length() - 1; i >= 0; i--) {
      char c = trimmed.charAt(i);
      if (c == '.' || c == '?' || c == '!' || c == '।' || c == '\n') { end = i; break; }
    }
    if (end < 0) return "";
    int start = 0;
    for (int i = end - 1; i >= 0; i--) {
      char c = trimmed.charAt(i);
      if (c == '.' || c == '?' || c == '!' || c == '।' || c == '\n') { start = i + 1; break; }
    }
    String sentence = trimmed.substring(start, end + 1).trim();
    return sentence.length() > 400 ? sentence.substring(sentence.length() - 400) : sentence;
  }

  /** True when this sentence has already been answered in this call. */
  static boolean alreadyAnswered(String[] answered, String sentence) {
    if (answered == null || sentence == null) return false;
    String wanted = normalise(sentence);
    for (String previous : answered) if (wanted.equals(normalise(previous))) return true;
    return false;
  }

  static String normalise(String value) {
    return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
  }

  /** The prompt sent to the model for one call turn. */
  static String conversation(String transcript, boolean heardUser) {
    return "## Call transcript so far\n" + (transcript == null ? "" : transcript.trim())
      + "\n\n## Now\n" + (heardUser ? "Vishal just finished a sentence. Decide whether anything needs to be said next."
      : "The caller just finished a sentence. Answer it for Vishal.")
      + "\nReply with only the words to say.";
  }

  /** A one-line note for the card; never contains the transcript. */
  static String statusLine(Mode mode) {
    switch (mode) {
      case SPEAK: return "Listening to the call · answering out loud";
      case SUGGEST: return "Listening (speakerphone) · suggestions on the card";
      default: return "Call assistant is off for this call";
    }
  }

  static JSONObject describe(Mode mode) {
    return AiAgent.json("mode", mode.key, "speaks", mode == Mode.SPEAK, "line", statusLine(mode));
  }
}
