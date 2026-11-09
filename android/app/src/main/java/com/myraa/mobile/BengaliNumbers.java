package com.myraa.mobile;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bengali / Banglish numbers, clock times and durations, so what the user says is what the
 * phone does.
 *
 * The speech recogniser returns Bengali digits ("৮টা ৩০"), Bengali words ("সাড়ে সাতটা",
 * "দশ মিনিট পর") and Banglish ("sat ta baje") mixed with English. Neither the model nor the
 * clock apps handle that consistently, so this class normalises it deterministically and is
 * covered by JVM unit tests.
 */
final class BengaliNumbers {

  private static final char[] BENGALI_DIGITS = {'০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯'};
  private static final char[] DEVANAGARI_DIGITS = {'०', '१', '२', '३', '४', '५', '६', '७', '८', '९'};

  private static final Map<String, Integer> WORDS = new LinkedHashMap<>();
  static {
    WORDS.put("শূন্য", 0); WORDS.put("শুন্য", 0); WORDS.put("এক", 1); WORDS.put("দুই", 2); WORDS.put("দুটো", 2); WORDS.put("দুইটা", 2);
    WORDS.put("তিন", 3); WORDS.put("চার", 4); WORDS.put("পাঁচ", 5); WORDS.put("পাচ", 5); WORDS.put("ছয়", 6); WORDS.put("ছয়টা", 6);
    WORDS.put("সাত", 7); WORDS.put("আট", 8); WORDS.put("নয়", 9); WORDS.put("নৌ", 9); WORDS.put("দশ", 10);
    WORDS.put("এগারো", 11); WORDS.put("বারো", 12); WORDS.put("তেরো", 13); WORDS.put("চৌদ্দ", 14); WORDS.put("পনেরো", 15);
    WORDS.put("ষোল", 16); WORDS.put("সতেরো", 17); WORDS.put("আঠারো", 18); WORDS.put("উনিশ", 19); WORDS.put("বিশ", 20);
    WORDS.put("একুশ", 21); WORDS.put("বাইশ", 22); WORDS.put("তেইশ", 23); WORDS.put("চব্বিশ", 24);
    WORDS.put("পঁচিশ", 25); WORDS.put("ছাব্বিশ", 26); WORDS.put("সাতাশ", 27); WORDS.put("আটাশ", 28);
    WORDS.put("উনত্রিশ", 29); WORDS.put("ত্রিশ", 30); WORDS.put("চল্লিশ", 40); WORDS.put("পঞ্চাশ", 50);
    WORDS.put("ষাট", 60); WORDS.put("সত্তর", 70); WORDS.put("আশি", 80); WORDS.put("নব্বই", 90);
    WORDS.put("শো", 100); WORDS.put("একশো", 100); WORDS.put("একশ", 100); WORDS.put("হাজার", 1000);
    WORDS.put("आठ", 8); WORDS.put("सात", 7); WORDS.put("दस", 10); WORDS.put("दो", 2); WORDS.put("एक", 1);
  }

  private static final Map<String, Integer> BANGLISH = new LinkedHashMap<>();
  static {
    BANGLISH.put("ek", 1); BANGLISH.put("dui", 2); BANGLISH.put("tin", 3); BANGLISH.put("char", 4);
    BANGLISH.put("panch", 5); BANGLISH.put("poch", 5); BANGLISH.put("choy", 6); BANGLISH.put("sat", 7);
    BANGLISH.put("aat", 8); BANGLISH.put("ath", 8); BANGLISH.put("noy", 9); BANGLISH.put("dosh", 10);
  }

  /** Words that make an hour PM, matched as whole tokens (\b is unreliable around Bengali). */
  private static final java.util.Set<String> AFTERNOON_WORDS = new java.util.HashSet<>(java.util.Arrays.asList(
    "pm", "p.m", "p.m.", "রাত", "রাতে", "রাত্রি", "সন্ধ্যা", "সন্ধ্যায়", "সন্ধ্যে", "বিকেল", "বিকাল", "বিকেলে",
    "দুপুর", "দুপুরে", "raat", "rate", "bikel", "bikale", "shondha", "shondhe", "dupur", "evening", "night"));
  /** Words that make an hour AM. */
  private static final java.util.Set<String> MORNING_WORDS = new java.util.HashSet<>(java.util.Arrays.asList(
    "am", "a.m", "a.m.", "সকাল", "সকালে", "ভোর", "ভোরে", "sokal", "sokale", "bhor", "bhore", "morning"));
  /** Words that mean the user is talking about a day, not a clock time. */
  private static final java.util.Set<String> DAY_WORDS = new java.util.HashSet<>(java.util.Arrays.asList(
    "আজ", "আজকে", "কাল", "আগামীকাল", "গতকাল", "আগামী", "পরশু", "aj", "ajke", "kal", "agamikal", "gatokal", "ajkal"));
  private static final java.util.Set<String> DATE_WORDS = new java.util.HashSet<>(java.util.Arrays.asList(
    "তারিখ", "tarikh", "জানুয়ারি", "ফেব্রুয়ারি", "মার্চ", "এপ্রিল", "মে", "জুন", "জুলাই", "আগস্ট",
    "সেপ্টেম্বর", "অক্টোবর", "নভেম্বর", "ডিসেম্বর", "january", "february", "march", "april", "may",
    "june", "july", "august", "september", "october", "november", "december", "শে", "তারিখে"));

  /** Splits into words, keeping Bengali combining marks attached to their base letter. */
  private static java.util.List<String> words(String text) {
    java.util.List<String> out = new java.util.ArrayList<>();
    if (text == null) return out;
    StringBuilder current = new StringBuilder();
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.isLetterOrDigit(c) || Character.getType(c) == Character.NON_SPACING_MARK
          || Character.getType(c) == Character.COMBINING_SPACING_MARK) current.append(c);
      else if (current.length() > 0) { out.add(current.toString()); current.setLength(0); }
    }
    if (current.length() > 0) out.add(current.toString());
    return out;
  }

  private static boolean anyWord(java.util.List<String> tokens, java.util.Set<String> wanted) {
    for (String token : tokens) if (wanted.contains(token)) return true;
    return false;
  }

  private BengaliNumbers() { }

  /** Converts Bengali and Devanagari digits to ASCII digits, leaving everything else alone. */
  static String digits(String input) {
    if (input == null) return "";
    StringBuilder out = new StringBuilder(input.length());
    for (int i = 0; i < input.length(); i++) {
      char c = input.charAt(i);
      int bengali = indexOf(BENGALI_DIGITS, c);
      int devanagari = indexOf(DEVANAGARI_DIGITS, c);
      if (bengali >= 0) out.append((char) ('0' + bengali));
      else if (devanagari >= 0) out.append((char) ('0' + devanagari));
      else out.append(c);
    }
    return out.toString();
  }

  private static int indexOf(char[] set, char c) {
    for (int i = 0; i < set.length; i++) if (set[i] == c) return i;
    return -1;
  }

  /** The value of a spoken number, or -1 when the words are not a number. */
  static int value(String phrase) {
    if (phrase == null) return -1;
    String text = digits(phrase.toLowerCase(Locale.ROOT)).trim().replaceAll("[^\\p{L}\\p{N}\\s]", "");
    if (text.isEmpty()) return -1;
    if (text.matches("\\d{1,7}")) return Integer.parseInt(text);
    int total = 0, current = 0;
    boolean any = false;
    for (String part : text.split("\\s+")) {
      if (part.isEmpty()) continue;
      Integer word = WORDS.get(part);
      if (word == null) word = BANGLISH.get(part);
      any = true;
      if (word == null && (part.endsWith("শো") || part.endsWith("শ"))) {
        Integer hundred = WORDS.get(part.substring(0, part.length() - (part.endsWith("শো") ? 2 : 1)));
        if (hundred != null && hundred > 0 && hundred < 10) word = hundred * 100;
      }
      if (word == null) return -1;
      if (word == 100 || word == 1000) {
        if (current == 0) current = 1;
        total += current * word;
        current = 0;
      } else current += word;
    }
    return any ? total + current : -1;
  }

  /** Suffixes that only mean "o'clock" and must be stripped before reading the hour. */
  private static final String[] CLOCK_SUFFIXES = { "টায়", "টাতে", "টার", "টা", "টো", "baje", "bajay", "tay", "ta", "oclock" };
  /** Words that shift the minutes instead of naming an hour. */
  private static final String[][] MODIFIERS = {
    { "সাড়ে", "sare", "sade" }, { "সোয়া", "শোয়া", "shoya", "sowa" },
    { "পৌনে", "পনে", "poune", "pone" }, { "দেড়", "deṛ", "der" }, { "আড়াই", "arai" }
  };

  /**
   * A spoken clock time as {hour, minute} on a 24-hour clock, or null when no clock time was
   * said at all. Understands ৮টা ৩০, 8:30, 8.30 pm, সাড়ে সাতটা (7:30), সোয়া সাতটা (7:15),
   * পৌনে আটটা (7:45), দেড়টা (1:30), আড়াইটা (2:30) and সকাল/সন্ধ্যা/রাত for am/pm.
   */
  static int[] time(String raw) {
    if (raw == null) return null;
    String text = digits(raw).toLowerCase(Locale.ROOT).trim();
    if (text.isEmpty()) return null;
    // "10 minutes later" or "2 hours" is a duration, not a time on the clock.
    text = text.replaceAll("(\\d{1,4}|[\\p{L}]+)\\s*(ঘন্টা|ঘণ্টা|ghonta|ghanta|hours?|hrs?|hr|মিনিট|mins?|minute|minutes|min)\\b", " ").trim();
    if (text.isEmpty()) return null;
    java.util.List<String> tokens = words(text);
    boolean afternoon = anyWord(tokens, AFTERNOON_WORDS);
    boolean morning = anyWord(tokens, MORNING_WORDS);

    // 1. Explicit h:mm or h.mm
    Matcher colon = Pattern.compile("(\\d{1,2})\\s*[:.]\\s*(\\d{2})").matcher(text);
    if (colon.find()) {
      int hour = Integer.parseInt(colon.group(1));
      int minute = Integer.parseInt(colon.group(2));
      if (minute > 59) return null;
      return adjust(hour, minute, afternoon, morning);
    }

    // 2. Day/date words without a clock cue are dates, not times.
    boolean dayWord = anyWord(tokens, DAY_WORDS);
    boolean dateCue = anyWord(tokens, DATE_WORDS);
    if ((dayWord || dateCue) && !Pattern.compile("(টা|টায়|টার|baje|bajay|o.?clock|:)").matcher(text).find()) return null;

    // 3. Word form: a clock cue must be present, otherwise "2 + 2" would become 02:00.
    boolean cue = Pattern.compile("(টা|টায়|টার|টা|baje|bajay|o.?clock|:|')").matcher(text).find();
    boolean modifierCue = false;
    for (String[] group : MODIFIERS) for (String word : group) if (text.contains(word)) modifierCue = true;
    if (!cue && !modifierCue) return null;

    // 3. Read the tokens in order, ignoring the "o'clock" endings.
    java.util.List<String> clockTokens = new java.util.ArrayList<>();
    for (String piece : words(text)) {
      String token = stripSuffix(piece);
      if (!token.isEmpty()) clockTokens.add(token);
    }
    int hour = -1, minute = -1;
    boolean half = false, quarter = false, threeQuarter = false, fixed = false;
    for (String token : clockTokens) {
      String modifier = modifierOf(token);
      if (modifier != null) {
        if (modifier.equals("হাফ")) { half = true; }
        else if (modifier.equals("সোয়া")) { quarter = true; }
        else if (modifier.equals("পৌনে")) { threeQuarter = true; }
        else if (modifier.equals("দেড়")) { hour = 1; minute = 30; fixed = true; }
        else if (modifier.equals("আড়াই")) { hour = 2; minute = 30; fixed = true; }
        continue;
      }
      int number = toInt(token);
      if (number < 0) continue;
      if (hour < 0) hour = number;
      else if (minute < 0 && number < 60) minute = number;
    }
    if (hour < 0 && !fixed) return null;
    if (hour > 24) return null;
    if (fixed) return adjust(hour, minute, afternoon, morning);
    if (minute < 0) minute = half ? 30 : quarter ? 15 : threeQuarter ? 45 : 0;
    if (threeQuarter) hour = hour - 1;
    return adjust(hour, minute, afternoon, morning);
  }

  private static String stripSuffix(String token) {
    String value = token;
    for (String suffix : CLOCK_SUFFIXES) {
      if (value.length() > suffix.length() && value.endsWith(suffix) && !value.equals(suffix)) {
        value = value.substring(0, value.length() - suffix.length());
        break;
      }
    }
    return value;
  }

  /** The canonical modifier this token means, or null when it is not a modifier. */
  private static String modifierOf(String token) {
    for (String word : MODIFIERS[0]) if (token.equals(word)) return "হাফ";
    for (String word : MODIFIERS[1]) if (token.equals(word)) return "সোয়া";
    for (String word : MODIFIERS[2]) if (token.equals(word)) return "পৌনে";
    for (String word : MODIFIERS[3]) if (token.equals(word)) return "দেড়";
    for (String word : MODIFIERS[4]) if (token.equals(word)) return "আড়াই";
    return null;
  }

  private static int[] adjust(int hour, int minute, boolean afternoon, boolean morning) {
    int h = hour;
    if (h < 0) h = 0;
    if (afternoon && h < 12) h += 12;
    if (morning && h == 12) h = 0;
    if (h == 24) h = 0;
    if (h > 23) h = h % 24;
    if (minute < 0 || minute > 59) minute = 0;
    return new int[]{h, minute};
  }

  private static int toInt(String token) {
    if (token == null) return -1;
    String value = token.trim();
    if (value.matches("\\d{1,4}")) return Integer.parseInt(value);
    return value(value);
  }

  /** Minutes from a spoken duration ("দশ মিনিট", "2 ঘণ্টা", "এক ঘণ্টা ত্রিশ মিনিট"), or -1. */
  static int durationMinutes(String raw) {
    if (raw == null) return -1;
    String text = digits(raw).toLowerCase(Locale.ROOT);
    Matcher matcher = Pattern.compile(
        "(\\d{1,4}|[\\p{L}]+)\\s*(ঘন্টা|ঘণ্টা|ghonta|ghanta|hours?|hrs?|hr|মিনিট|মিন\\b|minute|minutes|mins?|min)").matcher(text);
    int minutes = -1;
    while (matcher.find()) {
      int amount = toInt(matcher.group(1));
      if (amount < 0) continue;
      String unit = matcher.group(2);
      boolean hour = unit.startsWith("ঘণ") || unit.startsWith("ঘন") || unit.equals("ghonta") || unit.equals("ghanta")
        || unit.startsWith("hour") || unit.startsWith("hr");
      minutes = (minutes < 0 ? 0 : minutes) + (hour ? amount * 60 : amount);
    }
    return minutes;
  }

  /** One normalised line the model can rely on, or "" when nothing numeric was said. */
  static String describe(String raw) {
    if (raw == null || raw.trim().isEmpty()) return "";
    StringBuilder out = new StringBuilder();
    int[] clock = time(raw);
    if (clock != null) out.append("clock=").append(String.format(Locale.US, "%02d:%02d", clock[0], clock[1]));
    int minutes = durationMinutes(raw);
    if (minutes > 0) {
      if (out.length() > 0) out.append(", ");
      out.append("durationMinutes=").append(minutes);
    }
    String plain = digits(raw);
    if (out.length() == 0 && plain.matches("(?s).*\\d.*")) {
      String only = plain.replaceAll("[^0-9+]", " ").trim().replaceAll("\\s+", " ");
      if (!only.isEmpty()) out.append("digits=").append(only);
    }
    return out.toString();
  }
}
